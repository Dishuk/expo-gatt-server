package expo.modules.gattserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The ATT arithmetic every request runs through. Plain JUnit — none of this touches the Android
 * framework, so it needs neither Robolectric nor a device.
 *
 * Several of these deliberately mirror `tests/swift/WriteAssemblyTests.swift` and
 * `ResponseRebasingTests.swift` case for case. The two platforms implement the same contracts
 * independently, and asserting the same inputs produce the same bytes on each is the only thing that
 * actually holds them together — the module's whole premise is that one configuration behaves the same
 * either side.
 */
class AttOperationsTest {
  private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

  // MARK: - Assembling a prepared write

  /**
   * Only the queued-write path reaches [spliceAt]; an unqueued `ATT_WRITE_REQ` replaces the value
   * outright elsewhere. So a part covers exactly what it carries and the rest of the attribute stays —
   * the property iOS had to be taught, having only the offset to go on.
   */
  @Test
  fun `a part at offset zero keeps the tail`() {
    assertArrayEquals(bytes(9, 9, 3, 4, 5), spliceAt(bytes(1, 2, 3, 4, 5), 0, bytes(9, 9)))
  }

  @Test
  fun `a part keeps both sides of the fragment`() {
    assertArrayEquals(bytes(1, 8, 3, 4, 5), spliceAt(bytes(1, 2, 3, 4, 5), 1, bytes(8)))
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
  fun `an offset exactly at the end is in range`() {
    assertNotNull(spliceAt(bytes(1, 2), 2, bytes(9)))
  }

  @Test
  fun `an empty part leaves the value alone`() {
    assertArrayEquals(bytes(1, 2, 3), spliceAt(bytes(1, 2, 3), 1, ByteArray(0)))
  }

  /**
   * Folds a multi-part long write the way `assemblePreparedWrites` does. This is the case that used to
   * disagree with iOS: a write that stops short of the attribute's end must leave the remainder.
   */
  @Test
  fun `a multi part long write stopping short keeps the remainder`() {
    var value = bytes(1, 2, 3, 4, 5, 6, 7, 8)
    value = spliceAt(value, 0, bytes(10, 11))!!
    value = spliceAt(value, 2, bytes(12, 13))!!

    assertArrayEquals(bytes(10, 11, 12, 13, 5, 6, 7, 8), value)
  }

  @Test
  fun `a multi part long write may grow the attribute`() {
    var value = bytes(1, 2)
    value = spliceAt(value, 0, bytes(1, 2))!!
    value = spliceAt(value, 2, bytes(3, 4))!!
    value = spliceAt(value, 4, bytes(5))!!

    assertArrayEquals(bytes(1, 2, 3, 4, 5), value)
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

  @Test
  fun `the whole value at offset zero answers a plain read`() {
    assertArrayEquals(bytes(1, 2, 3), rebase(bytes(1, 2, 3), supplied = 0, requested = 0))
  }

  @Test
  fun `the whole value at offset zero answers a read blob continuation`() {
    assertArrayEquals(bytes(3, 4, 5), rebase(bytes(1, 2, 3, 4, 5), supplied = 0, requested = 2))
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

  /**
   * Supplying the value from *after* the requested offset leaves the octets the central asked for
   * missing, which nothing downstream could detect — so it is refused rather than sent short.
   */
  @Test
  fun `supplying from past the requested offset is rejected`() {
    val error = runCatching { rebase(bytes(1, 2, 3), supplied = 4, requested = 2) }
      .exceptionOrNull() as? GattServerException

    assertNotNull(error)
    assertEquals("ERR_RESPONSE_OFFSET", error!!.code)
    assertTrue(error.message!!, error.message!!.contains("offset 2"))
  }

  @Test
  fun `supplying from exactly the requested offset is accepted`() {
    assertArrayEquals(bytes(1, 2, 3), rebase(bytes(1, 2, 3), supplied = 2, requested = 2))
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
  fun `reading and writing the configuration round trips`() {
    for (bits in listOf(0x0000, 0x0001, 0x0002, 0x0003, 0x00FF, 0xFF00, 0xFFFF)) {
      assertEquals(bits, cccdBits(cccdValue(bits)))
    }
  }

  @Test
  fun `the configuration value is always the two octets the specification fixes`() {
    for (bits in listOf(0x0000, 0x0001, 0xFFFF)) {
      assertEquals(CCCD_VALUE_LENGTH, cccdValue(bits).size)
    }
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

  /**
   * The distinction that keeps the server from sending a client something it did not ask for: "when a
   * bit is set, that action shall be enabled, otherwise it will not be used". Gating on either bit
   * would hand a notification to a client that enabled only indications.
   */
  @Test
  fun `a client that enabled only notifications is not sent indications`() {
    assertTrue(cccdEnables(CCCD_NOTIFY_BIT, confirm = false))
    assertFalse(cccdEnables(CCCD_NOTIFY_BIT, confirm = true))
  }

  @Test
  fun `a client that enabled only indications is not sent notifications`() {
    assertTrue(cccdEnables(CCCD_INDICATE_BIT, confirm = true))
    assertFalse(cccdEnables(CCCD_INDICATE_BIT, confirm = false))
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

  /**
   * The platform truncates an oversized notification rather than failing it, and a notification has no
   * continuation, so the tail would be lost with nothing to recover it. The boundary is exact.
   */
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

  /**
   * The hint is the actionable part — it says the link simply has not negotiated yet — so it belongs
   * only where that is true. On a negotiated link it would be actively misleading.
   */
  @Test
  fun `the default mtu hint appears only while the link is unnegotiated`() {
    assertTrue(mtuErrorFor(negotiatedMtu = null, size = 40)!!.message!!.contains("default ATT MTU"))
    assertFalse(mtuErrorFor(negotiatedMtu = 247, size = 400)!!.message!!.contains("default ATT MTU"))
  }

  @Test
  fun `the mtu refusal reports the code the api documents`() {
    assertEquals("PAYLOAD_EXCEEDS_MTU", mtuErrorFor(negotiatedMtu = 23, size = 40)!!.code)
  }

  @Test
  fun `the mtu refusal names both sizes and says nothing was sent`() {
    val message = mtuErrorFor(negotiatedMtu = 247, size = 400)!!.message!!

    assertTrue(message, message.contains("400"))
    assertTrue(message, message.contains("244"))
    assertTrue(message, message.contains("Nothing was sent"))
  }

  // MARK: - Transmission type

  private val characteristic: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

  /**
   * `notifyCharacteristicChanged` checks neither the declaration nor the client's configuration, so
   * without this the stack would emit a PDU no client could legally have asked for.
   */
  @Test
  fun `a characteristic without notify cannot send a notification`() {
    val error = confirmError(PROPERTY_INDICATE, characteristic, confirm = false)

    assertNotNull(error)
    assertEquals("ERR_CONFIRM_UNSUPPORTED", error!!.code)
    assertTrue(error.message!!, error.message!!.contains("\"notify\""))
  }

  @Test
  fun `a characteristic without indicate cannot send an indication`() {
    val error = confirmError(PROPERTY_NOTIFY, characteristic, confirm = true)

    assertNotNull(error)
    assertTrue(error!!.message!!, error.message!!.contains("\"indicate\""))
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

  /**
   * The opposite contract to [rebasedResponseValue], which answers past-the-end with an empty value
   * because the caller supplying it has declared where the attribute ends. Here the module owns the
   * value, so it knows the offset is out of range and must say so — the distinction is easy to invert,
   * and inverting it is invisible until a peer asks for a blob.
   */
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

  /**
   * The continuation an `ATT_READ_BLOB_REQ` asks for. Answering with the whole value again — which the
   * descriptor path used to do — makes the central reassemble a repeated prefix.
   */
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
