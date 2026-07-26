package expo.modules.gattserver

import java.util.UUID

/**
 * The ATT-level arithmetic the server runs on every request: assembling a written value, aligning a
 * response, reading and writing a Client Characteristic Configuration, and refusing a transmission the
 * link or the declaration cannot carry.
 *
 * Free functions over plain values rather than methods on [GattServerManager], because none of it
 * depends on any server state and all of it is directly visible to a peer — a mistake here sends wrong
 * bytes and reports nothing. Keeping it separable is what lets it be exercised on the JVM, without the
 * Android framework and without a device. The iOS peripheral implements the same contracts.
 */

/**
 * Merges one written part into [current] at [offset], or returns `null` for an offset beyond the
 * current end — which the specification answers with "Invalid Offset". An offset exactly at the end
 * appends and is in range.
 *
 * The octets past the part are preserved: a queued write's part goes at "the offset of the first octet
 * where the Part Attribute Value parameter is to be written" (Core Spec Vol 3, Part F, §3.4.6.1), which
 * says nothing about the rest. An unqueued `ATT_WRITE_REQ` is *not* assembled through here — it
 * replaces the value outright, because "the attribute value shall be truncated or lengthened to match
 * the length of the Attribute Value parameter" (§3.4.5.1).
 */
internal fun spliceAt(current: ByteArray, offset: Int, part: ByteArray): ByteArray? {
  if (offset > current.size) return null
  val result = current.copyOf(maxOf(current.size, offset + part.size))
  part.copyInto(result, offset)
  return result
}

/**
 * The bytes a read of [value] from [offset] is answered with, or `null` for an offset past the end —
 * which the specification answers with "Invalid Offset" (Core Spec Vol 3, Part F, §3.4.1.1). An offset
 * exactly at the end is in range and reads as empty.
 *
 * The stack copies the value into the response PDU verbatim rather than slicing it by the offset, so the
 * alignment has to happen here. Shared by the characteristic and the descriptor read paths, which had
 * otherwise drifted: the descriptor one answered every Read Blob with the whole value again, so a
 * central reassembling a value longer than one PDU saw its prefix repeated.
 */
internal fun readSliceAt(value: ByteArray, offset: Int): ByteArray? {
  if (offset > value.size) return null
  if (offset == value.size) return ByteArray(0)
  return value.copyOfRange(offset, value.size)
}

/**
 * Rebases a response value supplied from [suppliedOffset] onto [requestedOffset], the offset the
 * request actually asked for.
 *
 * The stack copies the value into the response PDU verbatim — it does not slice it by the offset, which
 * for a read response is never even transmitted — so the alignment has to happen here. Both documented
 * spellings therefore work: the whole value with offset 0, or an already-sliced value with the
 * request's own offset. iOS honours the same contract.
 *
 * A write response carries no value, so [isRead] `false` passes it through untouched.
 */
internal fun rebasedResponseValue(
  value: ByteArray,
  isRead: Boolean,
  suppliedOffset: Int,
  requestedOffset: Int,
  requestId: Int,
): ByteArray {
  if (!isRead) return value

  if (suppliedOffset > requestedOffset) {
    throw GattServerException(
      "ERR_RESPONSE_OFFSET",
      "Request $requestId asked for the attribute from offset $requestedOffset, but the " +
        "response supplies it from offset $suppliedOffset, which leaves the requested bytes " +
        "missing. Pass the value together with the offset it starts at — offset 0 with the whole " +
        "value always works."
    )
  }
  val skip = requestedOffset - suppliedOffset
  if (skip == 0) return value
  // The caller supplied nothing at or beyond the requested offset, which is the specification's
  // signal that the attribute ends there.
  if (skip >= value.size) return ByteArray(0)
  return value.copyOfRange(skip, value.size)
}

/**
 * Reads the two-octet, little-endian Client Characteristic Configuration value — Core Spec Vol 3,
 * Part G, §3.3.3.3. The caller has already established that [value] is exactly two octets, which is
 * the only length the specification permits.
 */
internal fun cccdBits(value: ByteArray): Int =
  (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)

/** The two-octet, little-endian spelling of [bits], for answering a CCCD read. */
internal fun cccdValue(bits: Int): ByteArray =
  byteArrayOf((bits and 0xFF).toByte(), ((bits shr 8) and 0xFF).toByte())

/**
 * Whether [bits] enables either transmission. This is the coarse question the subscribe and unsubscribe
 * events answer, since switching between notifications and indications leaves the client subscribed
 * throughout.
 */
internal fun cccdSubscribed(bits: Int): Boolean =
  bits and (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) != 0

/**
 * Whether [bits] enables exactly the transmission [confirm] selects. "When a bit is set, that action
 * shall be enabled, otherwise it will not be used" (Core Spec Vol 3, Part G, §3.3.3.3), so a client
 * that enabled only indications must not be handed a notification, and vice versa — gating on either
 * bit would send whichever the caller asked for regardless of the client's configuration.
 */
internal fun cccdEnables(bits: Int, confirm: Boolean): Boolean {
  val required = if (confirm) CCCD_INDICATE_BIT else CCCD_NOTIFY_BIT
  return bits and required != 0
}

/**
 * Refuses a payload the link cannot carry in one notification, before anything is transmitted. The
 * platform silently truncates an oversized notification rather than failing it, and a notification has
 * no continuation mechanism — unlike a read, which the central can finish with a Read Blob request —
 * so sending it would lose the tail with nothing to recover it.
 *
 * [negotiatedMtu] is `null` for a link that has not negotiated one, which still carries the
 * specification default.
 */
internal fun mtuErrorFor(negotiatedMtu: Int?, size: Int): MtuException? {
  val mtu = negotiatedMtu ?: DEFAULT_ATT_MTU
  val maxPayload = mtu - ATT_NOTIFICATION_HEADER_SIZE
  if (size <= maxPayload) return null
  val hint = if (negotiatedMtu == null) {
    " The link is still at the default ATT MTU of $DEFAULT_ATT_MTU; a central that negotiates a " +
      "larger one is reported through onMtuChanged."
  } else {
    ""
  }
  return MtuException(
    "PAYLOAD_EXCEEDS_MTU",
    "Payload size $size exceeds the $maxPayload bytes a single notification or indication can " +
      "carry on this link (ATT MTU $mtu). Nothing was sent.$hint"
  )
}

/**
 * Refuses a transmission type the characteristic never declared. The specification permits each
 * transmission only when its property is set (Core Spec Vol 3, Part G, Table 3.5) and lets a client
 * enable the matching CCCD bit only then (Table 3.11). Android's `notifyCharacteristicChanged` checks
 * neither, so without this the stack would emit a PDU no client could legally have asked for.
 */
internal fun confirmError(
  properties: Int,
  characteristicUuid: UUID,
  confirm: Boolean,
): GattServerException? {
  val required = if (confirm) PROPERTY_INDICATE else PROPERTY_NOTIFY
  if (properties and required != 0) return null
  val message = if (confirm) {
    "Characteristic $characteristicUuid does not declare the \"indicate\" property, so it " +
      "cannot send the acknowledged indication confirm: true asks for. Declare \"indicate\" on " +
      "the characteristic, or send a notification with confirm: false."
  } else {
    "Characteristic $characteristicUuid does not declare the \"notify\" property, so it " +
      "cannot send an unacknowledged notification. Declare \"notify\" on the characteristic, or " +
      "send an indication with confirm: true."
  }
  return GattServerException("ERR_CONFIRM_UNSUPPORTED", message)
}

/**
 * `BluetoothGattCharacteristic.PROPERTY_NOTIFY` and `PROPERTY_INDICATE`, restated so this file stays
 * free of the framework and runs on a plain JVM. Both are `public static final` bits of the
 * characteristic declaration (Core Spec Vol 3, Part G, Table 3.5), so they cannot drift; the Robolectric
 * suite asserts they still agree with the platform's own.
 */
internal const val PROPERTY_NOTIFY = 0x10
internal const val PROPERTY_INDICATE = 0x20
