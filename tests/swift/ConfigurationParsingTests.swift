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
