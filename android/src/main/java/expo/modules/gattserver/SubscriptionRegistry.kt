package expo.modules.gattserver

import java.util.concurrent.ConcurrentHashMap

/**
 * What every client has written to the Client Characteristic Configuration descriptors it can reach —
 * Core Spec Vol 3, Part G, §3.3.3.3.
 *
 * Keyed by device and by characteristic *address* rather than UUID alone, so the same characteristic
 * UUID in two services cannot be mistaken for one subscription.
 */
internal class SubscriptionRegistry {
  private val byDevice = ConcurrentHashMap<String, ConcurrentHashMap<CharacteristicAddress, Int>>()

  /** The two-octet configuration this client last wrote, or the specified default of 0x0000. */
  fun configurationOf(deviceId: String, address: CharacteristicAddress): Int =
    byDevice[deviceId]?.get(address) ?: 0

  /** Whether [deviceId] enabled exactly the transmission [confirm] selects. See [cccdEnables]. */
  fun hasEnabled(deviceId: String, address: CharacteristicAddress, confirm: Boolean): Boolean =
    cccdEnables(configurationOf(deviceId, address), confirm)

  /**
   * Records a client's new configuration and reports whether it had been receiving anything before, so
   * the caller can emit only the transitions — switching between notifications and indications leaves
   * the client subscribed throughout.
   *
   * Android 13+ gives one connection several concurrent ATT bearers, so two CCCD writes from the same
   * central do arrive on two binder threads. Everything — including the previous state the return value
   * reports — must therefore stay inside one `compute`, which holds the key's bin lock; `getOrPut` and
   * a separately sampled previous state are both `get()` followed by `put()`, and race.
   */
  fun record(deviceId: String, address: CharacteristicAddress, bits: Int): Boolean {
    var wasEnabled = false
    byDevice.compute(deviceId) { _, forDevice ->
      val previous = forDevice?.get(address) ?: 0
      wasEnabled = cccdSubscribed(previous)
      if (bits == 0) {
        forDevice?.remove(address)
        if (forDevice.isNullOrEmpty()) null else forDevice
      } else {
        (forDevice ?: ConcurrentHashMap()).also { it[address] = bits }
      }
    }
    return wasEnabled
  }

  /**
   * Forgets everything [deviceId] configured and reports the addresses it was actually receiving on, so
   * each can be announced as ended.
   */
  fun clear(deviceId: String): List<CharacteristicAddress> {
    val forDevice = byDevice.remove(deviceId) ?: return emptyList()
    return forDevice.filterValues { cccdSubscribed(it) }.keys.toList()
  }

  fun clearAll() {
    byDevice.clear()
  }
}
