import CoreBluetooth
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

/// Decodes a whole number out of an argument expo-modules-core delivered as a `Double`.
///
/// Every integer argument this module takes is *declared* as a `Double` and narrowed here, rather than
/// declared as an `Int` and narrowed by expo-modules-core. Its `DynamicNumberType` converts with
/// `Int(double.rounded())`, and `Int(_: Double)` **traps** on `NaN` or an infinity — an uncatchable
/// fatal error that takes the process with it, raised before this module's code runs at all, so no
/// amount of checking inside a function body could have prevented it. A `Double` parameter converts
/// without narrowing, which puts the decision here where it can be reported.
///
/// It also removes a silent divergence: Android's converter turns the same `NaN` into `0` and truncates
/// a fraction where iOS rounds it, so an unchecked argument named a *different* request on each
/// platform. Both platforms now refuse it with the same message.
internal func parseIntArgument(
  _ value: Double, field: String, min: Int, max: Int, explanation: String
) throws -> Int {
  guard value.isFinite, value == value.rounded(), value >= Double(min), value <= Double(max) else {
    throw GattArgumentError(
      message: "Invalid \(field) \(value). \(explanation) It must be an integer between " +
        "\(min) and \(max)."
    )
  }
  return Int(value)
}

/// Decodes a value that will be *stored* as an attribute, which the specification bounds at
/// `maxAttributeValueLength` however it came to be set — a configured `value` and
/// `updateCharacteristicValue` alike, not only a write arriving from a central.
internal func parseAttributeValue(_ value: Any?, field: String) throws -> Data? {
  guard let bytes = try parseByteArray(value, field: field) else { return nil }
  try assertAttributeValueLength(bytes, field: field)
  return bytes
}

/// The one bound, applied wherever an attribute value is set. Kept separate from
/// `parseAttributeValue` because the typed `[Int]` path — `updateCharacteristicValue` — has already
/// been decoded by expo-modules-core and needs only the length rule.
internal func assertAttributeValueLength(_ bytes: Data, field: String) throws {
  guard bytes.count <= maxAttributeValueLength else {
    throw GattArgumentError(
      message: "Invalid \(field) value of \(bytes.count) bytes. An attribute value may hold at most " +
        "\(maxAttributeValueLength) octets (Core Spec Vol 3, Part F, §3.2.9), and a longer one could " +
        "never be notified or read in a single response."
    )
  }
}

/// The advertising payload a legacy advertisement can carry — Core Spec Vol 3, Part C, §11: an
/// `AdvData` field is 31 octets. The scan response has its own 31, which CoreBluetooth does not let a
/// peripheral populate.
let maxAdvertisementPayloadLength = 31

/// The AD structure the controller adds for a connectable advertisement: length, type `0x01`, and one
/// octet of flags. A `CBPeripheralManager` advertisement is always connectable, so the room is always
/// spent — the same three octets Android's `AdvertiseHelper` reserves before it decides an advertisement
/// is too large.
let advertisingFlagsLength = 3

/// The octets an advertisement's AD structures would occupy.
///
/// Each structure costs a length octet and a type octet plus its payload; service UUIDs are grouped by
/// width into one structure per width present (Core Spec Vol 3, Part C, §11 and the Supplement's §1.1).
/// The widths come from `CBUUID.data`, which is what `advertisedForm` has already contracted where it
/// could.
func advertisementPayloadSize(localName: String?, serviceUuids: [CBUUID]) -> Int {
  var size = advertisingFlagsLength
  if let localName = localName, !localName.isEmpty {
    size += 2 + localName.utf8.count
  }
  var octetsByWidth: [Int: Int] = [:]
  for uuid in serviceUuids {
    let width = uuid.data.count
    octetsByWidth[width, default: 0] += width
  }
  for (_, octets) in octetsByWidth {
    size += 2 + octets
  }
  return size
}

/// Refuses an advertisement that could not go on the air as asked.
///
/// CoreBluetooth reports no failure for one that does not fit: `startAdvertising` succeeds,
/// `peripheralManagerDidStartAdvertising` reports `error == nil`, and the parts that did not fit are
/// silently dropped — a truncated local name, and service UUIDs relocated into the Apple-proprietary
/// scan-response overflow area, which only Apple hardware reads. A non-Apple central filtering on a
/// service UUID then never discovers the peripheral, and nothing anywhere says why. Android's stack
/// refuses the same advertisement with `ADVERTISE_FAILED_DATA_TOO_LARGE`, which the module reports as
/// `ERR_ADVERTISE`, and `docs/api.md` documents that rejection for both platforms.
///
/// Checked before the call rather than after, because afterwards there is nothing left to check: the
/// advertisement CoreBluetooth put on the air is not readable from the API.
func assertAdvertisementFits(localName: String?, serviceUuids: [CBUUID]) throws {
  let size = advertisementPayloadSize(localName: localName, serviceUuids: serviceUuids)
  guard size > maxAdvertisementPayloadLength else { return }
  let nameCost = (localName?.isEmpty == false) ? 2 + (localName?.utf8.count ?? 0) : 0
  throw GattArgumentError(
    message: "The advertisement needs \(size) bytes and an advertisement carries at most " +
      "\(maxAdvertisementPayloadLength), of which \(advertisingFlagsLength) are the connectable " +
      "flags. Shorten localName, or advertise fewer service UUIDs — a 16-bit UUID costs 2 bytes " +
      "where a 128-bit one costs 16." +
      (nameCost > 0
        ? " localName is \(nameCost) of those bytes: CoreBluetooth advertises it, where Android puts "
          + "it in the scan response, so this configuration advertises on Android and not here."
        : "")
  )
}

/// Refuses a characteristic that declares one descriptor UUID more than once.
///
/// Lives here rather than beside the assignment it guards so that it can be exercised on the host:
/// `ExpoGattServerModule.swift` imports ExpoModulesCore and has no host build, and this is a rule that
/// must not be discovered to be wrong on a device. `CBMutableCharacteristic.descriptors` raises
/// `NSInternalInconsistencyException` for a second User Description or Presentation Format descriptor,
/// which Swift cannot catch — so the configuration that merely shadowed an attribute on Android
/// terminated the application here.
///
/// Any repeat is refused, not only the two CoreBluetooth names, so one configuration means the same
/// thing on both platforms.
internal func assertUniqueDescriptorUuids(_ uuids: [CBUUID], characteristic: CBUUID) throws {
  var seen: Set<CBUUID> = []
  for uuid in uuids {
    guard seen.insert(uuid).inserted else {
      throw GattArgumentError(
        message: "Duplicate descriptor UUID \(uuid.normalizedString) on characteristic " +
          "\(characteristic.normalizedString). A characteristic may declare each descriptor once. " +
          "The same descriptor UUID on a different characteristic is fine."
      )
    }
  }
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
