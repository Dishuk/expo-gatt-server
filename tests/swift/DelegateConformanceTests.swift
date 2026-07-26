import CoreBluetooth
import XCTest

@testable import GattServerCore

/// Guards the one class of bug the compiler cannot catch here.
///
/// `CBPeripheralManagerDelegate`'s requirements are all optional, so a method that *looks* like a
/// delegate callback but does not match the selector compiles silently — no error, and not even the
/// "nearly matches" warning an unfulfilled non-optional requirement produces. Two of them were
/// misspelled this way: `peripheralManager(_:didStartAdvertising:)` for
/// `peripheralManagerDidStartAdvertising(_:error:)`, which left every `startAdvertising` promise
/// unresolved, and `peripheralManagerIsReady(_:)` for `peripheralManagerIsReady(toUpdateSubscribers:)`,
/// which left a notification queue parked by `updateValue` with nothing to drain it. Both shipped
/// through a green build, a green unit suite and a pod-target compile.
///
/// Asserting on the selector is the only check that fails when the spelling drifts, because it asks
/// the Objective-C runtime the same question CoreBluetooth asks before dispatching.
final class DelegateConformanceTests: XCTestCase {
  /// Every `CBPeripheralManagerDelegate` callback the manager means to receive. A method removed on
  /// purpose should be deleted from this list in the same change; anything else failing here is a
  /// spelling that CoreBluetooth will never call.
  private static let implementedCallbacks = [
    "peripheralManagerDidUpdateState:",
    "peripheralManagerDidStartAdvertising:error:",
    "peripheralManager:didAddService:error:",
    "peripheralManager:central:didSubscribeToCharacteristic:",
    "peripheralManager:central:didUnsubscribeFromCharacteristic:",
    "peripheralManager:didReceiveReadRequest:",
    "peripheralManager:didReceiveWriteRequests:",
    "peripheralManagerIsReadyToUpdateSubscribers:",
  ]

  func testManagerRespondsToEveryDelegateCallbackItImplements() {
    for name in Self.implementedCallbacks {
      XCTAssertTrue(
        GattServerManager.instancesRespond(to: Selector(name)),
        "GattServerManager does not respond to \(name). CoreBluetooth dispatches by selector, so a " +
          "method spelled any other way is never called — check the argument labels against the " +
          "CBPeripheralManagerDelegate declaration."
      )
    }
  }

  /// The misspellings that actually shipped, pinned so neither can come back under a name that reads
  /// plausibly. Both compile; neither is ever invoked.
  func testKnownMisspellingsAreNotWhatTheManagerImplements() {
    for wrong in ["peripheralManager:didStartAdvertising:", "peripheralManagerIsReady:"] {
      XCTAssertFalse(
        GattServerManager.instancesRespond(to: Selector(wrong)),
        "GattServerManager implements \(wrong), which is not a CBPeripheralManagerDelegate " +
          "requirement and will never be called."
      )
    }
  }
}
