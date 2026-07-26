import Foundation

/// Configuration parsing that does not need ExpoModulesCore.
///
/// Split out of `ExpoGattServerModule.swift` so `Package.swift` can compile it and `swift test` can
/// exercise it on the host, the way `GattConfiguration.kt` is split out on Android. Before the split
/// this logic was reachable by no test at all — the binding imports ExpoModulesCore, which has no host
/// build — and it was where two silent decoding bugs lived: `map["value"] as? [Int]` against a
/// dictionary whose numbers arrive as `Double`, which dropped every configured characteristic value
/// and published every descriptor empty.
struct GattArgumentError: LocalizedError {
  let message: String
  var errorDescription: String? { message }
}

/// Converts an already-typed array of byte values.
///
/// Used for the arguments expo-modules-core decodes for us — a declared `[Int]` parameter goes through
/// `DynamicIntType` and really is `[Int]` by the time it arrives.
internal func parseBytes(_ value: [Int], field: String) throws -> Data {
  var bytes: [UInt8] = []
  bytes.reserveCapacity(value.count)
  for (index, element) in value.enumerated() {
    guard let byte = UInt8(exactly: element) else {
      throw GattArgumentError(
        message: "Invalid \(field) byte \(element) at index \(index). " +
          "Every element must be an integer between 0 and 255."
      )
    }
    bytes.append(byte)
  }
  return Data(bytes)
}

/// Decodes an array of byte values out of an **untyped** configuration map.
///
/// Elements arrive as `Double`: expo-modules-core converts an untyped `[String: Any]` through
/// `DynamicRawType` → `JavaScriptValue.getAny()`, which maps every JS number to `getDouble()`. So
/// `as? [Int]` can never succeed on this path, however plainly it reads — which is exactly why the
/// failure was silent. Typed parameters are unaffected, which is why only the two `services` sites
/// were wrong.
///
/// Booleans are refused rather than bridged through `NSNumber`, matching Android's `as? Number`.
/// Returns `nil` only when the key is absent: `[]` is a configured, zero-length value, and the two
/// mean different things to a characteristic.
internal func parseByteArray(_ value: Any?, field: String) throws -> Data? {
  guard let value = value, !(value is NSNull) else { return nil }
  guard let elements = value as? [Any] else {
    throw GattArgumentError(
      message: "Invalid \(field) value. Expected an array of byte values."
    )
  }
  var bytes: [UInt8] = []
  bytes.reserveCapacity(elements.count)
  for (index, element) in elements.enumerated() {
    guard !(element is Bool),
          let number = element as? NSNumber,
          let byte = UInt8(exactly: number.doubleValue) else {
      throw GattArgumentError(
        message: "Invalid \(field) byte \(element) at index \(index). " +
          "Every element must be an integer between 0 and 255."
      )
    }
    bytes.append(byte)
  }
  return Data(bytes)
}
