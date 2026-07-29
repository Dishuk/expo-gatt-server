import CoreBluetooth
import XCTest

@testable import GattServerCore

/// `peripheralManagerDidStartAdvertising` names no start, so an outstanding one cannot be told from the
/// call that displaced it. The coordinator refuses the overlap rather than handing the second call the
/// first one's outcome.
final class AdvertisingStartTests: XCTestCase {
  /// One start's promise, and whether it has been settled at all.
  private final class Outcome {
    var settled = false
    var error: Error?

    func settle(_ error: Error?) {
      settled = true
      self.error = error
    }
  }

  /// No `CBPeripheralManager`: `begin` reaches it only through optional chaining, so the settlement
  /// rules are exercisable without a radio.
  private func makeCoordinator() -> AdvertisingCoordinator {
    AdvertisingCoordinator(peripheral: { nil }, isDatabasePublished: { true })
  }

  func testOverlappingStartIsRefusedAndLeavesTheFirstPending() {
    let coordinator = makeCoordinator()
    let first = Outcome()
    let second = Outcome()

    coordinator.begin(localName: nil, serviceUuids: nil, timeoutMs: 0, completion: first.settle)
    coordinator.begin(localName: nil, serviceUuids: nil, timeoutMs: 0, completion: second.settle)

    XCTAssertFalse(first.settled, "the outstanding start keeps its promise until CoreBluetooth reports it")
    XCTAssertTrue(second.settled, "the overlapping start is refused rather than displacing it")
    XCTAssertNotNil(second.error)

    // The refused call must not have consumed the outcome the first one is still waiting for.
    coordinator.didStart(error: nil)
    XCTAssertTrue(first.settled, "the first start is settled by its own callback")
    XCTAssertNil(first.error, "and with its own result")
  }

  func testStartIsAcceptedAgainOnceTheOutstandingOneSettles() {
    let coordinator = makeCoordinator()
    let second = Outcome()

    coordinator.begin(localName: nil, serviceUuids: nil, timeoutMs: 0) { _ in }
    coordinator.didStart(error: nil)
    coordinator.begin(localName: nil, serviceUuids: nil, timeoutMs: 0, completion: second.settle)

    XCTAssertFalse(second.settled, "a settled start no longer blocks the next one")
  }

  func testStopReleasesTheOutstandingStart() {
    let coordinator = makeCoordinator()
    let second = Outcome()

    coordinator.begin(localName: nil, serviceUuids: nil, timeoutMs: 0) { _ in }
    coordinator.stop()
    coordinator.begin(localName: nil, serviceUuids: nil, timeoutMs: 0, completion: second.settle)

    XCTAssertFalse(second.settled, "stopAdvertising clears the way for a new start")
  }
}
