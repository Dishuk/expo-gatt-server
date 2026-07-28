import CoreBluetooth
import Foundation

/// Timeout for peripheralManagerDidStartAdvertising callback (Apple guarantees nothing). Matches Android AdvertiseCallback.
let advertisingStartTimeoutMs = 30_000

/// The radio side of the peripheral: what is on the air, and the single promise waiting for
/// CoreBluetooth to say so.
///
/// The promise is settled by exactly one of `peripheralManagerDidStartAdvertising`, a restart, a stop,
/// the start bound expiring, or Bluetooth going down — whichever claims it first. Main queue only.
final class AdvertisingCoordinator {
  private let peripheral: () -> CBPeripheralManager?
  private let isDatabasePublished: () -> Bool

  private var completion: ((Error?) -> Void)?
  private var airtimeTimeout: DispatchWorkItem?

  /// Cancelled by `claimCompletion`, live only while a start awaits.
  private var startTimeout: DispatchWorkItem?

  /// The `timeoutMs` to arm after the start succeeds.
  private var pendingAirtimeMs = 0

  /// Bumped by every stop; lets a waiting start detect that stop() was called.
  private(set) var generation = 0

  init(
    peripheral: @escaping () -> CBPeripheralManager?,
    isDatabasePublished: @escaping () -> Bool
  ) {
    self.peripheral = peripheral
    self.isDatabasePublished = isDatabasePublished
  }

  var isAdvertising: Bool {
    peripheral()?.isAdvertising ?? false
  }

  /// Builds only CBAdvertisementDataLocalNameKey and CBAdvertisementDataServiceUUIDsKey (per Apple docs).
  func begin(
    localName: String?,
    serviceUuids: [CBUUID]?,
    timeoutMs: Int,
    completion: @escaping (Error?) -> Void
  ) {
    guard isDatabasePublished() else {
      completion(GattServerError.databaseNotPublished)
      return
    }
    let advertisedUuids = (serviceUuids ?? []).map { $0.advertisedForm }
    do {
      try assertAdvertisementFits(localName: localName, serviceUuids: advertisedUuids)
    } catch {
      completion(advertisingError(error.localizedDescription))
      return
    }
    let displaced = claimCompletion()
    displaced?(advertisingError("Advertising restarted"))
    // Stop the old one before starting the new one. Callback carries no identity, so race remains possible if starts are issued back-to-back.
    if displaced != nil {
      peripheral()?.stopAdvertising()
    }
    self.completion = completion
    var advertisementData: [String: Any] = [:]
    if let name = localName {
      advertisementData[CBAdvertisementDataLocalNameKey] = name
    }
    if !advertisedUuids.isEmpty {
      advertisementData[CBAdvertisementDataServiceUUIDsKey] = advertisedUuids
    }
    cancelAirtimeTimeout()
    // Held until peripheralManagerDidStartAdvertising fires (which reports the on-air time started).
    pendingAirtimeMs = timeoutMs
    peripheral()?.startAdvertising(advertisementData)
    armStartTimeout()
  }

  func stop() {
    generation += 1
    cancelAirtimeTimeout()
    pendingAirtimeMs = 0
    peripheral()?.stopAdvertising()
    claimCompletion()?(advertisingError("Advertising stopped"))
  }

  /// The outcome of a `startAdvertising:` call. Arms the airtime bound only if the start succeeded.
  func didStart(error: Error?) {
    if error == nil {
      scheduleAirtimeTimeout(pendingAirtimeMs)
    }
    pendingAirtimeMs = 0
    claimCompletion()?(error)
  }

  /// Bluetooth dropped below powered on.
  ///
  /// `peripheralManagerDidStartAdvertising:error:` is documented only as returning "the result of a
  /// startAdvertising: call", with nothing promising one arrives when the state drops instead — so a
  /// start CoreBluetooth already has is settled here rather than left pending for the process lifetime.
  /// Claiming the completion stops a late callback settling it twice.
  func discard(reason: Error) {
    cancelAirtimeTimeout()
    pendingAirtimeMs = 0
    claimCompletion()?(reason)
  }

  /// Takes ownership so the promise is settled by exactly one of didStartAdvertising, restart, stop, or Bluetooth down.
  private func claimCompletion() -> ((Error?) -> Void)? {
    defer {
      completion = nil
      cancelStartTimeout()
    }
    return completion
  }

  /// Stops advertising on timeout (avoiding hung start with radio on).
  private func armStartTimeout() {
    cancelStartTimeout()
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      self.startTimeout = nil
      self.pendingAirtimeMs = 0
      self.peripheral()?.stopAdvertising()
      self.claimCompletion()?(advertisingError(
        "The Bluetooth stack did not report the advertisement as started within " +
          "\(advertisingStartTimeoutMs) ms. Nothing is advertising."
      ))
    }
    startTimeout = work
    DispatchQueue.main.asyncAfter(
      deadline: .now() + .milliseconds(advertisingStartTimeoutMs), execute: work
    )
  }

  private func cancelStartTimeout() {
    startTimeout?.cancel()
    startTimeout = nil
  }

  /// Emulates AdvertiseSettings.setTimeout by stopping at the limit (matching Android behavior).
  private func scheduleAirtimeTimeout(_ timeoutMs: Int) {
    guard timeoutMs > 0 else { return }
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      // Cleared first, so `stop` does not try to cancel the item running it.
      self.airtimeTimeout = nil
      self.stop()
    }
    airtimeTimeout = work
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeoutMs), execute: work)
  }

  private func cancelAirtimeTimeout() {
    airtimeTimeout?.cancel()
    airtimeTimeout = nil
  }
}
