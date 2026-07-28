import CoreBluetooth
import XCTest

@testable import GattServerCore

/// The translations between the module's platform-neutral vocabulary and CoreBluetooth's.
/// Each mapping has an Android counterpart that must agree.
final class AttMappingTests: XCTestCase {
  // MARK: - Advertised UUID width

  /// `CBUUID` advertises the width it was built from; a base-range UUID contracts to its shortest form.
  func testABaseRangeUuidContractsToItsShortestSpelling() {
    let expanded = CBUUID(string: "0000180D-0000-1000-8000-00805F9B34FB")
    XCTAssertEqual(expanded.data.count, 16, "precondition: the expanded form really is 16 octets")

    let advertised = expanded.advertisedForm
    XCTAssertEqual(advertised.data.count, 2)
    XCTAssertEqual(advertised, CBUUID(string: "180D"))
    // Contracting must not change which attribute it names.
    XCTAssertEqual(advertised.normalizedString, expanded.normalizedString)
  }

  func testA32BitAliasContractsToFourOctets() {
    let expanded = CBUUID(string: "12345678-0000-1000-8000-00805F9B34FB")

    XCTAssertEqual(expanded.advertisedForm.data.count, 4)
    XCTAssertEqual(expanded.advertisedForm.normalizedString, expanded.normalizedString)
  }

  /// A vendor UUID is not in the base range and has no shorter spelling, so it must survive untouched.
  func testAVendorUuidIsLeftAlone() {
    let vendor = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    XCTAssertEqual(vendor.advertisedForm, vendor)
    XCTAssertEqual(vendor.advertisedForm.data.count, 16)
  }

  /// Already-short input is idempotent, so the advertising path can apply this unconditionally.
  func testAlreadyShortUuidsAreUnchanged() {
    XCTAssertEqual(CBUUID(string: "180D").advertisedForm, CBUUID(string: "180D"))
    XCTAssertEqual(CBUUID(string: "180D").advertisedForm.data.count, 2)
  }

  // MARK: - ATT error codes

  /// The mapping is the identity over the specified range: an ATT error code has to reach the air
  /// unchanged on both platforms.
  func testEverySpecifiedCodeMapsToItself() {
    for status in 0...0x11 {
      XCTAssertEqual(
        attErrorCode(for: status).rawValue, status,
        "status \(status) should map to the CBATTError.Code with the same raw value"
      )
    }
  }

  /// Codes beyond 0x11 — the application and profile ranges — have no `CBATTError.Code`
  /// representation and become "unlikely error" rather than silently reporting success.
  func testUnrepresentableStatusesBecomeUnlikelyErrorRatherThanSuccess() {
    for status in [0x12, 0x13, 0x80, 0xFF, 256, -1] {
      let mapped = attErrorCode(for: status)
      XCTAssertEqual(mapped, .unlikelyError, "status \(status)")
      XCTAssertNotEqual(mapped, .success, "status \(status) must never read as success")
    }
  }

  // MARK: - UUID spelling

  /// Java's `UUID.toString` produces the lowercase 128-bit form; `CBUUID.uuidString` does not — it
  /// uppercases and echoes a short UUID back in its short form.
  func testShortUuidsExpandOntoTheBluetoothBaseUuid() {
    XCTAssertEqual(
      CBUUID(string: "180D").normalizedString, "0000180d-0000-1000-8000-00805f9b34fb"
    )
    XCTAssertEqual(
      CBUUID(string: "2A37").normalizedString, "00002a37-0000-1000-8000-00805f9b34fb"
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

  // MARK: - MTU

  /// iOS only ever reports a payload length; the ATT_MTU is reconstructed by adding back the
  /// one-octet opcode and two-octet handle of an `ATT_HANDLE_VALUE_NTF` PDU.
  func testMtuIsThePayloadPlusTheNotificationHeader() {
    let mtu = DeviceMtu(maxNotificationPayload: 20)

    XCTAssertEqual(mtu.maxNotificationPayload, 20)
    XCTAssertEqual(mtu.mtu, 23)
  }

  /// The unnegotiated payload (20 octets) has to report the specification's default ATT_MTU of 23.
  func testTheUnnegotiatedPayloadReportsTheSpecifiedDefaultMtu() {
    XCTAssertEqual(DeviceMtu(maxNotificationPayload: defaultAttMtu - attNotificationHeaderSize).mtu,
                   defaultAttMtu)
    XCTAssertEqual(defaultAttMtu, 23)
    XCTAssertEqual(attNotificationHeaderSize, 3)
  }

  /// `min(mtu - 3, 512)` is what `getMtu` documents and what Android reports; the second bound only
  /// binds above an ATT_MTU of 515, which is where the two platforms used to disagree.
  func testThePayloadIsBoundedByWhatAnAttributeMayHold() {
    let link = DeviceMtu(maxNotificationPayload: 514)

    XCTAssertEqual(link.maxNotificationPayload, maxAttributeValueLength)
    XCTAssertEqual(link.maxNotificationPayload, 512)
    // The ATT_MTU itself is the link's, not the bounded one — only the payload budget is capped.
    XCTAssertEqual(link.mtu, 517)
  }

  func testThePayloadIsUntouchedBelowTheAttributeBound() {
    XCTAssertEqual(DeviceMtu(maxNotificationPayload: 512).maxNotificationPayload, 512)
  }
}
