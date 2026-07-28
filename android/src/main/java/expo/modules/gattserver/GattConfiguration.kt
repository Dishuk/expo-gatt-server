package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import java.util.UUID

/** Parses configuration into Bluetooth framework objects. No Expo imports; testable on the host. */

private val SHORT_UUID = Regex("^[0-9a-fA-F]{4}$|^[0-9a-fA-F]{8}$")
private val LONG_UUID =
  Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** The Bluetooth Base UUID — Core Spec Vol 3, Part B, §2.5.1. */
private const val BLUETOOTH_BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** Parses and expands 16-bit or 32-bit UUID aliases to the Bluetooth Base UUID form. */
internal fun parseUuid(value: Any?, field: String): UUID {
  val text = value as? String
  if (text == null || (!SHORT_UUID.matches(text) && !LONG_UUID.matches(text))) {
    throw IllegalArgumentException(
      "Invalid $field UUID $value. Expected 4 hex digits (16-bit), 8 hex digits (32-bit) or the " +
        "hyphenated 8-4-4-4-12 form (128-bit)."
    )
  }
  val lower = text.lowercase()
  // Core Spec: alias = short_value * 2^96 + Bluetooth_Base_UUID. Expand via left-pad and base.
  val expanded = if (LONG_UUID.matches(lower)) lower else lower.padStart(8, '0') + BLUETOOTH_BASE_UUID_SUFFIX
  return UUID.fromString(expanded)
}

/**
 * Reads a whole number in [min]..[max] out of an untyped configuration value.
 *
 * expo-modules-core delivers every JavaScript number as a `Double`, so the integrality check is the
 * point: a fraction or a NaN would otherwise be truncated into a plausible-looking value. A `Boolean`
 * is not a `Number`, so it is refused here rather than bridged into 0 or 1.
 */
private inline fun requireWholeNumber(value: Any?, min: Int, max: Int, message: () -> String): Int {
  val number = value as? Number
  val whole = number?.toInt()
  if (number == null || whole == null ||
    number.toDouble() != whole.toDouble() || whole !in min..max
  ) {
    throw IllegalArgumentException(message())
  }
  return whole
}

/** Validates bytes 0..255. expo-modules-core passes Int as Double; this checks for NaN and fractions. */
internal fun toByteArray(value: List<*>, field: String): ByteArray {
  val bytes = ByteArray(value.size)
  value.forEachIndexed { index, element ->
    bytes[index] = requireWholeNumber(element, 0, 255) {
      "Invalid $field byte $element at index $index. " +
        "Every element must be an integer between 0 and 255."
    }.toByte()
  }
  return bytes
}

/** Parses a whole number from expo-modules-core Double, checking for NaN and fractions. */
internal fun parseIntArgument(
  value: Double, field: String, min: Int, max: Int, explanation: String
): Int {
  if (!value.isFinite() || value != Math.rint(value) || value < min || value > max) {
    throw IllegalArgumentException(
      "Invalid $field $value. $explanation It must be an integer between $min and $max."
    )
  }
  return value.toInt()
}

/** Parses and validates an attribute value against MAX_ATTRIBUTE_VALUE_LENGTH. */
internal fun parseAttributeValue(value: List<*>, field: String): ByteArray {
  val bytes = toByteArray(value, field)
  assertAttributeValueLength(bytes, field)
  return bytes
}

/** Core Spec Vol 3, Part F, §3.2.9: attribute values may not exceed 512 octets. */
internal fun assertAttributeValueLength(bytes: ByteArray, field: String) {
  if (exceedsAttributeLength(bytes.size)) {
    throw IllegalArgumentException(
      "Invalid $field value of ${bytes.size} bytes. An attribute value may hold at most " +
        "$MAX_ATTRIBUTE_VALUE_LENGTH octets (Core Spec Vol 3, Part F, §3.2.9), and a longer one " +
        "could never be notified or read in a single response."
    )
  }
}

/** Enforces list entries are objects; throws rather than skipping malformed entries. */
internal fun asConfigMap(item: Any?, field: String, index: Int): Map<*, *> =
  item as? Map<*, *> ?: throw IllegalArgumentException(
    "Invalid $field entry at index $index. Expected an object, received $item."
  )

/** Validates timeout before passing to AdvertiseSettings.Builder.setTimeout. */
internal fun parseAdvertisingTimeout(value: Any?): Int {
  if (value == null) return 0
  return requireWholeNumber(value, 0, MAX_ADVERTISING_TIMEOUT_MS) {
    "Invalid advertising timeout $value. Expected an integer between 0 and " +
      "$MAX_ADVERTISING_TIMEOUT_MS milliseconds, where 0 means no time limit."
  }
}

internal fun parseRequestTimeout(value: Any?): Int {
  if (value == null) return DEFAULT_REQUEST_TIMEOUT_MS
  return requireWholeNumber(value, 0, ATT_TRANSACTION_TIMEOUT_MS - 1) {
    "Invalid request timeout $value. Expected an integer between 0 and " +
      "${ATT_TRANSACTION_TIMEOUT_MS - 1} milliseconds — below the ATT transaction timeout of " +
      "$ATT_TRANSACTION_TIMEOUT_MS ms, past which the central has already given up — where 0 " +
      "disables the timeout."
  }
}

internal fun parseManufacturerData(value: Any?): List<ManufacturerData> {
  val list = value as? List<*> ?: return emptyList()
  return list.mapIndexed { index, item ->
    val map = asConfigMap(item, "manufacturerData", index)
    // Company ID is 16-bit; addManufacturerData only rejects negative values.
    val companyId = requireWholeNumber(map["companyId"], 0, 0xFFFF) {
      "Invalid manufacturer company id ${map["companyId"]}. A Bluetooth SIG Company " +
        "Identifier is a 16-bit value, so it must be an integer between 0 and 65535."
    }
    val data = (map["data"] as? List<*>) ?: emptyList<Any>()
    ManufacturerData(companyId, toByteArray(data, "manufacturer"))
  }
}

internal fun parseServiceData(value: Any?): List<ServiceData> {
  val list = value as? List<*> ?: return emptyList()
  return list.mapIndexed { index, item ->
    val map = asConfigMap(item, "serviceData", index)
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
    for ((index, item) in characteristics.withIndex()) {
      val charMap = asConfigMap(item, "characteristics", index)
      // Delegate is optional; absent means the characteristic answers its own requests.
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

/** Resolves delegation from address (if named) or fallback to characteristic UUID (if unnamed). */
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

/** Rejects duplicate service UUIDs; same UUID across different services is permitted by GATT. */
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
  for ((index, item) in characteristics.withIndex()) {
    val charMap = asConfigMap(item, "characteristics", index)
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

  // Track the CCCD the module publishes to reject duplicates via one rule.
  val declaredDescriptors = mutableSetOf<UUID>()
  if (characteristic.descriptors.isNotEmpty()) {
    declaredDescriptors += CCCD_UUID
  }
  for ((index, item) in ((map["descriptors"] as? List<*>) ?: emptyList<Any>()).withIndex()) {
    val descriptorMap = asConfigMap(item, "descriptors", index)
    val descriptor = parseDescriptorConfig(descriptorMap)
    // addDescriptor accepts duplicates but getDescriptor returns only the first (shadowing).
    if (!declaredDescriptors.add(descriptor.uuid)) {
      throw IllegalArgumentException(
        "Duplicate descriptor UUID ${descriptor.uuid} on characteristic $uuid. A characteristic may " +
          "declare each descriptor once. The same descriptor UUID on a different characteristic is fine."
      )
    }
    characteristic.addDescriptor(descriptor)
  }

  val initialValue = (map["value"] as? List<*>)?.let { parseAttributeValue(it, "characteristic") }
  if (initialValue != null) {
    @Suppress("DEPRECATION")
    characteristic.value = initialValue
  }

  return characteristic
}

/** CCCD write inherits strongest characteristic permission (Core Spec Vol 3, Part G, Table 3.10).
 *  Android enforces per-handle only; write must inherit to prevent cleartext subscription to encrypted characteristic.
 *  Read is always unrestricted; signed permissions do not apply to ordinary write requests. */
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

/** Rejects CCCD; the module publishes its own and shadows user-declared ones. */
internal fun parseDescriptorConfig(map: Map<*, *>): BluetoothGattDescriptor {
  val uuid = parseUuid(map["uuid"], "descriptor")
  if (uuid == CCCD_UUID) {
    throw IllegalArgumentException(
      "Descriptor $uuid is the Client Characteristic Configuration descriptor, which the module " +
        "publishes itself for every characteristic declaring \"notify\" or \"indicate\"."
    )
  }
  // Descriptor and Characteristic PERMISSION_* values are identical; one parser serves both.
  val permissions = (map["permissions"] as? List<*>)
    ?.let { parsePermissions(it) }
    ?: BluetoothGattDescriptor.PERMISSION_READ
  val descriptor = BluetoothGattDescriptor(uuid, permissions)
  @Suppress("DEPRECATION")
  descriptor.value = parseAttributeValue((map["value"] as? List<*>) ?: emptyList<Any>(), "descriptor")
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

/** Throws on unknown permission names; skipping would silently reduce protection. */
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
