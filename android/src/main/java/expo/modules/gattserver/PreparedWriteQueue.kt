package expo.modules.gattserver

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Prepared writes one device may queue before an execute. The specification leaves the limit to "a
 * higher layer specification" (Core Spec Vol 3, Part F, §3.4.6.1) and answers an overrun with
 * "Prepare Queue Full". 64 covers a 512-octet attribute written in the smallest parts the default
 * ATT_MTU allows, with room to spare for a reliable write spanning several attributes.
 */
private const val MAX_PREPARED_WRITES_PER_DEVICE = 64

/**
 * One `ATT_PREPARE_WRITE_REQ` held until its execute arrives. The attribute must not change until the
 * execute, and repeats of the same handle are executed in the order received rather than replacing one
 * another (Core Spec Vol 3, Part F, §3.4.6.1).
 */
internal sealed class PreparedWrite {
  abstract val offset: Int
  abstract val value: ByteArray

  class ToCharacteristic(
    val characteristic: BluetoothGattCharacteristic,
    override val offset: Int,
    override val value: ByteArray,
  ) : PreparedWrite()

  class ToDescriptor(
    val descriptor: BluetoothGattDescriptor,
    override val offset: Int,
    override val value: ByteArray,
  ) : PreparedWrite()
}

/**
 * What one execute assembled. Everything it could commit is already committed by the time this exists;
 * what remains is the response, the CCCD transitions and the events — none of which may run under the
 * attribute value monitor.
 */
internal class AssembledExecute(
  /** The ATT error the execute must be answered with, or `null` when it assembled cleanly. */
  val attError: Int? = null,
  val characteristicValues: Map<BluetoothGattCharacteristic, ByteArray> = emptyMap(),
  val clientConfigurations: List<Pair<BluetoothGattDescriptor, Int>> = emptyList(),
  /** The characteristics of this execute that hand their writes to JavaScript. */
  val delegated: Set<BluetoothGattCharacteristic> = emptySet(),
  /** Values withheld until JavaScript accepts the execute; empty unless it is partially delegated. */
  val deferredValues: Map<BluetoothGattCharacteristic, DeferredWrite> = emptyMap(),
  /** Plain descriptor values withheld for the same reason, and on the same condition. */
  val deferredDescriptors: Map<BluetoothGattDescriptor, DeferredWrite> = emptyMap(),
)

/**
 * The per-device prepare queues of the queued-write procedure, and the assembly one execute performs.
 *
 * Nothing here touches the Bluetooth stack: the caller sends the ATT responses, because the queue's job
 * is only to decide what they should say.
 */
internal class PreparedWriteQueue(private val values: AttributeStore) {
  // Per-device queue — Core Spec Vol 3, Part F, §3.4.6.1. Bin lock synchronizes binder threads.
  private val queues = ConcurrentHashMap<String, MutableList<PreparedWrite>>()

  /**
   * Holds one part until the execute arrives, reporting whether there was room. A refused prepare leaves
   * the existing queue untouched, as the specification requires.
   */
  fun offer(deviceId: String, write: PreparedWrite): Boolean {
    var accepted = false
    queues.compute(deviceId) { _, existing ->
      val queue = existing ?: mutableListOf()
      if (queue.size < MAX_PREPARED_WRITES_PER_DEVICE) {
        queue.add(write)
        accepted = true
      }
      queue
    }
    if (!accepted) {
      Log.w(TAG, "onPreparedWrite: queue full for device=$deviceId, rejecting")
    }
    return accepted
  }

  /** Detaches everything [deviceId] has queued, which an execute or an abort consumes. */
  fun take(deviceId: String): List<PreparedWrite> = queues.remove(deviceId) ?: emptyList()

  /** Bearer loss clears the prepare queue without executing (Core Spec Vol 3, Part F, §3.4.6.1). */
  fun discard(deviceId: String) {
    queues.remove(deviceId)
  }

  fun discardAll() {
    queues.clear()
  }

  /**
   * Merges every queued part onto the value its attribute holds *now* and commits the result, the whole
   * read-modify-write under the attribute value monitor. Assembling outside it would merge onto a value a
   * concurrent write or `updateCharacteristicValue` had already replaced, and the commit would then lose
   * that write.
   *
   * Nothing is committed unless every part validates: the execute either wholly succeeds or wholly fails,
   * because a part starting past the end of its attribute is answered with "Invalid Offset" and discards
   * the entire queue (Core Spec Vol 3, Part F, §3.4.6.3). A CCCD is only assembled and returned, because
   * applying one reports to a listener.
   */
  fun assemble(
    queued: List<PreparedWrite>,
    delegatesWrite: (BluetoothGattCharacteristic) -> Boolean,
  ): AssembledExecute = values.transaction {
    // Identity-keyed, which is what is wanted: these are the very instances the published database
    // holds, and neither class overrides equals.
    val characteristicValues = LinkedHashMap<BluetoothGattCharacteristic, ByteArray>()
    val descriptorValues = LinkedHashMap<BluetoothGattDescriptor, ByteArray>()
    // What each characteristic held when this execute was assembled, so a commit deferred until
    // JavaScript answers can tell whether anything has written it since.
    val baselines = HashMap<BluetoothGattCharacteristic, ByteArray?>()

    for (write in queued) {
      @Suppress("DEPRECATION")
      val current = when (write) {
        is PreparedWrite.ToCharacteristic -> {
          if (!baselines.containsKey(write.characteristic)) {
            baselines[write.characteristic] = write.characteristic.value
          }
          characteristicValues[write.characteristic] ?: baselines[write.characteristic]
        }
        is PreparedWrite.ToDescriptor ->
          descriptorValues[write.descriptor] ?: write.descriptor.value
      } ?: ByteArray(0)

      val merged = spliceAt(current, write.offset, write.value)
      if (merged == null) {
        Log.w(TAG, "onExecuteWrite: offset ${write.offset} past the end of a ${current.size}-byte value, rejecting")
        return@transaction AssembledExecute(attError = BluetoothGatt.GATT_INVALID_OFFSET)
      }
      // Checked on the assembled result rather than on each part: the parts are individually within
      // what a PDU carries, and it is only their placement that can push the attribute past what one
      // may hold. Left unchecked, a peer could commit a value longer than the specification allows —
      // which the module then refused to notify for the rest of the server's life, since the
      // notification bound is the same 512 octets, and carried across every adapter power cycle.
      if (exceedsAttributeLength(merged.size)) {
        Log.w(TAG, "onExecuteWrite: assembles to ${merged.size} octets, past the $MAX_ATTRIBUTE_VALUE_LENGTH-octet limit, rejecting")
        return@transaction AssembledExecute(attError = ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH)
      }
      when (write) {
        is PreparedWrite.ToCharacteristic -> characteristicValues[write.characteristic] = merged
        is PreparedWrite.ToDescriptor -> descriptorValues[write.descriptor] = merged
      }
    }

    // The specification fixes a CCCD at two octets, so a prepared write assembling to any other length
    // is rejected rather than parsed into a guess, exactly as a direct write would be.
    for ((descriptor, value) in descriptorValues) {
      if (descriptor.uuid == CCCD_UUID && value.size != CCCD_VALUE_LENGTH) {
        Log.w(TAG, "onExecuteWrite: prepared CCCD write assembles to ${value.size} octets, rejecting")
        return@transaction AssembledExecute(attError = ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH)
      }
    }

    // Decided per characteristic, as the direct write path already does: one that never opted in must
    // still have its value applied, even when a sibling in the same execute delegates. Worked out
    // before anything is committed, because whether this execute is still refusable is what decides
    // which parts of it may be applied now.
    val delegated = characteristicValues.keys.filterTo(LinkedHashSet(), delegatesWrite)
    val automatic = characteristicValues.filterKeys { it !in delegated }
    val refusable = delegated.isNotEmpty()

    val clientConfigurations = ArrayList<Pair<BluetoothGattDescriptor, Int>>()
    val plainDescriptors = LinkedHashMap<BluetoothGattDescriptor, ByteArray>()
    for ((descriptor, value) in descriptorValues) {
      if (descriptor.uuid == CCCD_UUID) {
        clientConfigurations.add(descriptor to cccdBits(value))
      } else {
        plainDescriptors[descriptor] = value
      }
    }

    // Applied straight away only when nothing in the execute is delegated. Otherwise the execute is
    // one atomic operation that JavaScript may still reject, so these wait for its answer too — the
    // characteristic values, the CCCD transitions and the plain descriptors alike. Committing the
    // descriptors here regardless was the one part of an execute a refusal could not take back.
    if (!refusable) {
      for ((characteristic, value) in automatic) {
        @Suppress("DEPRECATION")
        characteristic.value = value
      }
      for ((descriptor, value) in plainDescriptors) {
        @Suppress("DEPRECATION")
        descriptor.value = value
      }
    }

    AssembledExecute(
      characteristicValues = characteristicValues,
      clientConfigurations = clientConfigurations,
      delegated = delegated,
      deferredValues = if (refusable) {
        automatic.mapValues { (characteristic, value) ->
          DeferredWrite(value, baselines[characteristic])
        }
      } else {
        emptyMap()
      },
      deferredDescriptors = if (refusable) {
        plainDescriptors.mapValues { (descriptor, value) ->
          @Suppress("DEPRECATION")
          DeferredWrite(value, descriptor.value)
        }
      } else {
        emptyMap()
      },
    )
  }
}
