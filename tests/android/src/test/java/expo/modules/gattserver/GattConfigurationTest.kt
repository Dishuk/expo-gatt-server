package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * What a configuration turns into, checked against the real `android.bluetooth` attribute classes
 * rather than stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GattConfigurationTest {
  private val serviceUuid = "0000180d-0000-1000-8000-00805f9b34fb"
  private val characteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb"

  private fun characteristic(
    uuid: String = characteristicUuid,
    properties: List<String> = listOf("read"),
    permissions: List<String> = listOf("readable"),
    value: List<Int>? = null,
    descriptors: List<Map<String, Any?>>? = null,
    delegate: Map<String, Any?>? = null,
  ): Map<String, Any?> = buildMap {
    put("uuid", uuid)
    put("properties", properties)
    put("permissions", permissions)
    if (value != null) put("value", value)
    if (descriptors != null) put("descriptors", descriptors)
    if (delegate != null) put("delegate", delegate)
  }

  private fun service(
    uuid: String = serviceUuid,
    type: String? = null,
    characteristics: List<Map<String, Any?>> = listOf(characteristic()),
  ): Map<String, Any?> = buildMap {
    put("uuid", uuid)
    if (type != null) put("type", type)
    put("characteristics", characteristics)
  }

  private fun BluetoothGattService.only(): BluetoothGattCharacteristic = characteristics.single()

  private fun BluetoothGattCharacteristic.cccd(): BluetoothGattDescriptor? =
    descriptors.firstOrNull { it.uuid == CCCD_UUID }

  // MARK: - The constants restated in AttOperations

  /** `AttOperations.kt` restates these constants so it can run on a plain JVM. */
  @Test
  fun `the restated property bits match the framework`() {
    assertEquals(BluetoothGattCharacteristic.PROPERTY_NOTIFY, PROPERTY_NOTIFY)
    assertEquals(BluetoothGattCharacteristic.PROPERTY_INDICATE, PROPERTY_INDICATE)
  }

  // MARK: - Subscribing must not bypass the value's own security

  /** A CCCD published with plain PERMISSION_WRITE would let an unbonded client subscribe to a characteristic whose direct read is encrypted. */
  @Test
  fun `subscribing to an encrypted characteristic requires an encrypted link`() {
    val parsed = parseCharacteristicConfig(
      characteristic(properties = listOf("read", "notify"), permissions = listOf("readEncrypted"))
    )

    val cccd = parsed.cccd()
    assertNotNull("a notify characteristic must publish a CCCD", cccd)
    assertTrue(
      "the CCCD write must require encryption",
      cccd!!.permissions and BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED != 0
    )
    assertEquals(
      "an unprotected write must not remain available",
      0,
      cccd.permissions and BluetoothGattDescriptor.PERMISSION_WRITE
    )
  }

  @Test
  fun `an mitm characteristic requires an authenticated link to subscribe`() {
    val parsed = parseCharacteristicConfig(
      characteristic(
        properties = listOf("read", "indicate"), permissions = listOf("readEncryptedMitm")
      )
    )

    val cccd = parsed.cccd()!!
    assertTrue(
      cccd.permissions and BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM != 0
    )
    assertEquals(0, cccd.permissions and BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
    assertEquals(0, cccd.permissions and BluetoothGattDescriptor.PERMISSION_WRITE)
  }

  /** The strongest level declared in either direction wins, since either would otherwise be bypassable. */
  @Test
  fun `the strongest declared level wins`() {
    assertEquals(
      BluetoothGattDescriptor.PERMISSION_READ or
        BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM,
      cccdPermissions(
        BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
          BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
      )
    )
  }

  /** A signed permission constrains the PDU form, not encryption; a CCCD write must not be mistaken for one. */
  @Test
  fun `signed permissions do not raise the subscription requirement`() {
    val permissions = cccdPermissions(BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED)

    assertTrue(permissions and BluetoothGattDescriptor.PERMISSION_WRITE != 0)
    assertEquals(0, permissions and BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED)
  }

  /** Table 3.10 fixes the CCCD as "Readable with no authentication or authorization". */
  @Test
  fun `the configuration stays readable at every security level`() {
    val levels = listOf(
      0,
      BluetoothGattCharacteristic.PERMISSION_READ,
      BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
      BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
      BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
    )

    for (level in levels) {
      assertTrue(
        "level $level",
        cccdPermissions(level) and BluetoothGattDescriptor.PERMISSION_READ != 0
      )
    }
  }

  @Test
  fun `an unprotected characteristic keeps an unprotected subscription`() {
    val parsed = parseCharacteristicConfig(
      characteristic(properties = listOf("read", "notify"), permissions = listOf("readable"))
    )

    assertTrue(parsed.cccd()!!.permissions and BluetoothGattDescriptor.PERMISSION_WRITE != 0)
  }

  // MARK: - The configuration descriptor itself

  @Test
  fun `a characteristic that cannot transmit publishes no configuration descriptor`() {
    val parsed = parseCharacteristicConfig(
      characteristic(properties = listOf("read", "write"))
    )

    assertNull(parsed.cccd())
  }

  @Test
  fun `notify and indicate each publish a configuration descriptor`() {
    for (property in listOf("notify", "indicate")) {
      val parsed = parseCharacteristicConfig(characteristic(properties = listOf(property)))

      assertNotNull(property, parsed.cccd())
    }
  }

  /** A second instance would be published alongside the module's own and shadow the per-client subscription tracking. */
  @Test
  fun `declaring the configuration descriptor is rejected`() {
    val error = runCatching {
      parseCharacteristicConfig(
        characteristic(
          properties = listOf("notify"),
          descriptors = listOf(mapOf("uuid" to CCCD_UUID.toString(), "value" to listOf(0, 0)))
        )
      )
    }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error!!.message!!, error.message!!.contains("Client Characteristic Configuration"))
  }

  @Test
  fun `a declared descriptor is published alongside the configuration descriptor`() {
    val userDescription = "00002901-0000-1000-8000-00805f9b34fb"
    val parsed = parseCharacteristicConfig(
      characteristic(
        properties = listOf("notify"),
        descriptors = listOf(mapOf("uuid" to userDescription, "value" to listOf(0x41, 0x42)))
      )
    )

    assertNotNull(parsed.cccd())
    val declared = parsed.descriptors.single { it.uuid.toString() == userDescription }
    @Suppress("DEPRECATION")
    assertArrayEquals(byteArrayOf(0x41, 0x42), declared.value)
  }

  @Test
  fun `a descriptor defaults to readable`() {
    val parsed = parseDescriptorConfig(
      mapOf("uuid" to "00002901-0000-1000-8000-00805f9b34fb", "value" to listOf<Int>())
    )

    assertEquals(BluetoothGattDescriptor.PERMISSION_READ, parsed.permissions)
  }

  // MARK: - Properties and permissions

  @Test
  fun `each property name sets its own declaration bit`() {
    val expected = mapOf(
      "read" to BluetoothGattCharacteristic.PROPERTY_READ,
      "write" to BluetoothGattCharacteristic.PROPERTY_WRITE,
      "writeNoResponse" to BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
      "notify" to BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      "indicate" to BluetoothGattCharacteristic.PROPERTY_INDICATE,
      "broadcast" to BluetoothGattCharacteristic.PROPERTY_BROADCAST,
      "signedWrite" to BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE,
      "extendedProperties" to BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS,
    )

    for ((name, bit) in expected) {
      assertEquals(name, bit, parseProperties(listOf(name)))
    }
  }

  @Test
  fun `each permission name sets its own bit`() {
    val expected = mapOf(
      "readable" to BluetoothGattCharacteristic.PERMISSION_READ,
      "writeable" to BluetoothGattCharacteristic.PERMISSION_WRITE,
      "readEncrypted" to BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
      "readEncryptedMitm" to BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM,
      "writeEncrypted" to BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
      "writeEncryptedMitm" to BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM,
      "writeSigned" to BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED,
      "writeSignedMitm" to BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM,
    )

    for ((name, bit) in expected) {
      assertEquals(name, bit, parsePermissions(listOf(name)))
    }
  }

  @Test
  fun `names combine rather than replace`() {
    assertEquals(
      BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      parseProperties(listOf("read", "notify"))
    )
  }

  /** Skipping an unrecognised name would silently publish a weaker attribute than requested. */
  @Test
  fun `an unrecognised permission is rejected rather than dropped`() {
    val error = runCatching { parsePermissions(listOf("readable", "writable")) }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error!!.message!!, error.message!!.contains("writable"))
  }

  @Test
  fun `an unrecognised property is rejected rather than dropped`() {
    assertTrue(
      runCatching { parseProperties(listOf("notifiy")) }.exceptionOrNull()
        is IllegalArgumentException
    )
  }

  @Test
  fun `an absent list declares nothing`() {
    assertEquals(0, parseProperties(null))
    assertEquals(0, parsePermissions(null))
  }

  // MARK: - Services

  @Test
  fun `a service defaults to primary`() {
    assertEquals(BluetoothGattService.SERVICE_TYPE_PRIMARY, parseServiceType(null))
    assertEquals(BluetoothGattService.SERVICE_TYPE_PRIMARY, parseServiceType("primary"))
    assertEquals(BluetoothGattService.SERVICE_TYPE_SECONDARY, parseServiceType("secondary"))
  }

  @Test
  fun `an unrecognised service type is rejected`() {
    assertTrue(
      runCatching { parseServiceType("tertiary") }.exceptionOrNull() is IllegalArgumentException
    )
  }

  /** `getService` returns the first match, so a repeated UUID would leave one service unreachable. */
  @Test
  fun `two services with the same uuid are rejected`() {
    val error = runCatching { parseServices(listOf(service(), service())) }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error!!.message!!, error.message!!.contains("Duplicate service UUID"))
  }

  @Test
  fun `two characteristics with the same uuid in one service are rejected`() {
    val error = runCatching {
      parseServices(listOf(service(characteristics = listOf(characteristic(), characteristic()))))
    }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error!!.message!!, error.message!!.contains("Duplicate characteristic UUID"))
  }

  /** GATT permits this, and the pair of UUIDs still names exactly one attribute. */
  @Test
  fun `the same characteristic uuid in two services is accepted`() {
    val parsed = parseServices(
      listOf(service(), service(uuid = "0000181a-0000-1000-8000-00805f9b34fb"))
    )

    assertEquals(2, parsed.size)
    assertEquals(characteristicUuid, parsed[0].only().uuid.toString())
    assertEquals(characteristicUuid, parsed[1].only().uuid.toString())
  }

  @Test
  fun `a configured value is carried onto the characteristic`() {
    val parsed = parseServices(
      listOf(service(characteristics = listOf(characteristic(value = listOf(0, 60)))))
    )

    @Suppress("DEPRECATION")
    assertArrayEquals(byteArrayOf(0, 60), parsed.single().only().value)
  }

  /** `[]` declares a present but zero-length attribute, which is distinct from an absent value. */
  @Test
  fun `an empty configured value is a value`() {
    val parsed = parseServices(
      listOf(service(characteristics = listOf(characteristic(value = listOf()))))
    )

    @Suppress("DEPRECATION")
    assertArrayEquals(ByteArray(0), parsed.single().only().value)
  }

  @Test
  fun `an absent value leaves the characteristic without one`() {
    val parsed = parseServices(listOf(service()))

    @Suppress("DEPRECATION")
    assertNull(parsed.single().only().value)
  }

  // MARK: - Delegation

  @Test
  fun `an absent delegate block leaves the characteristic automatic`() {
    assertTrue(parseDelegations(listOf(service())).isEmpty())
  }

  @Test
  fun `delegation is recorded against the service and characteristic pair`() {
    val services = listOf(
      service(characteristics = listOf(characteristic(delegate = mapOf("read" to true))))
    )

    val delegations = parseDelegations(services)

    val address = CharacteristicAddress(
      java.util.UUID.fromString(serviceUuid), java.util.UUID.fromString(characteristicUuid)
    )
    assertEquals(CharacteristicDelegation(read = true, write = false), delegations[address])
  }

  /** Keying by characteristic alone would make one instance's delegation answer for another's. */
  @Test
  fun `the same characteristic in two services delegates independently`() {
    val other = "0000181a-0000-1000-8000-00805f9b34fb"
    val services = listOf(
      service(characteristics = listOf(characteristic(delegate = mapOf("read" to true)))),
      service(uuid = other, characteristics = listOf(characteristic())),
    )

    val delegations = parseDelegations(services)

    assertEquals(1, delegations.size)
    assertNull(
      delegations[
        CharacteristicAddress(
          java.util.UUID.fromString(other), java.util.UUID.fromString(characteristicUuid)
        )
      ]
    )
  }

  // MARK: - Bytes and bounds

  @Test
  fun `bytes outside a single octet are rejected rather than truncated`() {
    for (value in listOf(256, -1, 1000)) {
      val error = runCatching { toByteArray(listOf(value), "test") }.exceptionOrNull()
      assertTrue("$value", error is IllegalArgumentException)
    }
  }

  @Test
  fun `a fractional byte is rejected`() {
    assertTrue(
      runCatching { toByteArray(listOf(1.5), "test") }.exceptionOrNull()
        is IllegalArgumentException
    )
  }

  /** The high half of the range must survive the signed `Byte` it is stored in. */
  @Test
  fun `the whole octet range round trips`() {
    val parsed = toByteArray((0..255).toList(), "test")

    assertEquals(256, parsed.size)
    for (value in 0..255) {
      assertEquals(value, parsed[value].toInt() and 0xFF)
    }
  }

  @Test
  fun `the error names the offending index`() {
    val error = runCatching { toByteArray(listOf(1, 2, 999), "notification") }.exceptionOrNull()

    assertTrue(error!!.message!!, error.message!!.contains("index 2"))
    assertTrue(error.message!!, error.message!!.contains("notification"))
  }

  // MARK: - Timeouts

  /** A module timeout at or above the ATT transaction timeout could never answer before the peer retires the bearer, so the bound is exclusive. */
  @Test
  fun `a request timeout at the att transaction timeout is rejected`() {
    assertTrue(
      runCatching { parseRequestTimeout(ATT_TRANSACTION_TIMEOUT_MS) }.exceptionOrNull()
        is IllegalArgumentException
    )
    assertEquals(
      ATT_TRANSACTION_TIMEOUT_MS - 1, parseRequestTimeout(ATT_TRANSACTION_TIMEOUT_MS - 1)
    )
  }

  @Test
  fun `zero disables the request timeout and absent means the default`() {
    assertEquals(0, parseRequestTimeout(0))
    assertEquals(DEFAULT_REQUEST_TIMEOUT_MS, parseRequestTimeout(null))
  }

  @Test
  fun `a negative request timeout is rejected`() {
    assertTrue(
      runCatching { parseRequestTimeout(-1) }.exceptionOrNull() is IllegalArgumentException
    )
  }

  /** "May not exceed 180000 milliseconds" — the bound `AdvertiseSettings.Builder` enforces. */
  @Test
  fun `the advertising timeout is bounded at the platform limit`() {
    assertEquals(MAX_ADVERTISING_TIMEOUT_MS, parseAdvertisingTimeout(MAX_ADVERTISING_TIMEOUT_MS))
    assertTrue(
      runCatching { parseAdvertisingTimeout(MAX_ADVERTISING_TIMEOUT_MS + 1) }.exceptionOrNull()
        is IllegalArgumentException
    )
    assertEquals(0, parseAdvertisingTimeout(null))
  }

  // MARK: - Advertising data

  @Test
  fun `a company id wider than sixteen bits is rejected`() {
    val entry = listOf(mapOf("companyId" to 0x10000, "data" to listOf(1)))

    assertTrue(
      runCatching { parseManufacturerData(entry) }.exceptionOrNull() is IllegalArgumentException
    )
  }

  @Test
  fun `the widest valid company id is accepted`() {
    val parsed = parseManufacturerData(listOf(mapOf("companyId" to 0xFFFF, "data" to listOf(7))))

    assertEquals(0xFFFF, parsed.single().companyId)
    assertArrayEquals(byteArrayOf(7), parsed.single().data)
  }

  @Test
  fun `a negative company id is rejected`() {
    assertTrue(
      runCatching { parseManufacturerData(listOf(mapOf("companyId" to -1, "data" to listOf<Int>()))) }
        .exceptionOrNull() is IllegalArgumentException
    )
  }

  // MARK: - Resolving a delegation

  /** Keying by service+characteristic is only half of it — the lookup has to honour that key too. */
  private val serviceA = java.util.UUID.fromString(serviceUuid)
  private val serviceB = java.util.UUID.fromString("0000181a-0000-1000-8000-00805f9b34fb")
  private val charX = java.util.UUID.fromString(characteristicUuid)

  /** Mirrors what `GattServerManager.setDelegations` builds from a parsed map. */
  private fun byCharacteristic(
    byAddress: Map<CharacteristicAddress, CharacteristicDelegation>
  ): Map<java.util.UUID, CharacteristicDelegation> {
    val occurrences = byAddress.keys.groupingBy { it.characteristic }.eachCount()
    return byAddress.filterKeys { occurrences[it.characteristic] == 1 }
      .mapKeys { it.key.characteristic }
  }

  @Test
  fun `a delegation configured on one service does not reach the same characteristic in another`() {
    val delegated = CharacteristicDelegation(read = true, write = true)
    val byAddress = mapOf(CharacteristicAddress(serviceA, charX) to delegated)

    val resolved = resolveDelegation(
      CharacteristicAddress(serviceB, charX), charX, byAddress, byCharacteristic(byAddress)
    )

    assertEquals(CharacteristicDelegation.none, resolved)
  }

  @Test
  fun `the characteristic that did opt in still resolves to its delegation`() {
    val delegated = CharacteristicDelegation(read = true, write = true)
    val byAddress = mapOf(CharacteristicAddress(serviceA, charX) to delegated)

    val resolved = resolveDelegation(
      CharacteristicAddress(serviceA, charX), charX, byAddress, byCharacteristic(byAddress)
    )

    assertEquals(delegated, resolved)
  }

  /** `addressOf` could not name the owning service here, so the unambiguous single occurrence is the best available answer. */
  @Test
  fun `an unnameable attribute falls back to the unambiguous characteristic`() {
    val delegated = CharacteristicDelegation(read = true)
    val byAddress = mapOf(CharacteristicAddress(serviceA, charX) to delegated)

    val resolved = resolveDelegation(null, charX, byAddress, byCharacteristic(byAddress))

    assertEquals(delegated, resolved)
  }

  /** Two services delegating the same characteristic UUID leave nothing unambiguous to fall back to. */
  @Test
  fun `an unnameable attribute with an ambiguous characteristic stays automatic`() {
    val byAddress = mapOf(
      CharacteristicAddress(serviceA, charX) to CharacteristicDelegation(read = true),
      CharacteristicAddress(serviceB, charX) to CharacteristicDelegation(write = true),
    )

    val resolved = resolveDelegation(null, charX, byAddress, byCharacteristic(byAddress))

    assertEquals(CharacteristicDelegation.none, resolved)
  }

  @Test
  fun `an empty configuration leaves everything automatic`() {
    assertEquals(
      CharacteristicDelegation.none,
      resolveDelegation(CharacteristicAddress(serviceA, charX), charX, emptyMap(), emptyMap())
    )
  }
}

/**
 * Parsing a configured UUID: 16-bit, 32-bit, and 128-bit spellings must all resolve, unlike
 * `java.util.UUID.fromString` alone.
 */
class UuidParsingTest {
  private val heartRate = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

  @Test
  fun `a 16 bit alias expands onto the bluetooth base uuid`() {
    assertEquals(heartRate, parseUuid("180d", "service"))
    assertEquals(heartRate, parseUuid("180D", "service"))
  }

  @Test
  fun `a 32 bit alias expands onto the bluetooth base uuid`() {
    assertEquals(heartRate, parseUuid("0000180d", "service"))
  }

  @Test
  fun `the 128 bit form is taken as written`() {
    assertEquals(heartRate, parseUuid("0000180D-0000-1000-8000-00805F9B34FB", "service"))
  }

  @Test
  fun `a malformed uuid is rejected rather than parsed`() {
    for (bad in listOf("", "180", "180dd", "not-a-uuid", "0000180d-0000-1000-8000")) {
      assertThrows(IllegalArgumentException::class.java) { parseUuid(bad, "service") }
    }
  }

  /** `UUID.fromString` accepts short groups and silently zero-pads them. */
  @Test
  fun `a uuid with short groups is rejected rather than silently padded`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseUuid("180d-0-1000-8000-00805f9b34fb", "service")
    }
  }

  @Test
  fun `a non string is rejected with a message naming the field`() {
    val error = assertThrows(IllegalArgumentException::class.java) { parseUuid(42, "descriptor") }
    assertTrue(error.message!!, error.message!!.contains("descriptor"))
  }

  @Test
  fun `a missing uuid is rejected rather than throwing a cast error`() {
    assertThrows(IllegalArgumentException::class.java) { parseUuid(null, "service") }
  }
}

/**
 * Rules that bound an argument or value, and rules for whether a malformed entry is reported or
 * dropped.
 *
 * Robolectric, because the value rules are asserted through the real attribute classes the server
 * publishes rather than through the helpers in isolation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArgumentBoundsTest {
  private val serviceUuid = "0000180d-0000-1000-8000-00805f9b34fb"
  private val characteristicUuid = "00002a37-0000-1000-8000-00805f9b34fb"

  private fun characteristic(
    properties: List<String> = listOf("read"),
    value: List<Int>? = null,
    descriptors: List<Any?>? = null,
  ): Map<String, Any?> = buildMap {
    put("uuid", characteristicUuid)
    put("properties", properties)
    put("permissions", listOf("readable"))
    if (value != null) put("value", value)
    if (descriptors != null) put("descriptors", descriptors)
  }

  // MARK: - Integer arguments

  /** Declared as `Double` and narrowed here, rather than narrowed by expo-modules-core's `asDouble().toInt()`, which turns NaN into 0. */
  @Test
  fun `a non finite integer argument is rejected rather than becoming zero`() {
    for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertThrows(IllegalArgumentException::class.java) {
        parseIntArgument(value, "response request id", 0, Int.MAX_VALUE, "x")
      }
    }
  }

  @Test
  fun `a fractional integer argument is rejected rather than truncated`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseIntArgument(1.5, "response request id", 0, 10, "x")
    }
  }

  @Test
  fun `an integer argument accepts its bounds and refuses the values outside them`() {
    assertEquals(0, parseIntArgument(0.0, "response status", 0, 255, "x"))
    assertEquals(255, parseIntArgument(255.0, "response status", 0, 255, "x"))
    assertThrows(IllegalArgumentException::class.java) {
      parseIntArgument(256.0, "response status", 0, 255, "x")
    }
    assertThrows(IllegalArgumentException::class.java) {
      parseIntArgument(-1.0, "response status", 0, 255, "x")
    }
  }

  // MARK: - The attribute value length limit

  /** "The maximum length of an attribute value shall be 512 octets" — Core Spec Vol 3, Part F, §3.2.9. */
  @Test
  fun `a configured characteristic value of exactly the limit is accepted`() {
    val value = List(MAX_ATTRIBUTE_VALUE_LENGTH) { 0 }
    val parsed = parseCharacteristicConfig(characteristic(value = value))
    @Suppress("DEPRECATION")
    assertEquals(MAX_ATTRIBUTE_VALUE_LENGTH, parsed.value!!.size)
  }

  @Test
  fun `a configured characteristic value past the limit is rejected`() {
    val error = assertThrows(IllegalArgumentException::class.java) {
      parseCharacteristicConfig(characteristic(value = List(MAX_ATTRIBUTE_VALUE_LENGTH + 1) { 0 }))
    }
    assertTrue(error.message!!, error.message!!.contains("at most $MAX_ATTRIBUTE_VALUE_LENGTH octets"))
  }

  @Test
  fun `a configured descriptor value past the limit is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseCharacteristicConfig(
        characteristic(
          descriptors = listOf(
            mapOf("uuid" to "2901", "value" to List(MAX_ATTRIBUTE_VALUE_LENGTH + 1) { 0 })
          )
        )
      )
    }
  }

  // MARK: - Descriptor uniqueness

  /** `addDescriptor` accepts a repeat and `getDescriptor` returns the first, leaving the second unreachable. */
  @Test
  fun `a repeated descriptor uuid on one characteristic is rejected`() {
    val descriptor = mapOf("uuid" to "2901", "value" to listOf(0x41))
    val error = assertThrows(IllegalArgumentException::class.java) {
      parseCharacteristicConfig(characteristic(descriptors = listOf(descriptor, descriptor)))
    }
    assertTrue(error.message!!, error.message!!.contains("Duplicate descriptor UUID"))
  }

  @Test
  fun `the two spellings of one descriptor uuid are recognised as a repeat`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseCharacteristicConfig(
        characteristic(
          descriptors = listOf(
            mapOf("uuid" to "2901", "value" to listOf(0x41)),
            mapOf("uuid" to "00002901-0000-1000-8000-00805F9B34FB", "value" to listOf(0x42)),
          )
        )
      )
    }
  }

  @Test
  fun `distinct descriptor uuids on one characteristic are accepted`() {
    val parsed = parseCharacteristicConfig(
      characteristic(
        descriptors = listOf(
          mapOf("uuid" to "2901", "value" to listOf(0x41)),
          mapOf("uuid" to "2904", "value" to listOf(0x42)),
        )
      )
    )
    assertEquals(2, parsed.descriptors.size)
  }

  // MARK: - Malformed configuration entries

  /** A malformed list element must be reported, not silently dropped from the service. */
  @Test
  fun `a malformed characteristic entry is reported rather than dropped`() {
    val error = assertThrows(IllegalArgumentException::class.java) {
      parseServiceConfig(
        mapOf("uuid" to serviceUuid, "characteristics" to listOf(characteristic(), "2a38"))
      )
    }
    assertTrue(error.message!!, error.message!!.contains("index 1"))
  }

  @Test
  fun `a malformed manufacturer data entry is reported rather than dropped`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseManufacturerData(listOf(mapOf("companyId" to 0x004C, "data" to listOf(1)), 0x004C))
    }
  }

  @Test
  fun `a malformed characteristic entry is reported when delegations are parsed`() {
    assertThrows(IllegalArgumentException::class.java) {
      parseDelegations(listOf(mapOf("uuid" to serviceUuid, "characteristics" to listOf("2a37"))))
    }
  }
}
