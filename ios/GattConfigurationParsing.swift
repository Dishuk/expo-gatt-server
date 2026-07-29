import CoreBluetooth
import Foundation

/// Configuration parsing that does not need ExpoModulesCore.
/// Testable on the host; used to catch silent decoding failures when untyped dictionaries have Double values.
struct GattArgumentError: LocalizedError {
  let message: String
  var errorDescription: String? { message }
}

/// The longest advertising duration both platforms accept. iOS enforces with a timer.
let maxAdvertisingTimeoutMs = 180_000

/// Decodes a millisecond duration out of an untyped configuration map, refusing anything that is not a
/// whole number in `0...bound`.
///
/// Re-checked natively since the module is reachable directly. Swift bridges `Bool` to `NSNumber`,
/// so `timeoutMs: true` resolves but silently stops the advertisement on iOS.
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
/// Declared as `Double` and narrowed here instead of as `Int`, because `Int(_: Double)` **traps**
/// (uncatchable fatal error) on `NaN` or infinity. Android's converter returns `0` for `NaN` and
/// truncates fractions; iOS rounds them. Both platforms refuse it with the same message.
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

/// Decodes a value that will be stored as an attribute (configured or updated).
internal func parseAttributeValue(_ value: Any?, field: String) throws -> Data? {
  guard let bytes = try parseByteArray(value, field: field) else { return nil }
  try assertAttributeValueLength(bytes, field: field)
  return bytes
}

/// Validates the bound applied to all attribute values. Separate from parseAttributeValue because typed paths are pre-decoded.
internal func assertAttributeValueLength(_ bytes: Data, field: String) throws {
  guard bytes.count <= maxAttributeValueLength else {
    throw GattArgumentError(
      message: "Invalid \(field) value of \(bytes.count) bytes. An attribute value may hold at most " +
        "\(maxAttributeValueLength) octets (Core Spec Vol 3, Part F, §3.2.9), and a longer one could " +
        "never be notified or read in a single response."
    )
  }
}

/// Legacy advertising payload limit: 31 octets (Core Spec Vol 3, Part C, §11). Scan response has its own 31; CoreBluetooth does not expose it.
let maxAdvertisementPayloadLength = 31

/// The AD structure always added for a connectable advertisement: length, type 0x01, flags. CBPeripheralManager always connectable.
let advertisingFlagsLength = 3

/// Total octets in an advertisement's AD structures.
/// Service UUIDs grouped by width per Core Spec Vol 3, Part C, §11 and Supplement §1.1; widths from CBUUID.data.
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

/// Refuses an advertisement that does not fit. CoreBluetooth silently drops local name and service UUIDs
/// that do not fit; a non-Apple central filtering on a dropped service UUID will not discover the peripheral.
/// Android refuses with ADVERTISE_FAILED_DATA_TOO_LARGE; both platforms document this rejection.
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

/// Refuses duplicate descriptor UUIDs on a characteristic.
/// CBMutableCharacteristic.descriptors raises NSInternalInconsistencyException (uncatchable) for duplicates.
/// Any repeat is refused to mean the same thing on both platforms.
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
/// `value as? [Element]` is all-or-nothing: one bad element makes the whole cast nil, silently dropping
/// services, characteristics, or attributes. Android refuses the same input; both platforms need consistent behavior.
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

/// Converts an array of byte values delivered as `Double`.
/// Int(_: Double) **traps** on NaN or infinity; UInt8(exactly:) refuses them safely.
internal func parseBytes(_ value: [Double], field: String) throws -> Data {
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

/// Decodes an array of byte values out of an untyped configuration map.
/// Elements arrive as Double (expo-modules-core maps JS numbers to getDouble()), so as? [Int] always fails.
/// Booleans are refused (matching Android's as? Number). Returns nil only if key is absent, not for empty array.
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

// MARK: - Attribute configuration

/// `CBUUID(string:)` raises an uncatchable Objective-C exception for invalid formats; validate before calling CoreBluetooth.
func isValidUuid(_ string: String) -> Bool {
  func isHex(_ characters: Substring) -> Bool {
    !characters.isEmpty && characters.allSatisfy { $0.isASCII && $0.isHexDigit }
  }

  switch string.count {
  case 4, 8:
    return isHex(string[...])
  case 36:
    let groups = string.split(separator: "-", omittingEmptySubsequences: false)
    let expectedLengths = [8, 4, 4, 4, 12]
    guard groups.count == expectedLengths.count else { return false }
    return zip(groups, expectedLengths).allSatisfy { $0.count == $1 && isHex($0) }
  default:
    return false
  }
}

func validateUuid(_ string: String, field: String) throws {
  guard isValidUuid(string) else {
    throw GattArgumentError(
      message: "Invalid \(field) UUID \"\(string)\". Expected 4 hex digits (16-bit), " +
        "8 hex digits (32-bit) or the hyphenated 8-4-4-4-12 form (128-bit)."
    )
  }
}

func parseUuid(_ value: Any?, field: String) throws -> CBUUID {
  guard let string = value as? String else {
    throw GattArgumentError(message: "Missing or non-string \(field) UUID")
  }
  try validateUuid(string, field: field)
  return CBUUID(string: string)
}

/// Broadcast and extendedProperties are not allowed for local characteristics; refuse early.
func parseProperties(_ list: [String]?) throws -> CBCharacteristicProperties {
  var props: CBCharacteristicProperties = []
  for str in list ?? [] {
    switch str {
    case "read": props.insert(.read)
    case "write": props.insert(.write)
    case "writeNoResponse": props.insert(.writeWithoutResponse)
    case "notify": props.insert(.notify)
    case "indicate": props.insert(.indicate)
    case "signedWrite": props.insert(.authenticatedSignedWrites)
    case "broadcast", "extendedProperties":
      throw GattServerError.configurationUnsupported(
        option: "characteristic property \"\(str)\"",
        reason: "CoreBluetooth documents the matching CBCharacteristicProperties member as not " +
          "allowed for local characteristics. Declare it for Android only."
      )
    default:
      throw GattArgumentError(message: "Invalid characteristic property \"\(str)\".")
    }
  }
  return props
}

/// CBAttributePermissions has four members; Android's MITM/signed variants would be weaker approximations, so refuse them.
func parsePermissions(_ list: [String]?) throws -> CBAttributePermissions {
  var perms: CBAttributePermissions = []
  for str in list ?? [] {
    switch str {
    case "readable": perms.insert(.readable)
    case "writeable": perms.insert(.writeable)
    case "readEncrypted": perms.insert(.readEncryptionRequired)
    case "writeEncrypted": perms.insert(.writeEncryptionRequired)
    case "readEncryptedMitm", "writeEncryptedMitm", "writeSigned", "writeSignedMitm":
      throw GattServerError.configurationUnsupported(
        option: "permission \"\(str)\"",
        reason: "CBAttributePermissions offers only readable, writeable, " +
          "readEncryptionRequired and writeEncryptionRequired, and approximating this one would " +
          "publish a less protected attribute than was asked for. Use \"readEncrypted\" or " +
          "\"writeEncrypted\" for a portable encrypted attribute."
      )
    default:
      throw GattArgumentError(message: "Invalid permission \"\(str)\".")
    }
  }
  return perms
}

/// Raises subscription security to match value security. CBAttributePermissions don't guard the CCC descriptor;
/// without this an unpaired central could subscribe to an encrypted characteristic and receive values in cleartext.
/// The .notify/.indicate property sets the declaration bit (Core Spec Vol 3, Part G, Table 3.5).
internal func securedSubscription(
  _ properties: CBCharacteristicProperties,
  _ permissions: CBAttributePermissions
) -> CBCharacteristicProperties {
  guard permissions.contains(.readEncryptionRequired)
    || permissions.contains(.writeEncryptionRequired) else {
    return properties
  }

  var secured = properties
  if properties.contains(.notify) {
    secured.insert(.notifyEncryptionRequired)
  }
  if properties.contains(.indicate) {
    secured.insert(.indicateEncryptionRequired)
  }
  return secured
}
