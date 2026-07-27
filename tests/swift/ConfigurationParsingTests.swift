import CoreBluetooth
import Foundation
import XCTest

@testable import GattServerCore

/// The decoding that sits between an untyped JavaScript configuration and CoreBluetooth.
///
/// Every case here goes through `[Any]` holding `Double`, because that is what expo-modules-core
/// actually hands over: an untyped `[String: Any]` argument is converted by `DynamicRawType` through
/// `JavaScriptValue.getAny()`, which maps every JS number to `getDouble()`. Writing the fixtures as
/// `[1, 2, 3]` would type them as `[Int]` and test a shape that never occurs — which is how
/// `map["value"] as? [Int]` survived: it reads correctly, compiles, and returns nil every time.
final class ConfigurationParsingTests: XCTestCase {
  /// Numbers exactly as they arrive from the JS runtime.
  private func jsNumbers(_ values: [Double]) -> [Any] {
    values.map { $0 as Any }
  }

  // MARK: - The decoding that was silently failing

  func testDecodesTheDoublesJavaScriptActuallySends() throws {
    let decoded = try parseByteArray(jsNumbers([0, 1, 127, 255]), field: "characteristic")
    XCTAssertEqual(decoded, Data([0, 1, 127, 255]))
  }

  /// Pins the exact cast that failed. If someone reintroduces `as? [Int]`, this is what catches it.
  func testTheOldIntCastCouldNeverHaveSucceeded() {
    let asSent: Any = jsNumbers([1, 2, 3])
    XCTAssertNil(asSent as? [Int], "JS numbers arrive as Double; [Int] is the wrong target type.")
    XCTAssertNotNil(asSent as? [Double])
  }

  func testWholeNumbersSurviveEvenWhenSpelledAsDoubles() throws {
    XCTAssertEqual(try parseByteArray(jsNumbers([42.0]), field: "descriptor"), Data([42]))
  }

  // MARK: - Absent versus empty

  /// A characteristic distinguishes the two: absent means every read is delegated, `[]` means reads
  /// are answered from a present, zero-length value.
  func testAbsentIsNilAndEmptyIsZeroLength() throws {
    XCTAssertNil(try parseByteArray(nil, field: "characteristic"))
    XCTAssertNil(try parseByteArray(NSNull(), field: "characteristic"))
    XCTAssertEqual(try parseByteArray([Any](), field: "characteristic"), Data())
  }

  // MARK: - Rejections

  func testRejectsANonIntegralNumber() {
    XCTAssertThrowsError(try parseByteArray(jsNumbers([1.5]), field: "characteristic")) { error in
      XCTAssertTrue(
        "\(error.localizedDescription)".contains("between 0 and 255"),
        "unexpected message: \(error.localizedDescription)"
      )
    }
  }

  func testRejectsOutOfRangeValues() {
    XCTAssertThrowsError(try parseByteArray(jsNumbers([256]), field: "characteristic"))
    XCTAssertThrowsError(try parseByteArray(jsNumbers([-1]), field: "characteristic"))
  }

  /// `true` bridges to `NSNumber(1)`, so without an explicit guard a boolean would silently become a
  /// byte. Android refuses it because Kotlin's `Boolean` is not a `Number`; this keeps the two the same.
  func testRejectsBooleansRatherThanBridgingThemToOne() {
    XCTAssertThrowsError(try parseByteArray([true as Any], field: "characteristic"))
  }

  func testRejectsAValueThatIsNotAnArray() {
    XCTAssertThrowsError(try parseByteArray("not an array" as Any, field: "descriptor")) { error in
      XCTAssertTrue(
        "\(error.localizedDescription)".contains("Expected an array of byte values"),
        "unexpected message: \(error.localizedDescription)"
      )
    }
  }

  func testNamesTheFieldAndIndexItRejected() {
    XCTAssertThrowsError(try parseByteArray(jsNumbers([0, 300]), field: "descriptor")) { error in
      let message = "\(error.localizedDescription)"
      XCTAssertTrue(message.contains("descriptor"), message)
      XCTAssertTrue(message.contains("index 1"), message)
    }
  }

  // MARK: - The typed path

  /// `parseBytes` serves declared `[Int]` parameters, which expo-modules-core converts through
  /// `DynamicIntType` — genuinely `[Int]` on arrival, which is why those call sites were never broken.
  func testTypedParametersStillDecode() throws {
    XCTAssertEqual(try parseBytes([0, 255], field: "notification"), Data([0, 255]))
    XCTAssertThrowsError(try parseBytes([256], field: "notification"))
    XCTAssertThrowsError(try parseBytes([-1], field: "notification"))
  }
}

/// The millisecond durations a configuration carries.
///
/// Re-checked natively rather than trusted from the TypeScript layer, because the native module is
/// reachable directly — the same rule the byte and UUID checks follow. Android has always re-checked
/// these; iOS took the advertising timeout on trust, and the two platforms disagreed on exactly the
/// input Swift bridges and Kotlin does not.
final class TimeoutParsingTests: XCTestCase {
  private func advertisingTimeout(_ value: Any?) throws -> Int {
    try parseTimeoutMs(
      value, field: "advertising timeout", bound: maxAdvertisingTimeoutMs,
      default: 0, boundDescription: "\(maxAdvertisingTimeoutMs) milliseconds"
    )
  }

  func testAnAbsentTimeoutTakesTheDefault() throws {
    XCTAssertEqual(try advertisingTimeout(nil), 0)
    XCTAssertEqual(try advertisingTimeout(NSNull()), 0)
  }

  func testTheBoundsAreInclusiveAtBothEnds() throws {
    XCTAssertEqual(try advertisingTimeout(0.0), 0)
    XCTAssertEqual(try advertisingTimeout(Double(maxAdvertisingTimeoutMs)), maxAdvertisingTimeoutMs)
  }

  func testAValueOutsideTheBoundsIsRejected() {
    XCTAssertThrowsError(try advertisingTimeout(-1.0))
    XCTAssertThrowsError(try advertisingTimeout(Double(maxAdvertisingTimeoutMs) + 1))
  }

  func testAFractionalValueIsRejected() {
    XCTAssertThrowsError(try advertisingTimeout(1.5))
  }

  /// The case the platforms disagreed on. Swift bridges `Bool` to `NSNumber`, so `true` decoded as `1`
  /// and silently stopped the advertisement a millisecond later; Kotlin's `Boolean` is not a `Number`,
  /// so Android threw. `parseByteArray` guards the same hazard for byte values.
  func testABooleanIsRejectedRatherThanBridgedToOne() {
    XCTAssertThrowsError(try advertisingTimeout(true)) { error in
      XCTAssertTrue(
        (error as? GattArgumentError)?.message.contains("advertising timeout") == true,
        "\(error)"
      )
    }
    XCTAssertThrowsError(try advertisingTimeout(false))
  }

  func testANonNumberIsRejected() {
    XCTAssertThrowsError(try advertisingTimeout("2000"))
    XCTAssertThrowsError(try advertisingTimeout(["2000"]))
  }

  /// The two callers differ only in their bound, so the request timeout's own limit has to hold.
  func testTheRequestTimeoutStaysBelowTheAttTransactionTimeout() throws {
    let parse = { (value: Any?) in
      try parseTimeoutMs(
        value, field: "request timeout", bound: attTransactionTimeoutMs - 1,
        default: defaultRequestTimeoutMs, boundDescription: "\(attTransactionTimeoutMs - 1) ms"
      )
    }

    XCTAssertEqual(try parse(nil), defaultRequestTimeoutMs)
    XCTAssertEqual(try parse(Double(attTransactionTimeoutMs - 1)), attTransactionTimeoutMs - 1)
    XCTAssertThrowsError(try parse(Double(attTransactionTimeoutMs)))
  }

  // MARK: - Typed arrays

  /// An absent key is not an empty array: the caller has to be able to tell "no descriptors" from
  /// "descriptors I could not read", which is the whole point of this returning an optional.
  func testAnAbsentArrayIsNil() throws {
    let parsed: [String]? = try parseTypedArray(nil, field: "properties", elementDescription: "names")
    XCTAssertNil(parsed)
    let null: [String]? = try parseTypedArray(
      NSNull(), field: "properties", elementDescription: "names"
    )
    XCTAssertNil(null)
  }

  func testAWellTypedArrayComesBackWhole() throws {
    let parsed: [String]? = try parseTypedArray(
      ["read", "write"], field: "properties", elementDescription: "names"
    )
    XCTAssertEqual(parsed, ["read", "write"])
  }

  /// The failure this exists for. `["readable", 1] as? [String]` is `nil`, and every caller read `nil`
  /// as an absent key — so one bad element published an attribute with no permissions at all and
  /// `createServer` resolved. Android throws for the same input.
  func testOneBadElementIsReportedRatherThanDroppingTheArray() {
    XCTAssertThrowsError(
      try parseTypedArray(["readable", 1], field: "permissions", elementDescription: "names")
        as [String]?
    ) { error in
      XCTAssertTrue(
        "\(error)".contains("index 1"),
        "the message should name the element that could not be read, got \(error)"
      )
    }
  }

  func testAValueThatIsNotAnArrayIsRejected() {
    XCTAssertThrowsError(
      try parseTypedArray("read", field: "properties", elementDescription: "names") as [String]?
    )
  }

  /// The nested case: a service whose `characteristics` holds one non-object entry used to publish a
  /// service with no characteristics at all, which a central discovers as an empty service.
  func testAMalformedObjectEntryIsReported() {
    XCTAssertThrowsError(
      try parseTypedArray(
        [["uuid": "2a37"], 5], field: "characteristics", elementDescription: "characteristic objects"
      ) as [[String: Any]]?
    )
  }
}

/// The rules that guard a value or an argument's *shape* rather than its decoding.
final class ArgumentBoundsTests: XCTestCase {
  private func jsNumbers(_ values: [Double]) -> [Any] {
    values.map { $0 as Any }
  }

  // MARK: - Integer arguments

  /// The reason every integer argument is declared as a `Double` and narrowed here: expo-modules-core
  /// produces a declared `Int` with `Int(double.rounded())`, and `Int(_: Double)` traps on these two
  /// values — an uncatchable fatal error raised before any of this module's code runs. A `Double`
  /// parameter is what makes them reportable at all, so these cases are the whole point of the change
  /// and not merely another range check.
  func testRejectsNonFiniteIntegerArguments() {
    for value in [Double.nan, .infinity, -.infinity] {
      XCTAssertThrowsError(
        try parseIntArgument(
          value, field: "response request id", min: 0, max: Int(Int32.max), explanation: "x"
        ),
        "\(value) must be reported, never narrowed"
      )
    }
  }

  func testRejectsAFractionRatherThanRoundingIt() {
    // iOS rounded 1.5 to 2 and Android truncated it to 1, so the same call answered a different
    // request on each platform.
    XCTAssertThrowsError(
      try parseIntArgument(1.5, field: "response request id", min: 0, max: 10, explanation: "x")
    )
  }

  func testAcceptsTheBoundsAndRejectsOutsideThem() throws {
    XCTAssertEqual(
      try parseIntArgument(0, field: "response status", min: 0, max: 255, explanation: "x"), 0
    )
    XCTAssertEqual(
      try parseIntArgument(255, field: "response status", min: 0, max: 255, explanation: "x"), 255
    )
    XCTAssertThrowsError(
      try parseIntArgument(256, field: "response status", min: 0, max: 255, explanation: "x")
    )
    XCTAssertThrowsError(
      try parseIntArgument(-1, field: "response status", min: 0, max: 255, explanation: "x")
    )
  }

  // MARK: - The attribute value length limit

  /// "The maximum length of an attribute value shall be 512 octets" — Core Spec Vol 3, Part F, §3.2.9.
  /// Enforced on the values the *application* sets as well as the ones a central writes, which is what
  /// was missing: a longer attribute is readable only through a conformant Read Blob and can never be
  /// notified, since a notification is capped at `min(mtu - 3, 512)`.
  func testAcceptsAValueOfExactlyTheLimit() throws {
    let value = jsNumbers(Array(repeating: 0, count: maxAttributeValueLength))
    XCTAssertEqual(
      try parseAttributeValue(value, field: "characteristic")?.count, maxAttributeValueLength
    )
  }

  func testRejectsAValuePastTheLimit() {
    let value = jsNumbers(Array(repeating: 0, count: maxAttributeValueLength + 1))
    XCTAssertThrowsError(try parseAttributeValue(value, field: "characteristic")) { error in
      XCTAssertTrue(
        "\(error.localizedDescription)".contains("at most \(maxAttributeValueLength) octets"),
        "unexpected message: \(error.localizedDescription)"
      )
    }
  }

  func testTheTypedPathIsBoundedToo() {
    // `updateCharacteristicValue` arrives already decoded, so it reaches the length rule directly.
    XCTAssertThrowsError(
      try assertAttributeValueLength(
        Data(repeating: 0, count: maxAttributeValueLength + 1), field: "characteristic"
      )
    )
    XCTAssertNoThrow(
      try assertAttributeValueLength(
        Data(repeating: 0, count: maxAttributeValueLength), field: "characteristic"
      )
    )
  }

  // MARK: - Descriptor uniqueness

  /// Not a shadowing rule on this platform but a crash guard: `CBMutableCharacteristic.descriptors`
  /// raises `NSInternalInconsistencyException` for a second User Description or Presentation Format
  /// descriptor, and an Objective-C exception cannot be caught from Swift.
  func testRejectsARepeatedDescriptorUuid() {
    let characteristic = CBUUID(string: "2A37")
    XCTAssertThrowsError(
      try assertUniqueDescriptorUuids(
        [CBUUID(string: "2901"), CBUUID(string: "2901")], characteristic: characteristic
      )
    ) { error in
      XCTAssertTrue(
        "\(error.localizedDescription)".contains("Duplicate descriptor UUID"),
        "unexpected message: \(error.localizedDescription)"
      )
    }
  }

  /// `CBUUID` equality is width-insensitive, so the two spellings are one UUID and the rule sees them
  /// as the repeat they are.
  func testRecognisesTheTwoSpellingsOfOneDescriptorUuid() {
    XCTAssertThrowsError(
      try assertUniqueDescriptorUuids(
        [CBUUID(string: "2901"), CBUUID(string: "00002901-0000-1000-8000-00805F9B34FB")],
        characteristic: CBUUID(string: "2A37")
      )
    )
  }

  func testAcceptsDistinctDescriptorUuids() {
    XCTAssertNoThrow(
      try assertUniqueDescriptorUuids(
        [CBUUID(string: "2901"), CBUUID(string: "2904")], characteristic: CBUUID(string: "2A37")
      )
    )
  }

  // MARK: - The advertising budget

  /// CoreBluetooth accepts an advertisement that does not fit and reports success: the local name is
  /// truncated and service UUIDs are moved into the Apple-proprietary overflow area, where a non-Apple
  /// central filtering on one stops finding the peripheral. Android's stack refuses the same
  /// advertisement outright, and the shared documentation promises that rejection for both platforms —
  /// so the size is decided here, before the call that cannot report it.
  func testAnEmptyAdvertisementCostsOnlyTheFlags() {
    XCTAssertEqual(advertisementPayloadSize(localName: nil, serviceUuids: []), advertisingFlagsLength)
  }

  func testALocalNameCostsItsUtf8BytesPlusAHeader() {
    // Two octets of header, then the name itself — and the count is of UTF-8 bytes, not characters.
    XCTAssertEqual(advertisementPayloadSize(localName: "abc", serviceUuids: []), 3 + 2 + 3)
    XCTAssertEqual(advertisementPayloadSize(localName: "é", serviceUuids: []), 3 + 2 + 2)
  }

  /// UUIDs of one width share a single AD structure, so the header is paid once per width rather than
  /// once per UUID.
  func testServiceUuidsAreGroupedByWidth() {
    let short = [CBUUID(string: "180D"), CBUUID(string: "180F")]
    XCTAssertEqual(advertisementPayloadSize(localName: nil, serviceUuids: short), 3 + 2 + 4)

    let long = [CBUUID(string: "0000FE01-1234-1000-8000-00805F9B34FB")]
    XCTAssertEqual(advertisementPayloadSize(localName: nil, serviceUuids: long), 3 + 2 + 16)

    XCTAssertEqual(
      advertisementPayloadSize(localName: nil, serviceUuids: short + long),
      3 + (2 + 4) + (2 + 16)
    )
  }

  func testAnAdvertisementThatExactlyFitsIsAccepted() {
    // 3 flags + 2 header + 26 name = 31.
    let name = String(repeating: "a", count: 26)
    XCTAssertEqual(advertisementPayloadSize(localName: name, serviceUuids: []), 31)
    XCTAssertNoThrow(try assertAdvertisementFits(localName: name, serviceUuids: []))
  }

  func testAnAdvertisementOneByteOverIsRejected() {
    let name = String(repeating: "a", count: 27)
    XCTAssertThrowsError(try assertAdvertisementFits(localName: name, serviceUuids: [])) { error in
      XCTAssertTrue(
        "\(error.localizedDescription)".contains("at most \(maxAdvertisementPayloadLength)"),
        "unexpected message: \(error.localizedDescription)"
      )
    }
  }

  /// The case the contraction in `advertisedForm` exists for, and the reason the check runs on the
  /// contracted spelling: the same two UUIDs fit as 16-bit aliases and do not as their 128-bit
  /// expansions.
  func testTheWidthAUuidIsAdvertisedAtDecidesWhetherItFits() {
    let shortForms = [CBUUID(string: "180D"), CBUUID(string: "180F")]
    let expansions = shortForms.map { CBUUID(string: $0.normalizedString) }

    XCTAssertNoThrow(
      try assertAdvertisementFits(localName: "Peripheral", serviceUuids: shortForms)
    )
    XCTAssertThrowsError(
      try assertAdvertisementFits(localName: "Peripheral", serviceUuids: expansions)
    )
    // ...and contracting them is what the advertisement actually sends.
    XCTAssertNoThrow(
      try assertAdvertisementFits(
        localName: "Peripheral", serviceUuids: expansions.map { $0.advertisedForm }
      )
    )
  }
}
