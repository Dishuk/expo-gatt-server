package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import java.util.UUID

/**
 * Turns the configuration JavaScript sends into the framework objects the server publishes.
 *
 * Deliberately free of any Expo import, so the meaning of a configuration is separable from the way
 * Expo happens to deliver it — [ExpoGattServerModule] is the binding, and this is the decision. That
 * also lets every rule here be exercised on the host: a mistake in an attribute's permissions or in a
 * byte conversion is silent at runtime and visible only to a peer.
 */

private val SHORT_UUID = Regex("^[0-9a-fA-F]{4}$|^[0-9a-fA-F]{8}$")
private val LONG_UUID =
  Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** The Bluetooth Base UUID — Core Spec Vol 3, Part B, §2.5.1. */
private const val BLUETOOTH_BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/**
 * Parses a configured UUID, expanding a 16-bit or 32-bit alias onto the Bluetooth Base UUID.
 *
 * Repeated here as well as in JavaScript because the native module is reachable directly, which is the
 * same reason the duplicate-UUID and byte-range checks below are. It is also the check the two platforms
 * most needed to agree on: `CBUUID` accepts all three widths, while `java.util.UUID.fromString` requires
 * the 8-4-4-4-12 form — so `'180D'` was accepted on iOS and threw here, the exact divergence the shared
 * `normalizeUuid` exists to remove, left unrepeated on the platform that needs it.
 *
 * `UUID.fromString` is also lenient about group widths: it accepts `"180d-0-1000-8000-00805f9b34fb"`
 * and silently zero-pads. The pattern is matched first so a malformed spelling is reported rather than
 * quietly turned into a different UUID.
 */
internal fun parseUuid(value: Any?, field: String): UUID {
  val text = value as? String
  if (text == null || (!SHORT_UUID.matches(text) && !LONG_UUID.matches(text))) {
    throw IllegalArgumentException(
      "Invalid $field UUID $value. Expected 4 hex digits (16-bit), 8 hex digits (32-bit) or the " +
        "hyphenated 8-4-4-4-12 form (128-bit)."
    )
  }
  val lower = text.lowercase()
  // The specification defines an alias as `short_value * 2^96 + Bluetooth_Base_UUID`, which lands the
  // value in the leading 32 bits — so the expansion is a left-pad to eight hex digits plus the base.
  val expanded = if (LONG_UUID.matches(lower)) lower else lower.padStart(8, '0') + BLUETOOTH_BASE_UUID_SUFFIX
  return UUID.fromString(expanded)
}

/** Anything outside 0..255 would be silently truncated by [Int.toByte]. */
internal fun toByteArray(value: List<*>, field: String): ByteArray {
  val bytes = ByteArray(value.size)
  value.forEachIndexed { index, element ->
    val number = element as? Number
    val intValue = number?.toInt()
    if (number == null || intValue == null ||
      number.toDouble() != intValue.toDouble() || intValue !in 0..255
    ) {
      throw IllegalArgumentException(
        "Invalid $field byte $element at index $index. " +
          "Every element must be an integer between 0 and 255."
      )
    }
    bytes[index] = intValue.toByte()
  }
  return bytes
}

/**
 * Checked before `AdvertiseSettings.Builder.setTimeout` sees it, whose own message ("timeoutMillis
 * invalid") does not say which option was wrong.
 */
internal fun parseAdvertisingTimeout(value: Any?): Int {
  if (value == null) return 0
  val number = value as? Number
  val millis = number?.toInt()
  if (number == null || millis == null || number.toDouble() != millis.toDouble() ||
    millis < 0 || millis > MAX_ADVERTISING_TIMEOUT_MS
  ) {
    throw IllegalArgumentException(
      "Invalid advertising timeout $value. Expected an integer between 0 and " +
        "$MAX_ADVERTISING_TIMEOUT_MS milliseconds, where 0 means no time limit."
    )
  }
  return millis
}

internal fun parseRequestTimeout(value: Any?): Int {
  if (value == null) return DEFAULT_REQUEST_TIMEOUT_MS
  val number = value as? Number
  val millis = number?.toInt()
  if (number == null || millis == null || number.toDouble() != millis.toDouble() ||
    millis < 0 || millis >= ATT_TRANSACTION_TIMEOUT_MS
  ) {
    throw IllegalArgumentException(
      "Invalid request timeout $value. Expected an integer between 0 and " +
        "${ATT_TRANSACTION_TIMEOUT_MS - 1} milliseconds — below the ATT transaction timeout of " +
        "$ATT_TRANSACTION_TIMEOUT_MS ms, past which the central has already given up — where 0 " +
        "disables the timeout."
    )
  }
  return millis
}

internal fun parseManufacturerData(value: Any?): List<ManufacturerData> {
  val list = value as? List<*> ?: return emptyList()
  return list.mapNotNull { item ->
    val map = item as? Map<*, *> ?: return@mapNotNull null
    val number = map["companyId"] as? Number
    val companyId = number?.toInt()
    // 16-bit field, so a wider value is not transmissible; `addManufacturerData` only rejects
    // negative ids.
    if (number == null || companyId == null || number.toDouble() != companyId.toDouble() ||
      companyId !in 0..0xFFFF
    ) {
      throw IllegalArgumentException(
        "Invalid manufacturer company id ${map["companyId"]}. A Bluetooth SIG Company " +
          "Identifier is a 16-bit value, so it must be an integer between 0 and 65535."
      )
    }
    val data = (map["data"] as? List<*>) ?: emptyList<Any>()
    ManufacturerData(companyId, toByteArray(data, "manufacturer"))
  }
}

internal fun parseServiceData(value: Any?): List<ServiceData> {
  val list = value as? List<*> ?: return emptyList()
  return list.mapNotNull { item ->
    val map = item as? Map<*, *> ?: return@mapNotNull null
    val uuid = parseUuid(map["uuid"], "service data")
    val data = (map["data"] as? List<*>) ?: emptyList<Any>()
    ServiceData(uuid, toByteArray(data, "service data"))
  }
}

/** Absent or empty `delegate` configuration produces no entry, so the default stays fully automatic. */
internal fun parseDelegations(
  services: List<Map<String, Any?>>
): Map<CharacteristicAddress, CharacteristicDelegation> {
  val result = mutableMapOf<CharacteristicAddress, CharacteristicDelegation>()
  for (service in services) {
    val serviceUuid = parseUuid(service["uuid"], "service")
    val characteristics = (service["characteristics"] as? List<*>) ?: emptyList<Any>()
    for (item in characteristics) {
      val charMap = item as? Map<*, *> ?: continue
      val delegate = charMap["delegate"] as? Map<*, *> ?: continue
      val delegation = CharacteristicDelegation(
        read = delegate["read"] as? Boolean ?: false,
        write = delegate["write"] as? Boolean ?: false,
      )
      if (delegation == CharacteristicDelegation.none) continue
      result[CharacteristicAddress(serviceUuid, parseUuid(charMap["uuid"], "characteristic"))] =
        delegation
    }
  }
  return result
}

/**
 * The delegation configured for an attribute, given the [address] the published database named it by —
 * `null` when it could not be named at all — and the two maps [parseDelegations] feeds.
 *
 * [byCharacteristic] holds only the characteristic UUIDs that appear exactly once across every service,
 * and exists purely for the unnameable case. It is consulted **only** then. Consulting it whenever
 * [byAddress] merely had no entry for a perfectly nameable attribute let a delegation configured on one
 * service reach a same-named characteristic in another — which GATT permits and this module accepts, so
 * an attribute that never opted in had its Write Without Response silently dropped and its reads handed
 * to a listener that was never going to answer them. iOS resolves the exact address with no fallback.
 */
internal fun resolveDelegation(
  address: CharacteristicAddress?,
  characteristicUuid: UUID,
  byAddress: Map<CharacteristicAddress, CharacteristicDelegation>,
  byCharacteristic: Map<UUID, CharacteristicDelegation>,
): CharacteristicDelegation {
  if (address == null) {
    return byCharacteristic[characteristicUuid] ?: CharacteristicDelegation.none
  }
  return byAddress[address] ?: CharacteristicDelegation.none
}

/**
 * `BluetoothGattServer.getService` and `BluetoothGattService.getCharacteristic` both return the first
 * match, so a repeated UUID leaves one attribute unreachable and the other addressed by both
 * spellings. Rejected in JavaScript too; repeated here because the native module is reachable
 * directly. The same characteristic UUID in *different* services stays legal, as GATT permits.
 */
internal fun parseServices(services: List<Map<String, Any?>>): List<BluetoothGattService> {
  val seen = mutableSetOf<UUID>()
  return services.map { config ->
    val service = parseServiceConfig(config)
    if (!seen.add(service.uuid)) {
      throw IllegalArgumentException(
        "Duplicate service UUID ${service.uuid}. Give each service its own UUID, or merge their " +
          "characteristics into one service."
      )
    }
    service
  }
}

internal fun parseServiceConfig(map: Map<String, Any?>): BluetoothGattService {
  val uuid = parseUuid(map["uuid"], "service")
  val service = BluetoothGattService(uuid, parseServiceType(map["type"] as? String))

  val characteristics = (map["characteristics"] as? List<*>) ?: emptyList<Any>()
  val seen = mutableSetOf<UUID>()
  for (item in characteristics) {
    val charMap = item as? Map<*, *> ?: continue
    val characteristic = parseCharacteristicConfig(charMap)
    if (!seen.add(characteristic.uuid)) {
      throw IllegalArgumentException(
        "Duplicate characteristic UUID ${characteristic.uuid} in service $uuid. The same " +
          "characteristic UUID in a different service is fine."
      )
    }
    service.addCharacteristic(characteristic)
  }
  return service
}

internal fun parseServiceType(name: String?): Int = when (name) {
  null, "primary" -> BluetoothGattService.SERVICE_TYPE_PRIMARY
  "secondary" -> BluetoothGattService.SERVICE_TYPE_SECONDARY
  else -> throw IllegalArgumentException(
    "Invalid service type \"$name\". Expected \"primary\" or \"secondary\"."
  )
}

internal fun parseCharacteristicConfig(map: Map<*, *>): BluetoothGattCharacteristic {
  val uuid = parseUuid(map["uuid"], "characteristic")
  val properties = parseProperties(map["properties"] as? List<*>)
  val permissions = parsePermissions(map["permissions"] as? List<*>)
  val characteristic = BluetoothGattCharacteristic(uuid, properties, permissions)

  if (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
    characteristic.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, cccdPermissions(permissions)))
  }

  for (item in (map["descriptors"] as? List<*>) ?: emptyList<Any>()) {
    val descriptorMap = item as? Map<*, *> ?: continue
    characteristic.addDescriptor(parseDescriptorConfig(descriptorMap))
  }

  val initialValue = (map["value"] as? List<*>)?.let { toByteArray(it, "characteristic") }
  if (initialValue != null) {
    @Suppress("DEPRECATION")
    characteristic.value = initialValue
  }

  return characteristic
}

/**
 * The link security a client must reach to configure the CCCD the module publishes for [permissions].
 *
 * Android enforces permissions per attribute handle with no inheritance — `gatts_write_attr_perm_check`
 * resolves the permission from the written handle alone, and `GATTS_HandleValueNotification` checks
 * nothing at all — so a fixed `PERMISSION_WRITE` here would let an unbonded client subscribe to a
 * characteristic the app marked encrypted-only and receive every later value in cleartext, while the
 * direct read it would have tried first was correctly refused. Enabling a subscription is what puts the
 * value on the air, so the write inherits the strongest level declared in *either* direction — which is
 * the server's to decide, the CCCD being "Writable with authentication and authorization defined by a
 * higher layer specification or is implementation specific" (Core Spec Vol 3, Part G, Table 3.10).
 *
 * The read inherits nothing, because the same table fixes it as "Readable with no authentication or
 * authorization" — and the module answers a CCCD read from its own per-client map rather than from the
 * shared descriptor, so refusing one would cost a conformant client its descriptor discovery to hide a
 * value it is entitled to and that says nothing about anyone else. iOS cannot protect it either:
 * CoreBluetooth owns the CCCD and applies encryption only to its write.
 *
 * The signed permissions deliberately contribute nothing: they constrain the form of an inbound write
 * PDU, and a CCCD is configured with an ordinary write request rather than a signed write command.
 */
internal fun cccdPermissions(permissions: Int): Int {
  val readEncryptedMitm = permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0
  val readEncrypted = permissions and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0
  val writeEncryptedMitm = permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0
  val writeEncrypted = permissions and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0

  val write = when {
    readEncryptedMitm || writeEncryptedMitm -> BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM
    readEncrypted || writeEncrypted -> BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
    else -> BluetoothGattDescriptor.PERMISSION_WRITE
  }
  return BluetoothGattDescriptor.PERMISSION_READ or write
}

/**
 * The CCCD is rejected here as well as in JavaScript, because a second instance would be published
 * alongside the module's own and shadow the per-client subscription tracking that answers it.
 */
internal fun parseDescriptorConfig(map: Map<*, *>): BluetoothGattDescriptor {
  val uuid = parseUuid(map["uuid"], "descriptor")
  if (uuid == CCCD_UUID) {
    throw IllegalArgumentException(
      "Descriptor $uuid is the Client Characteristic Configuration descriptor, which the module " +
        "publishes itself for every characteristic declaring \"notify\" or \"indicate\"."
    )
  }
  // `BluetoothGattDescriptor.PERMISSION_*` and `BluetoothGattCharacteristic.PERMISSION_*` are
  // declared with identical values, so one parser serves both attribute kinds.
  val permissions = (map["permissions"] as? List<*>)
    ?.let { parsePermissions(it) }
    ?: BluetoothGattDescriptor.PERMISSION_READ
  val descriptor = BluetoothGattDescriptor(uuid, permissions)
  @Suppress("DEPRECATION")
  descriptor.value = toByteArray((map["value"] as? List<*>) ?: emptyList<Any>(), "descriptor")
  return descriptor
}

internal fun parseProperties(list: List<*>?): Int {
  var props = 0
  list?.forEach {
    props = props or when (it as? String) {
      "read" -> BluetoothGattCharacteristic.PROPERTY_READ
      "write" -> BluetoothGattCharacteristic.PROPERTY_WRITE
      "writeNoResponse" -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
      "notify" -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
      "indicate" -> BluetoothGattCharacteristic.PROPERTY_INDICATE
      "broadcast" -> BluetoothGattCharacteristic.PROPERTY_BROADCAST
      "signedWrite" -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
      "extendedProperties" -> BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
      else -> throw IllegalArgumentException("Invalid characteristic property \"$it\".")
    }
  }
  return props
}

/**
 * An unrecognised name throws rather than being skipped: dropping a permission silently publishes an
 * attribute less protected than the configuration asked for.
 */
internal fun parsePermissions(list: List<*>?): Int {
  var perms = 0
  list?.forEach {
    perms = perms or when (it as? String) {
      "readable" -> BluetoothGattCharacteristic.PERMISSION_READ
      "writeable" -> BluetoothGattCharacteristic.PERMISSION_WRITE
      "readEncrypted" -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
      "readEncryptedMitm" -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
      "writeEncrypted" -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
      "writeEncryptedMitm" -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
      "writeSigned" -> BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED
      "writeSignedMitm" -> BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM
      else -> throw IllegalArgumentException("Invalid permission \"$it\".")
    }
  }
  return perms
}
