package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService

/**
 * One value a partially delegated execute withheld, kept with what the attribute held when the execute
 * was assembled — the only thing a later commit can tell a stale value apart by.
 */
internal class DeferredWrite(
  val value: ByteArray,
  /** `null` when the attribute had no value at all, which is distinct from an empty one. */
  val baseline: ByteArray?,
)

/**
 * The mirrored attribute values that reads are answered from, and the one monitor guarding them.
 *
 * The framework's `value` fields are plain mutable state: non-volatile and unsynchronized, written from
 * binder threads and read from others. Every access goes through here — reads included, both for
 * ordering and for the atomicity of the read-modify-write that a long write performs.
 */
internal class AttributeStore {
  private val lock = Any()

  /**
   * Runs [body] with the value monitor held, for a read-modify-write that must not interleave with
   * another. Nothing that reports to a listener or enters the binder may run inside it, with the one
   * exception [GattServerManager.notifyValue] documents.
   */
  fun <T> transaction(body: () -> T): T = synchronized(lock, body)

  @Suppress("DEPRECATION")
  fun valueOf(characteristic: BluetoothGattCharacteristic): ByteArray? =
    synchronized(lock) { characteristic.value }

  @Suppress("DEPRECATION")
  fun valueOf(descriptor: BluetoothGattDescriptor): ByteArray? =
    synchronized(lock) { descriptor.value }

  /** Replaces the mirrored value a read of [characteristic] is answered from. */
  @Suppress("DEPRECATION")
  fun store(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
    synchronized(lock) { characteristic.value = value }
  }

  @Suppress("DEPRECATION")
  fun store(descriptor: BluetoothGattDescriptor, value: ByteArray) {
    synchronized(lock) { descriptor.value = value }
  }

  /**
   * The values [services] hold now, keyed by address, so a registration round can carry them onto the
   * fresh instances the next one builds. Read from the retained instances rather than from the server,
   * which is closed before the next round starts.
   */
  @Suppress("DEPRECATION")
  fun snapshot(services: List<BluetoothGattService>): Map<CharacteristicAddress, ByteArray> {
    if (services.isEmpty()) return emptyMap()
    val values = HashMap<CharacteristicAddress, ByteArray>()
    synchronized(lock) {
      for (service in services) {
        for (characteristic in service.characteristics) {
          val value = characteristic.value ?: continue
          values[CharacteristicAddress(service.uuid, characteristic.uuid)] = value
        }
      }
    }
    return values
  }

  /**
   * Carries [values] onto the matching characteristics of [services]. An address the configuration no
   * longer declares is dropped, and one that was never written keeps whatever the configuration gave it.
   */
  @Suppress("DEPRECATION")
  fun restore(services: List<BluetoothGattService>, values: Map<CharacteristicAddress, ByteArray>) {
    if (values.isEmpty()) return
    synchronized(lock) {
      for (service in services) {
        for (characteristic in service.characteristics) {
          val value = values[CharacteristicAddress(service.uuid, characteristic.uuid)] ?: continue
          characteristic.value = value
        }
      }
    }
  }

  /**
   * Applies the characteristic values a partially delegated execute withheld, and reports which of them
   * were actually applied.
   *
   * An attribute something else has written since the execute was assembled — `updateCharacteristicValue`
   * or another client — keeps that newer value rather than having a completed write silently undone.
   */
  @Suppress("DEPRECATION")
  fun commitDeferredValues(
    deferred: Map<BluetoothGattCharacteristic, DeferredWrite>,
  ): Map<BluetoothGattCharacteristic, DeferredWrite> {
    if (deferred.isEmpty()) return emptyMap()
    val committed = LinkedHashMap<BluetoothGattCharacteristic, DeferredWrite>()
    synchronized(lock) {
      for ((characteristic, write) in deferred) {
        if (!characteristic.value.contentEquals(write.baseline)) {
          logDebug { "Deferred write to ${characteristic.uuid} was superseded, keeping the newer value" }
          continue
        }
        characteristic.value = write.value
        committed[characteristic] = write
      }
    }
    return committed
  }

  /** Undoes [commitDeferredValues] where nothing has written the attribute since. */
  @Suppress("DEPRECATION")
  fun revertDeferredValues(committed: Map<BluetoothGattCharacteristic, DeferredWrite>) {
    if (committed.isEmpty()) return
    synchronized(lock) {
      for ((characteristic, write) in committed) {
        if (!characteristic.value.contentEquals(write.value)) continue
        characteristic.value = write.baseline
      }
    }
  }

  /**
   * The plain descriptors of the same execute, held back for the same reason and applied at the same
   * moment — against the baseline each was assembled from, as the characteristics are. A delegated
   * execute stays open for up to `requestTimeoutMs`, and an unqueued descriptor write or another
   * device's execute can land in that window, so committing regardless reverted a newer value.
   */
  @Suppress("DEPRECATION")
  fun commitDeferredDescriptors(
    deferred: Map<BluetoothGattDescriptor, DeferredWrite>,
  ): Map<BluetoothGattDescriptor, DeferredWrite> {
    if (deferred.isEmpty()) return emptyMap()
    val committed = LinkedHashMap<BluetoothGattDescriptor, DeferredWrite>()
    synchronized(lock) {
      for ((descriptor, write) in deferred) {
        if (!descriptor.value.contentEquals(write.baseline)) {
          logDebug { "Deferred write to descriptor ${descriptor.uuid} was superseded, keeping the newer value" }
          continue
        }
        descriptor.value = write.value
        committed[descriptor] = write
      }
    }
    return committed
  }

  /** Undoes [commitDeferredDescriptors] where nothing has written the descriptor since. */
  @Suppress("DEPRECATION")
  fun revertDeferredDescriptors(committed: Map<BluetoothGattDescriptor, DeferredWrite>) {
    if (committed.isEmpty()) return
    synchronized(lock) {
      for ((descriptor, write) in committed) {
        if (!descriptor.value.contentEquals(write.value)) continue
        descriptor.value = write.baseline
      }
    }
  }
}
