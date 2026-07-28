import CoreBluetooth

/// Default ATT_MTU, in octets — Core Spec Vol 3, Part G, §5.2.1.
let defaultAttMtu = 23

/// Header octets in ATT_HANDLE_VALUE_NTF/IND PDU: 1-octet opcode + 2-octet handle (Core Spec Vol 3, Part F, §§3.4.7.1–3.4.7.2).
let attNotificationHeaderSize = 3

let defaultAttMtuPayload = defaultAttMtu - attNotificationHeaderSize

/// "The maximum length of an attribute value shall be 512 octets" — Core Spec Vol 3, Part F, §3.2.9.
let maxAttributeValueLength = 512

/// Checked separately: a queued write can exceed per-PDU limits; an unqueued write cannot.
func exceedsAttributeLength(_ size: Int) -> Bool {
  size > maxAttributeValueLength
}

/// Octets that fit in one notification: min of link MTU and max attribute value length.
func notificationPayloadLimit(for central: CBCentral) -> Int {
  min(central.maximumUpdateValueLength, maxAttributeValueLength)
}

/// ATT transaction timeout; failure requires new bearer (Core Spec Vol 3, Part F, §3.3.3). Module timeout must be below this.
let attTransactionTimeoutMs = 30_000

/// Request timeout for JavaScript handlers; below attTransactionTimeoutMs with margin for bearer recovery.
let defaultRequestTimeoutMs = 10_000

/// How long a registration round waits before timing out — generously above what healthy hardware needs.
let publicationTimeoutMs = 30_000

struct CharacteristicAddress: Hashable {
  let service: CBUUID
  let characteristic: CBUUID
}

struct CharacteristicDelegation: Equatable {
  var read = false
  var write = false

  static let none = CharacteristicDelegation()
}

/// The link budget for one central, expressed in the units the public API uses.
struct DeviceMtu {
  /// ATT_MTU in octets.
  let mtu: Int
  /// Octets that fit in one notification or indication: `ATT_MTU - 3`.
  let maxNotificationPayload: Int

  /// iOS reports only payload length; reconstructed to ATT_MTU. Capped by max attribute value length (Core Spec Vol 3, Part F, §3.2.9).
  init(maxNotificationPayload: Int) {
    self.mtu = maxNotificationPayload + attNotificationHeaderSize
    self.maxNotificationPayload = min(maxNotificationPayload, maxAttributeValueLength)
  }
}

/// Maps a status supplied by JavaScript onto the ATT error code CoreBluetooth transmits.
///
/// `CBATTError.Code` models 0x00 through 0x11 (Core Spec 5.4, Vol 3, Part F, Table 3.4), so those map
/// straight across and match what Android sends. The specification also defines 0x12, 0x13 and the
/// application and profile ranges, but `respond(to:withResult:)` accepts only a `CBATTError.Code`, so
/// anything unrepresentable becomes the generic "unlikely error" rather than being downgraded to
/// success.
func attErrorCode(for status: Int) -> CBATTError.Code {
  switch status {
  case 0x00: return .success
  case 0x01: return .invalidHandle
  case 0x02: return .readNotPermitted
  case 0x03: return .writeNotPermitted
  case 0x04: return .invalidPdu
  case 0x05: return .insufficientAuthentication
  case 0x06: return .requestNotSupported
  case 0x07: return .invalidOffset
  case 0x08: return .insufficientAuthorization
  case 0x09: return .prepareQueueFull
  case 0x0A: return .attributeNotFound
  case 0x0B: return .attributeNotLong
  case 0x0C: return .insufficientEncryptionKeySize
  case 0x0D: return .invalidAttributeValueLength
  case 0x0E: return .unlikelyError
  case 0x0F: return .insufficientEncryption
  case 0x10: return .unsupportedGroupType
  case 0x11: return .insufficientResources
  default: return .unlikelyError
  }
}

/// The Bluetooth Base UUID's trailing four groups — Core Spec Vol 3, Part B, §2.5.1.
private let bluetoothBaseUuidSuffix = "-0000-1000-8000-00805f9b34fb"

extension CBUUID {
  /// The lowercase 128-bit spelling, which is what `java.util.UUID.toString` produces on Android.
  ///
  /// `CBUUID.uuidString` is not that: it uppercases the 128-bit form and echoes a 16-bit or 32-bit UUID
  /// back in the short form it was constructed from. Normalising is repeated here as well as in
  /// JavaScript because these UUIDs come back out of CoreBluetooth rather than from the configuration.
  var normalizedString: String {
    let lower = uuidString.lowercased()
    guard lower.count < 36 else { return lower }
    return String(repeating: "0", count: 8 - lower.count) + lower + bluetoothBaseUuidSuffix
  }

  /// The shortest spelling of this UUID that means the same thing, for use in an advertisement.
  ///
  /// Unlike Android's encoder, `CBUUID` advertises whatever width it was constructed from: a 16-bit
  /// alias occupies two octets of the 31-byte budget, its 128-bit expansion sixteen. The shared
  /// TypeScript layer expands every UUID to 128 bits so both platforms address attributes by one
  /// spelling, which costs fourteen bytes per UUID here — enough to push a service UUID into the
  /// Apple-only scan-response overflow area, where a non-Apple central filtering on it stops finding the
  /// peripheral.
  ///
  /// Only exact members of the Bluetooth base range contract; a vendor UUID is returned unchanged.
  var advertisedForm: CBUUID {
    let lower = uuidString.lowercased()
    guard lower.count == 36, lower.hasSuffix(bluetoothBaseUuidSuffix) else { return self }
    let leading = String(lower.prefix(8))
    // A 16-bit alias is a 32-bit one whose top half is zero, and `CBUUID(string:)` accepts both widths.
    let short = leading.hasPrefix("0000") ? String(leading.suffix(4)) : leading
    return CBUUID(string: short)
  }
}

/// Maps `CBManagerState` onto the platform-neutral state union shared with Android.
func normalizedBluetoothState(_ state: CBManagerState) -> String {
  switch state {
  case .poweredOn: return "poweredOn"
  case .poweredOff: return "poweredOff"
  case .resetting: return "resetting"
  case .unsupported: return "unsupported"
  case .unauthorized: return "unauthorized"
  default: return "unknown"
  }
}
