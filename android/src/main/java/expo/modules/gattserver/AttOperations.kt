package expo.modules.gattserver

import java.util.UUID

/**
 * ATT-level arithmetic for assembling values, aligning responses, reading/writing CCCD, and refusing
 * transmissions the link cannot carry. Free functions to allow JVM-only testing without Android framework.
 */

/**
 * Merges one written part into [current] at [offset], or null for offset beyond end (Core Spec Vol 3, Part F, §3.4.6.1).
 * Preserves octets past the part; unqueued ATT_WRITE_REQ replaces the value outright (§3.4.5.1).
 */
internal fun spliceAt(current: ByteArray, offset: Int, part: ByteArray): ByteArray? {
  if (offset > current.size) return null
  val result = current.copyOf(maxOf(current.size, offset + part.size))
  part.copyInto(result, offset)
  return result
}

/**
 * Checks if value exceeds max attribute length (512 octets, Core Spec Vol 3, Part F, §3.2.9).
 * Separate from spliceAt because they check different ATT errors.
 */
internal fun exceedsAttributeLength(size: Int): Boolean = size > MAX_ATTRIBUTE_VALUE_LENGTH

/**
 * Returns bytes from [value] at [offset], or null for offset past end (Core Spec Vol 3, Part F, §3.4.1.1).
 * Stack copies value verbatim to PDU; alignment must happen here.
 */
internal fun readSliceAt(value: ByteArray, offset: Int): ByteArray? {
  if (offset > value.size) return null
  if (offset == value.size) return ByteArray(0)
  return value.copyOfRange(offset, value.size)
}

/**
 * Rebases response value from [suppliedOffset] to [requestedOffset]. Stack copies verbatim to PDU;
 * alignment here. Supports both whole value (offset 0) and pre-sliced value. iOS honors same contract.
 */
internal fun rebasedResponseValue(
  value: ByteArray,
  isRead: Boolean,
  suppliedOffset: Int,
  requestedOffset: Int,
  requestId: Int,
): ByteArray {
  if (!isRead) return value

  // Check negative offsets here: native module is reachable directly. Negative offset silently trimmed response and sent incorrect data.
  if (suppliedOffset < 0 || requestedOffset < 0) {
    throw GattServerException(
      "ERR_RESPONSE_OFFSET",
      "Request $requestId was answered with offset $suppliedOffset against a requested offset of " +
        "$requestedOffset. An ATT offset is an unsigned 16-bit value."
    )
  }

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
  // Caller supplied nothing at or beyond requested offset; attribute ends there per spec.
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
 * Refuses payload exceeding link MTU or attribute limit (512 octets, Core Spec Vol 3, Part F, §3.2.9).
 * Platform silently truncates oversized notifications with no recovery. [negotiatedMtu] null means default ATT MTU.
 */
internal fun mtuErrorFor(negotiatedMtu: Int?, size: Int): MtuException? {
  val mtu = negotiatedMtu ?: DEFAULT_ATT_MTU
  val maxPayload = minOf(mtu - ATT_NOTIFICATION_HEADER_SIZE, MAX_ATTRIBUTE_VALUE_LENGTH)
  if (size <= maxPayload) return null
  val hint = if (negotiatedMtu == null) {
    " The link is still at the default ATT MTU of $DEFAULT_ATT_MTU; a central that negotiates a " +
      "larger one is reported through onMtuChanged."
  } else {
    ""
  }
  val limit = if (maxPayload == MAX_ATTRIBUTE_VALUE_LENGTH) {
    "an attribute value may hold (Core Spec Vol 3, Part F, §3.2.9)"
  } else {
    "a single notification or indication can carry on this link (ATT MTU $mtu)"
  }
  return MtuException(
    "PAYLOAD_EXCEEDS_MTU",
    "Payload size $size exceeds the $maxPayload bytes $limit. Nothing was sent.$hint"
  )
}

/**
 * Refuses transmission type not declared on characteristic (Core Spec Vol 3, Part G, Table 3.5 & 3.11).
 * Android's notifyCharacteristicChanged checks neither; this prevents illegal PDUs.
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
 * BluetoothGattCharacteristic properties from Core Spec Vol 3, Part G, Table 3.5.
 * Restated to keep this file JVM-only. Robolectric suite asserts they match the platform.
 */
internal const val PROPERTY_NOTIFY = 0x10
internal const val PROPERTY_INDICATE = 0x20
