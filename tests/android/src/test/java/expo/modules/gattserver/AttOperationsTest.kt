package expo.modules.gattserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * ATT arithmetic (write assembly, response rebasing, CCCD, MTU bounds). Plain JUnit, no Android
 * framework dependency.
 *
 * Several cases mirror `tests/swift/WriteAssemblyTests.swift` and `ResponseRebasingTests.swift`
 * to keep both platforms producing the same bytes for the same input.
 */
class AttOperationsTest {
  private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

  // MARK: - Assembling a prepared write

  /** Only the queued-write path reaches [spliceAt]; an unqueued write replaces the value outright elsewhere. */
  @Test
  fun `a part at offset zero keeps the tail`() {
    assertArrayEquals(bytes(9, 9, 3, 4, 5), spliceAt(bytes(1, 2, 3, 4, 5), 0, bytes(9, 9)))
  }

  @Test
  fun `a part at the end appends`() {
    assertArrayEquals(bytes(1, 2, 3, 4), spliceAt(bytes(1, 2), 2, bytes(3, 4)))
  }

  @Test
  fun `a part may extend past the current end`() {
    assertArrayEquals(bytes(1, 8, 9, 10), spliceAt(bytes(1, 2), 1, bytes(8, 9, 10)))
  }

  /** "Invalid Offset" — Core Spec Vol 3, Part F, §3.4.6.3. */
  @Test
  fun `an offset past the end is rejected`() {
    assertNull(spliceAt(bytes(1, 2), 3, bytes(9)))
    assertNull(spliceAt(ByteArray(0), 1, bytes(9)))
  }

  @Test
  fun `an empty part leaves the value alone`() {
    assertArrayEquals(bytes(1, 2, 3), spliceAt(bytes(1, 2, 3), 1, ByteArray(0)))
  }

  /** "The maximum length of an attribute value shall be 512 octets" — Core Spec Vol 3, Part F, §3.2.9. */
  @Test
  fun `an assembled value at the limit is accepted and one past it is not`() {
    assertFalse(exceedsAttributeLength(512))
    assertTrue(exceedsAttributeLength(513))
  }

  @Test
  fun `parts within range can still assemble past the limit`() {
    val assembled = spliceAt(ByteArray(500), 500, ByteArray(500))
    assertNotNull(assembled)
    assertTrue(exceedsAttributeLength(assembled!!.size))
  }

  /** At the largest permitted ATT_MTU, one write request carries 514 octets — two more than an attribute may hold. */
  @Test
  fun `one write request at the largest permitted mtu can exceed the limit`() {
    val largestWriteValue = 517 - 3
    assertEquals(514, largestWriteValue)
    assertTrue(exceedsAttributeLength(largestWriteValue))
  }

  /** Folds a multi-part long write; stopping short of the attribute's end must leave the remainder. */
  @Test
  fun `a multi part long write stopping short keeps the remainder`() {
    var value = bytes(1, 2, 3, 4, 5, 6, 7, 8)
    value = spliceAt(value, 0, bytes(10, 11))!!
    value = spliceAt(value, 2, bytes(12, 13))!!

    assertArrayEquals(bytes(10, 11, 12, 13, 5, 6, 7, 8), value)
  }

  /** Repeats of the same handle are executed in the order received rather than replacing one another. */
  @Test
  fun `repeated parts at the same offset apply in order`() {
    var value = ByteArray(0)
    value = spliceAt(value, 0, bytes(1, 1))!!
    value = spliceAt(value, 0, bytes(2, 2))!!

    assertArrayEquals(bytes(2, 2), value)
  }

  // MARK: - Rebasing a response

  private fun rebase(value: ByteArray, supplied: Int, requested: Int, isRead: Boolean = true) =
    rebasedResponseValue(value, isRead, supplied, requested, requestId = 7)

  /** The native module is reachable directly, so a negative offset must be rejected here too, not just in the JS layer. */
  @Test
  fun `a negative supplied offset is rejected rather than trimming the response`() {
    val error = assertThrows(GattServerException::class.java) {
      rebase(bytes(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), supplied = -4, requested = 0)
    }
    assertEquals("ERR_RESPONSE_OFFSET", error.code)
  }

  @Test
  fun `the whole value at offset zero answers a plain read`() {
    assertArrayEquals(bytes(1, 2, 3), rebase(bytes(1, 2, 3), supplied = 0, requested = 0))
  }

  @Test
  fun `a pre sliced value at the requested offset is passed through`() {
    assertArrayEquals(bytes(3, 4, 5), rebase(bytes(3, 4, 5), supplied = 2, requested = 2))
  }

  @Test
  fun `a partially sliced value is rebased the remaining distance`() {
    assertArrayEquals(bytes(4, 5), rebase(bytes(2, 3, 4, 5), supplied = 1, requested = 3))
  }

  /** Both documented spellings have to produce the same PDU, or the contract is a coin flip. */
  @Test
  fun `both documented spellings agree`() {
    val whole = bytes(10, 20, 30, 40, 50)

    val fromZero = rebase(whole, supplied = 0, requested = 3)
    val preSliced = rebase(whole.copyOfRange(3, whole.size), supplied = 3, requested = 3)

    assertArrayEquals(fromZero, preSliced)
    assertArrayEquals(bytes(40, 50), fromZero)
  }

  @Test
  fun `nothing left at the requested offset answers empty`() {
    assertArrayEquals(ByteArray(0), rebase(bytes(1, 2), supplied = 0, requested = 2))
    assertArrayEquals(ByteArray(0), rebase(bytes(1, 2), supplied = 0, requested = 99))
  }

  /** Supplying the value from after the requested offset would silently drop the missing octets, so it is refused. */
  @Test
  fun `supplying from past the requested offset is rejected`() {
    val error = runCatching { rebase(bytes(1, 2, 3), supplied = 4, requested = 2) }
      .exceptionOrNull() as? GattServerException

    assertNotNull(error)
    assertEquals("ERR_RESPONSE_OFFSET", error!!.code)
    assertTrue(error.message!!, error.message!!.contains("offset 2"))
  }

  /** A write response carries no value, so nothing is rebased and nothing is rejected. */
  @Test
  fun `write responses are passed through unchanged`() {
    assertArrayEquals(
      bytes(1, 2, 3), rebase(bytes(1, 2, 3), supplied = 0, requested = 2, isRead = false)
    )
    assertArrayEquals(
      bytes(1, 2, 3), rebase(bytes(1, 2, 3), supplied = 9, requested = 0, isRead = false)
    )
  }

  // MARK: - Client Characteristic Configuration

  /** Two octets, little endian — Core Spec Vol 3, Part G, §3.3.3.3, Table 3.11. */
  @Test
  fun `the configuration is read little endian`() {
    assertEquals(0x0000, cccdBits(bytes(0x00, 0x00)))
    assertEquals(0x0001, cccdBits(bytes(0x01, 0x00)))
    assertEquals(0x0002, cccdBits(bytes(0x02, 0x00)))
    assertEquals(0x0003, cccdBits(bytes(0x03, 0x00)))
    // The high octet is the second one, not the first.
    assertEquals(0x0100, cccdBits(bytes(0x00, 0x01)))
  }

  @Test
  fun `the configuration is written little endian`() {
    assertArrayEquals(bytes(0x01, 0x00), cccdValue(0x0001))
    assertArrayEquals(bytes(0x02, 0x00), cccdValue(0x0002))
    assertArrayEquals(bytes(0x00, 0x01), cccdValue(0x0100))
  }

  @Test
  fun `the configuration value is always the two octets the specification fixes`() {
    for (bits in listOf(0x0000, 0x0001, 0xFFFF)) {
      assertEquals(CCCD_VALUE_LENGTH, cccdValue(bits).size)
    }
  }

  /** Pins the two bits to the values the specification assigns, not just to themselves — Core Spec Vol 3, Part G, §3.3.3.3: bit 0 is Notification, bit 1 is Indication. */
  @Test
  fun `the configuration bits are the ones the specification assigns`() {
    assertEquals(0x0001, CCCD_NOTIFY_BIT)
    assertEquals(0x0002, CCCD_INDICATE_BIT)
    // Stated against literals too, so the pairing cannot be inverted without this failing.
    assertTrue(cccdEnables(0x0001, confirm = false))
    assertFalse(cccdEnables(0x0001, confirm = true))
    assertTrue(cccdEnables(0x0002, confirm = true))
    assertFalse(cccdEnables(0x0002, confirm = false))
  }

  @Test
  fun `either bit counts as subscribed and neither does not`() {
    assertFalse(cccdSubscribed(0x0000))
    assertTrue(cccdSubscribed(CCCD_NOTIFY_BIT))
    assertTrue(cccdSubscribed(CCCD_INDICATE_BIT))
    assertTrue(cccdSubscribed(CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT))
  }

  /** Reserved bits are not a subscription; only the two the specification defines are. */
  @Test
  fun `a reserved bit alone is not a subscription`() {
    assertFalse(cccdSubscribed(0x0004))
    assertFalse(cccdSubscribed(0xFF00))
  }

  @Test
  fun `a client that enabled both may be sent either`() {
    val both = CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT

    assertTrue(cccdEnables(both, confirm = false))
    assertTrue(cccdEnables(both, confirm = true))
  }

  @Test
  fun `a client that enabled nothing may be sent nothing`() {
    assertFalse(cccdEnables(0x0000, confirm = false))
    assertFalse(cccdEnables(0x0000, confirm = true))
  }

  // MARK: - MTU

  /** A notification has no continuation, so an oversized one would silently lose its tail; the boundary is exact. */
  @Test
  fun `a payload that exactly fills the link is accepted`() {
    assertNull(mtuErrorFor(negotiatedMtu = 23, size = 20))
    assertNull(mtuErrorFor(negotiatedMtu = 247, size = 244))
  }

  @Test
  fun `a payload one octet past the link is refused`() {
    assertNotNull(mtuErrorFor(negotiatedMtu = 23, size = 21))
    assertNotNull(mtuErrorFor(negotiatedMtu = 247, size = 245))
  }

  /** A link that has not negotiated still carries the specification default, not "unknown". */
  @Test
  fun `an unnegotiated link is measured against the default mtu`() {
    assertNull(mtuErrorFor(negotiatedMtu = null, size = DEFAULT_ATT_MTU - ATT_NOTIFICATION_HEADER_SIZE))
    assertNotNull(mtuErrorFor(negotiatedMtu = null, size = DEFAULT_ATT_MTU - ATT_NOTIFICATION_HEADER_SIZE + 1))
  }

  /** ATT_MTU 517 leaves 514 octets for the value, but an attribute may hold only 512 (Core Spec Vol 3, Part F, §3.2.9). */
  @Test
  fun `a payload past the attribute bound is refused however large the link is`() {
    assertNull(mtuErrorFor(negotiatedMtu = 517, size = MAX_ATTRIBUTE_VALUE_LENGTH))
    assertNotNull(mtuErrorFor(negotiatedMtu = 517, size = MAX_ATTRIBUTE_VALUE_LENGTH + 1))
    // 514 is what the arithmetic alone would have allowed.
    assertNotNull(mtuErrorFor(negotiatedMtu = 517, size = 517 - ATT_NOTIFICATION_HEADER_SIZE))
  }

  /** The link is not what refused it, so the message must not send the caller to the ATT_MTU. */
  @Test
  fun `the attribute bound refusal names the attribute rather than the link`() {
    val message = mtuErrorFor(negotiatedMtu = 517, size = 513)!!.message!!

    assertTrue(message, message.contains("512"))
    assertTrue(message, message.contains("attribute value"))
    assertFalse(message, message.contains("ATT MTU 517"))
  }

  /** Below the attribute bound the link is still the binding limit, and still what is reported. */
  @Test
  fun `a link smaller than the attribute bound is still measured by the link`() {
    val message = mtuErrorFor(negotiatedMtu = 247, size = 400)!!.message!!

    assertTrue(message, message.contains("ATT MTU 247"))
    assertFalse(message, message.contains("attribute value"))
  }

  @Test
  fun `the mtu refusal reports the code the api documents`() {
    assertEquals("PAYLOAD_EXCEEDS_MTU", mtuErrorFor(negotiatedMtu = 23, size = 40)!!.code)
  }

  // MARK: - Transmission type

  private val characteristic: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

  /** notifyCharacteristicChanged does not itself check the declared properties. */
  @Test
  fun `a characteristic without notify cannot send a notification`() {
    val error = confirmError(PROPERTY_INDICATE, characteristic, confirm = false)

    assertNotNull(error)
    assertEquals("ERR_CONFIRM_UNSUPPORTED", error!!.code)
    assertTrue(error.message!!, error.message!!.contains("\"notify\""))
  }

  @Test
  fun `a characteristic declaring the property may send it`() {
    assertNull(confirmError(PROPERTY_NOTIFY, characteristic, confirm = false))
    assertNull(confirmError(PROPERTY_INDICATE, characteristic, confirm = true))
    assertNull(
      confirmError(PROPERTY_NOTIFY or PROPERTY_INDICATE, characteristic, confirm = false)
    )
    assertNull(
      confirmError(PROPERTY_NOTIFY or PROPERTY_INDICATE, characteristic, confirm = true)
    )
  }

  @Test
  fun `a characteristic declaring neither may send neither`() {
    assertNotNull(confirmError(0, characteristic, confirm = false))
    assertNotNull(confirmError(0, characteristic, confirm = true))
  }

  /** Unrelated declared properties must not be mistaken for a transmission property. */
  @Test
  fun `read and write properties do not permit a transmission`() {
    val readWrite = 0x02 or 0x08

    assertNotNull(confirmError(readWrite, characteristic, confirm = false))
    assertNotNull(confirmError(readWrite, characteristic, confirm = true))
  }

  @Test
  fun `the refusal names the characteristic`() {
    val error = confirmError(0, characteristic, confirm = false)

    assertTrue(error!!.message!!, error.message!!.contains(characteristic.toString()))
  }

  // MARK: - Slicing a read

  /** The opposite of [rebasedResponseValue]: here the module owns the value, so an out-of-range offset must say so rather than answer empty. */
  @Test
  fun `an offset past the end is out of range`() {
    assertNull(readSliceAt(bytes(1, 2, 3), 4))
  }

  /** An offset exactly at the end is in range, and the attribute ends there. */
  @Test
  fun `an offset at the end reads empty`() {
    assertArrayEquals(ByteArray(0), readSliceAt(bytes(1, 2, 3), 3))
  }

  @Test
  fun `an offset of zero reads the whole value`() {
    assertArrayEquals(bytes(1, 2, 3), readSliceAt(bytes(1, 2, 3), 0))
  }

  /** The continuation an ATT_READ_BLOB_REQ asks for. */
  @Test
  fun `a blob continuation reads from the offset onwards`() {
    assertArrayEquals(bytes(3, 4, 5), readSliceAt(bytes(1, 2, 3, 4, 5), 2))
  }

  @Test
  fun `an empty attribute reads empty at offset zero and is out of range beyond it`() {
    assertArrayEquals(ByteArray(0), readSliceAt(ByteArray(0), 0))
    assertNull(readSliceAt(ByteArray(0), 1))
  }
}
