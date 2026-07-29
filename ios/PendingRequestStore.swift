import CoreBluetooth
import Foundation

/// Value withheld from a partially delegated batch; baseline used to detect stale overwrites.
struct DeferredWrite {
  let value: Data
  /// nil when attribute had no value initially (distinct from empty).
  let baseline: Data?
}

struct PendingRequest {
  let request: CBATTRequest
  /// True for read (carries response value); false for write (value is input only).
  let isRead: Bool
  /// Auto values for non-delegated characteristics, withheld until batch is accepted (atomicity).
  let deferredValues: [CharacteristicAddress: DeferredWrite]
  /// Expiry to cancel on answer or discard.
  let timeout: DispatchWorkItem?
}

/// The ATT requests handed to JavaScript and not yet answered, each with the expiry that answers it if
/// JavaScript never does.
///
/// Nothing here talks to CoreBluetooth: the store decides *which* requests are affected and the caller
/// sends the responses, so every `respond(to:withResult:)` stays on one path.
final class PendingRequestStore {
  /// Milliseconds a delegated request may go unanswered; `0` disables the expiry entirely.
  private let timeoutMs: Int
  /// Answers a request the expiry claimed, so the central's transaction completes rather than stalling.
  private let onExpire: (CBATTRequest) -> Void

  private var requests: [Int: PendingRequest] = [:]
  private var counter = 0

  init(timeoutMs: Int, onExpire: @escaping (CBATTRequest) -> Void) {
    self.timeoutMs = timeoutMs
    self.onExpire = onExpire
  }

  func nextId() -> Int {
    counter += 1
    return counter
  }

  func resetIds() {
    counter = 0
  }

  subscript(requestId: Int) -> PendingRequest? {
    requests[requestId]
  }

  /// Arms expiry for unanswered delegated requests; called before the event fires so sync listeners can still find it.
  func register(
    _ requestId: Int, request: CBATTRequest, isRead: Bool,
    deferredValues: [CharacteristicAddress: DeferredWrite] = [:]
  ) {
    var timeout: DispatchWorkItem?
    if timeoutMs > 0 {
      let work = DispatchWorkItem { [weak self] in
        guard let self = self, let pending = self.discard(requestId) else { return }
        self.onExpire(pending.request)
      }
      timeout = work
      DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeoutMs), execute: work)
    }
    requests[requestId] = PendingRequest(
      request: request, isRead: isRead, deferredValues: deferredValues, timeout: timeout
    )
  }

  @discardableResult
  func discard(_ requestId: Int) -> PendingRequest? {
    guard let pending = requests.removeValue(forKey: requestId) else { return nil }
    pending.timeout?.cancel()
    return pending
  }

  /// Forgets matching requests without answering them. Correct only where the central cannot hear a
  /// response anyway — the bearers are gone with the connections.
  func discard(where predicate: (PendingRequest) -> Bool) {
    for (requestId, pending) in requests where predicate(pending) {
      pending.timeout?.cancel()
      requests.removeValue(forKey: requestId)
    }
  }

  /// Forgets matching requests and hands them back to be answered. Bookkeeping happens first, so a
  /// response cannot re-enter the module mid-teardown and find half-cleared state.
  func claim(where predicate: (PendingRequest) -> Bool) -> [CBATTRequest] {
    var claimed: [CBATTRequest] = []
    for (requestId, pending) in requests where predicate(pending) {
      pending.timeout?.cancel()
      requests.removeValue(forKey: requestId)
      claimed.append(pending.request)
    }
    return claimed
  }
}
