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

/**
 * Anything outside 0..255 would be silently truncated by [Int.toByte].
 *
 * Callers must declare the argument as `List<Double>`, not `List<Int>`: expo-modules-core narrows a
 * declared `Int` with `asDouble().toInt()` before this runs, which turns `NaN` into `0` and truncates a
 * fraction — the whole-number test below can never fail on a value that has already been through it.
 */
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
 * Decodes a whole number out of an argument expo-modules-core delivered as a [Double].
 *
 * The counterpart of iOS's `parseIntArgument`, and declared the same way for the same reason: a
 * parameter declared as `Int` is produced by `asDouble().toInt()`, which turns `NaN` into `0` and
 * truncates a fraction — so an unchecked argument silently named request 0, or a different request than
 * iOS chose for the same call, with nothing reporting it. Android never crashed on it the way iOS did,
 * but a wrong answer to a real request is its own failure, and one rule across both platforms is what
 * keeps a call meaning one thing.
 */
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

/**
 * Decodes a value that will be *stored* as an attribute, which the specification bounds at
 * [MAX_ATTRIBUTE_VALUE_LENGTH] however it came to be set — a configured `value` and
 * `updateCharacteristicValue` alike, not only a write arriving from a central.
 */
internal fun parseAttributeValue(value: List<*>, field: String): ByteArray {
  val bytes = toByteArray(value, field)
  assertAttributeValueLength(bytes, field)
  return bytes
}

/**
 * The one bound, applied wherever an attribute value is set.
 *
 * [exceedsAttributeLength] already refused a *client* write that assembled past it and
 * [mtuErrorFor] already capped a notification at `min(mtu - 3, 512)`, so the module refused to carry a
 * value it would happily publish: an application could configure, or `updateCharacteristicValue` to, an
 * attribute longer than any attribute may be — one no central could be notified of, retrievable only by
 * a conformant Read Blob.
 */
internal fun assertAttributeValueLength(bytes: ByteArray, field: String) {
  if (exceedsAttributeLength(bytes.size)) {
    throw IllegalArgumentException(
      "Invalid $field value of ${bytes.size} bytes. An attribute value may hold at most " +
        "$MAX_ATTRIBUTE_VALUE_LENGTH octets (Core Spec Vol 3, Part F, §3.2.9), and a longer one " +
        "could never be notified or read in a single response."
    )
  }
}

/**
 * Reports an entry of a configuration array that is not an object, rather than skipping it.
 *
 * Every rule in this file throws for input it cannot honour, on the grounds that the native module is
 * reachable directly — except these list elements, which were dropped with `?: continue`. So one
 * malformed entry published a service with a characteristic missing, a characteristic with a descriptor
 * missing, or an advertisement with no manufacturer data, and `createServer` resolved as though the
 * whole configuration had been honoured. iOS reports the same input through `parseTypedArray`.
 */
internal fun asConfigMap(item: Any?, field: String, index: Int): Map<*, *> =
  item as? Map<*, *> ?: throw IllegalArgumentException(
    "Invalid $field entry at index $index. Expected an object, received $item."
  )

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
  return list.mapIndexed { index, item ->
    val map = asConfigMap(item, "manufacturerData", index)
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
      // Genuinely optional, unlike the entry itself: a characteristic with no `delegate` key answers
      // its own requests, which is the default.
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

  // The CCCD the module publishes itself counts: a configuration declaring one is already refused by
  // `parseDescriptorConfig`, and seeding the set makes that one rule rather than two.
  val declaredDescriptors = mutableSetOf<UUID>()
  if (characteristic.descriptors.isNotEmpty()) {
    declaredDescriptors += CCCD_UUID
  }
  for ((index, item) in ((map["descriptors"] as? List<*>) ?: emptyList<Any>()).withIndex()) {
    val descriptorMap = asConfigMap(item, "descriptors", index)
    val descriptor = parseDescriptorConfig(descriptorMap)
    // `addDescriptor` accepts a repeat and `getDescriptor` then returns the first, leaving the second
    // unreachable — the same shadowing a repeated characteristic UUID is refused for. iOS cannot even
    // accept it: `CBMutableCharacteristic.descriptors` raises an uncatchable Objective-C exception, so
    // the configuration Android quietly published terminated the application there.
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
