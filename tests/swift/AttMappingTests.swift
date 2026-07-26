import CoreBluetooth
import XCTest

@testable import GattServerCore

/// The translations between the module's platform-neutral vocabulary and CoreBluetooth's.
///
/// Every one of these is observable by a peer or by a consumer: the ATT error byte goes on the wire, the
/// UUID spelling is what a consumer's `===` compares against, and the state string is what a consumer
/// branches on. All three have an Android counterpart that must agree.
final class AttMappingTests: XCTestCase {
  // MARK: - ATT error codes

  /// The whole point of the mapping is that it is the identity over the specified range: JavaScript
  /// passes an ATT error code, and the same byte has to reach the air on both platforms. Asserting the
  /// round trip catches a transposed case that spelling out 18 expected constants would not, because a
  /// swapped pair still type-checks and still looks plausible.
  func testEverySpecifiedCodeMapsToItself() {
    for status in 0...0x11 {
      XCTAssertEqual(
        attErrorCode(for: status).rawValue, status,
        "status \(status) should map to the CBATTError.Code with the same raw value"
      )
    }
  }

  func testSuccessIsDistinctFromEveryError() {
    XCTAssertEqual(attErrorCode(for: 0x00), .success)
    for status in 1...0x11 {
      XCTAssertNotEqual(attErrorCode(for: status), .success)
    }
  }

  /// `respond(to:withResult:)` accepts only a `CBATTError.Code`, so the codes the specification defines
  /// beyond 0x11 — and the application and profile ranges — have no representation. Downgrading them to
  /// success would report a failed operation as a successful one, so they become "unlikely error".
  func testUnrepresentableStatusesBecomeUnlikelyErrorRatherThanSuccess() {
    for status in [0x12, 0x13, 0x80, 0xFF, 256, -1] {
      let mapped = attErrorCode(for: status)
      XCTAssertEqual(mapped, .unlikelyError, "status \(status)")
      XCTAssertNotEqual(mapped, .success, "status \(status) must never read as success")
    }
  }

  // MARK: - UUID spelling

  /// Java's `UUID.toString` produces the lowercase 128-bit form, and event payloads have to match it on
  /// both platforms or a consumer's equality check against one canonical spelling fails on one of them.
  /// `CBUUID.uuidString` does not: it uppercases, and echoes a short UUID back in its short form.
  func testShortUuidsExpandOntoTheBluetoothBaseUuid() {
    XCTAssertEqual(
      CBUUID(string: "180D").normalizedString, "0000180d-0000-1000-8000-00805f9b34fb"
    )
    XCTAssertEqual(
      CBUUID(string: "2A37").normalizedString, "00002a37-0000-1000-8000-00805f9b34fb"
    )
  }

  func testThirtyTwoBitUuidsExpandOntoTheBluetoothBaseUuid() {
    XCTAssertEqual(
      CBUUID(string: "0000180D").normalizedString, "0000180d-0000-1000-8000-00805f9b34fb"
    )
    XCTAssertEqual(
      CBUUID(string: "12345678").normalizedString, "12345678-0000-1000-8000-00805f9b34fb"
    )
  }

  func testOneHundredAndTwentyEightBitUuidsAreLowercasedAndOtherwiseUntouched() {
    XCTAssertEqual(
      CBUUID(string: "0000180D-0000-1000-8000-00805F9B34FB").normalizedString,
      "0000180d-0000-1000-8000-00805f9b34fb"
    )
  }

  /// A 16-bit alias and its own 128-bit expansion are the same UUID, so they must not arrive spelled
  /// two different ways.
  func testAnAliasAndItsExpansionNormaliseIdentically() {
    XCTAssertEqual(
      CBUUID(string: "180D").normalizedString,
      CBUUID(string: "0000180D-0000-1000-8000-00805F9B34FB").normalizedString
    )
  }

  func testEveryNormalisedUuidHasTheShapeJavaProduces() {
    for spelling in ["180D", "0000180D", "0000180d-0000-1000-8000-00805f9b34fb"] {
      let normalized = CBUUID(string: spelling).normalizedString

      XCTAssertEqual(normalized.count, 36, spelling)
      XCTAssertEqual(normalized, normalized.lowercased(), spelling)
      XCTAssertEqual(
        normalized.split(separator: "-").map(\.count), [8, 4, 4, 4, 12], spelling
      )
    }
  }

  // MARK: - Adapter state

  /// The union is shared with Android, which maps its own constants onto the same strings.
  func testEveryManagerStateMapsToTheSharedVocabulary() {
    let expected: [CBManagerState: String] = [
      .poweredOn: "poweredOn",
      .poweredOff: "poweredOff",
      .resetting: "resetting",
      .unsupported: "unsupported",
      .unauthorized: "unauthorized",
      .unknown: "unknown",
    ]

    for (state, name) in expected {
      XCTAssertEqual(normalizedBluetoothState(state), name)
    }
  }

  func testStatesMapOntoDistinctNames() {
    let states: [CBManagerState] = [
      .poweredOn, .poweredOff, .resetting, .unsupported, .unauthorized, .unknown,
    ]

    let names = Set(states.map(normalizedBluetoothState))

    XCTAssertEqual(names.count, states.count)
  }

  // MARK: - MTU

  /// iOS only ever reports a payload length, so the ATT_MTU is reconstructed by adding back the
  /// one-octet opcode and two-octet handle of an `ATT_HANDLE_VALUE_NTF` PDU.
  func testMtuIsThePayloadPlusTheNotificationHeader() {
    let mtu = DeviceMtu(maxNotificationPayload: 20)

    XCTAssertEqual(mtu.maxNotificationPayload, 20)
    XCTAssertEqual(mtu.mtu, 23)
  }

  /// 20 octets is what a link carries before any negotiation, and it has to report the specification's
  /// default ATT_MTU of 23 rather than something else.
  func testTheUnnegotiatedPayloadReportsTheSpecifiedDefaultMtu() {
    XCTAssertEqual(DeviceMtu(maxNotificationPayload: defaultAttMtu - attNotificationHeaderSize).mtu,
                   defaultAttMtu)
    XCTAssertEqual(defaultAttMtu, 23)
    XCTAssertEqual(attNotificationHeaderSize, 3)
  }

  func testTheIdentityHoldsAcrossNegotiatedSizes() {
    for payload in [20, 100, 244, 509] {
      XCTAssertEqual(DeviceMtu(maxNotificationPayload: payload).mtu - attNotificationHeaderSize,
                     payload)
    }
  }
}
