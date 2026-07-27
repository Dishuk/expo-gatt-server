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

/// The longest advertising duration `AdvertiseSettings.Builder.setTimeout` accepts, applied here too so
/// one configuration behaves the same either side. iOS emulates the limit with a timer of its own.
let maxAdvertisingTimeoutMs = 180_000

/// Decodes a millisecond duration out of an untyped configuration map, refusing anything that is not a
/// whole number in `0...bound`.
///
/// Re-checked natively rather than trusted from the TypeScript layer, on the same grounds as the
/// duplicate-UUID and byte-range checks: the native module is reachable directly. Android has always
/// re-checked these; iOS took the advertising timeout on trust, and Swift bridges `Bool` to `NSNumber`
/// where Kotlin's `Boolean` is not a `Number` — so `timeoutMs: true` threw on Android and, on iOS,
/// resolved and then silently stopped the advertisement one millisecond later.
internal func parseTimeoutMs(
  _ value: Any?, field: String, bound: Int, default fallback: Int, boundDescription: String
) throws -> Int {
  guard let value = value, !(value is NSNull) else { return fallback }
  guard !(value is Bool),
        let number = value as? NSNumber,
        let millis = Int(exactly: number.doubleValue),
        millis >= 0, millis <= bound else {
    throw GattArgumentError(
      message: "Invalid \(field) \(value). Expected an integer between 0 and \(boundDescription)."
    )
  }
  return millis
}

/// Decodes an optional array whose elements must all be of one type, reporting the first that is not.
///
/// Written because `value as? [Element]` is all-or-nothing: one element of the wrong type makes the
/// whole cast `nil`, and every caller here read `nil` as "the key was absent". So a single malformed
/// entry published a service with *no* characteristics, a characteristic with *no* descriptors, or —
/// worst — an attribute with no properties and no permissions, and `createServer` resolved as though
/// the configuration had been honoured. That is the silent-drop failure the TypeScript layer rejects a
/// misspelled property name to prevent, reappearing one layer down for a caller that reaches the native
/// module directly, which is the case this parsing exists for at all.
///
/// Android refuses the same input rather than dropping it, so throwing is also what keeps one
/// configuration meaning one thing on both platforms.
internal func parseTypedArray<Element>(
  _ value: Any?, field: String, elementDescription: String
) throws -> [Element]? {
  guard let value = value, !(value is NSNull) else { return nil }
  guard let elements = value as? [Any] else {
    throw GattArgumentError(
      message: "Invalid \(field). Expected an array of \(elementDescription)."
    )
  }
  var typed: [Element] = []
  typed.reserveCapacity(elements.count)
  for (index, element) in elements.enumerated() {
    guard let item = element as? Element else {
      throw GattArgumentError(
        message: "Invalid \(field) entry at index \(index). Expected \(elementDescription), " +
          "received \(element)."
      )
    }
    typed.append(item)
  }
  return typed
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
