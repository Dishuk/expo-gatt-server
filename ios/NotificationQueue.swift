import CoreBluetooth
import Foundation

/// Upper bound per central while the transmit queue is full. Per-central limit prevents one silent link from starving others.
private let maxQueuedNotifications = 64

/// How long a parked notification waits before abandoned. Above `attTransactionTimeoutMs` so slow links aren't cut off prematurely.
private let notificationTimeoutMs = 35_000

struct QueuedNotification {
  let id: Int
  let deviceId: String
  let address: CharacteristicAddress
  let characteristic: CBMutableCharacteristic
  let central: CBCentral
  let value: Data
  let completion: (Error?) -> Void
  /// Expiry held with entry so timeout and removal cannot separate.
  var timeout: DispatchWorkItem?
}

/// The notifications CoreBluetooth has refused because its transmit queue was full, oldest first.
///
/// `updateValue(_:for:onSubscribedCentrals:)` returning `false` means "try again when
/// `peripheralManagerIsReady` fires", so an entry it refuses is retained rather than failed. Main queue
/// only, like everything else CoreBluetooth hands back.
final class NotificationQueue {
  /// Delivers one entry, settling its completion. Returns `false` only when the transmit queue is full.
  private let deliver: (QueuedNotification) -> Bool
  /// Whether there is still a peripheral manager to deliver through. A drain into a stopped server
  /// would settle every waiting entry with the same failure rather than leaving them for `stop`.
  private let isActive: () -> Bool

  private var pending: [QueuedNotification] = []
  private var nextId = 0

  init(isActive: @escaping () -> Bool, deliver: @escaping (QueuedNotification) -> Bool) {
    self.isActive = isActive
    self.deliver = deliver
  }

  func makeId() -> Int {
    nextId += 1
    return nextId
  }

  /// Delivers [entry] now, or parks it behind whatever is already waiting for this central. Throws only
  /// when this central's share of the queue is already full.
  func submit(_ entry: QueuedNotification) throws {
    guard pending.isEmpty else {
      let queuedForCentral = pending.reduce(0) {
        $0 + ($1.deviceId == entry.deviceId ? 1 : 0)
      }
      guard queuedForCentral < maxQueuedNotifications else {
        throw GattServerError.notifyQueueFull(limit: maxQueuedNotifications)
      }
      park(entry)
      return
    }

    if !deliver(entry) {
      park(entry)
    }
  }

  /// Delivers queued notifications until one is refused, dequeuing first so completion callbacks don't interfere.
  func drain() {
    while let next = pending.first {
      pending.removeFirst()
      guard deliver(next) else {
        // Requeue at head with existing expiry (don't re-arm).
        pending.insert(next, at: 0)
        return
      }
      next.timeout?.cancel()
    }
  }

  func failAll(
    _ error: GattServerError, where predicate: (QueuedNotification) -> Bool = { _ in true }
  ) {
    let abandoned = pending.filter(predicate)
    guard !abandoned.isEmpty else { return }
    pending.removeAll(where: predicate)
    for entry in abandoned {
      entry.timeout?.cancel()
      entry.completion(error)
    }
    // Callback may have been for abandoned entry; retry rest to avoid forever-waiting entries.
    if !pending.isEmpty && isActive() {
      drain()
    }
  }

  /// Queues an entry that transmit queue refused, with expiry timeout.
  private func park(_ entry: QueuedNotification) {
    var parked = entry
    let id = entry.id
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      guard let index = self.pending.firstIndex(where: { $0.id == id }) else { return }
      let abandoned = self.pending.remove(at: index)
      abandoned.completion(GattServerError.notificationTimedOut(timeoutMs: notificationTimeoutMs))
      // Callback may never arrive, so retry rest instead of waiting for each to expire.
      self.drain()
    }
    parked.timeout = work
    pending.append(parked)
    DispatchQueue.main.asyncAfter(
      deadline: .now() + .milliseconds(notificationTimeoutMs), execute: work
    )
  }
}
