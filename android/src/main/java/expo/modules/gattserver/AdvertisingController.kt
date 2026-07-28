package expo.modules.gattserver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Longest duration `AdvertiseSettings.Builder.setTimeout` accepts — "May not exceed 180000
 * milliseconds" — the Bluetooth SIG limit the platform names `LIMITED_ADVERTISING_MAX_MILLIS`.
 */
const val MAX_ADVERTISING_TIMEOUT_MS = 180_000

/** Timeout for advertising start. startAdvertising callback is not guaranteed to arrive; bounds unrecoverable hangs. */
private const val ADVERTISING_START_TIMEOUT_MS = 30_000L

class ManufacturerData(val companyId: Int, val data: ByteArray)

class ServiceData(val uuid: UUID, val data: ByteArray)

/** Defaults deliberately match `AdvertiseSettings.Builder`'s own, rather than overriding them. */
class AdvertiseOptions(
  val localName: String? = null,
  val serviceUuids: List<UUID> = emptyList(),
  val includeTxPower: Boolean = false,
  val connectable: Boolean = true,
  val includeDeviceName: Boolean = false,
  val setAdapterName: Boolean = false,
  val mode: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
  val txPowerLevel: Int = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
  val timeoutMs: Int = 0,
  val manufacturerData: List<ManufacturerData> = emptyList(),
  val serviceData: List<ServiceData> = emptyList(),
)

/** The platform implements these as advertising intervals of 1 s, 250 ms and 100 ms. */
fun advertiseModeFor(name: String?): Int = when (name) {
  null, "lowPower" -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
  "balanced" -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
  "lowLatency" -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
  else -> throw IllegalArgumentException(
    "Invalid advertising mode \"$name\". Expected \"lowPower\", \"balanced\" or \"lowLatency\"."
  )
}

fun advertiseTxPowerFor(name: String?): Int = when (name) {
  "ultraLow" -> AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
  "low" -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
  null, "medium" -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
  "high" -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
  else -> throw IllegalArgumentException(
    "Invalid advertising tx power level \"$name\". Expected \"ultraLow\", \"low\", \"medium\" or " +
      "\"high\"."
  )
}

/**
 * The radio side of the peripheral: what is on the air, the promise waiting for the stack to say so, and
 * the system-wide adapter name an opt-in start may borrow.
 *
 * Every field is atomic because the `AdvertiseCallback`s arrive on binder threads while the caller is
 * still inside [start], and no lock may be held across a binder call.
 */
internal class AdvertisingController(
  private val adapter: BluetoothAdapter?,
  private val timeoutHandler: Handler,
  /** Holds a start until the database is published, rather than refusing it. */
  private val awaitDatabasePublished: (onReady: (GattServerException?) -> Unit) -> Unit,
  private val isServerRunning: () -> Boolean,
  private val databaseNotPublished: () -> GattServerException,
) {
  /** A start still waiting for its `AdvertiseCallback`. */
  private class PendingStart(
    val callback: AdvertiseCallback,
    val onResult: (GattServerException?) -> Unit,
  )

  private val advertiser = AtomicReference<BluetoothLeAdvertiser?>(null)
  private val advertiseCallback = AtomicReference<AdvertiseCallback?>(null)
  // Pairs callback with result to prevent stale callback from settling wrong completion.
  private val pendingStart = AtomicReference<PendingStart?>(null)

  // Bumped on stop to detect race: start knows if stop requested while waiting for database.
  private val generation = AtomicInteger(0)

  // Thread-safe state read from caller, written from binder threads.
  private val advertising = AtomicBoolean(false)
  private val airtimeTimeout = AtomicReference<Runnable?>(null)
  // Timeout for start, paired with callback to prevent stale timeout from evicting replacement.
  private val startTimeout = AtomicReference<Pair<AdvertiseCallback, Runnable>?>(null)
  // Set from the caller's thread, read again during a teardown that may be on another.
  private val originalAdapterName = AtomicReference<String?>(null)

  fun isAdvertising(): Boolean = advertising.get()

  /**
   * Advertises once the database is registered, holding the call rather than refusing it. [onResult] is
   * called exactly once. Stop races reject with a single meaning: stop requested.
   */
  fun start(options: AdvertiseOptions, onResult: (error: GattServerException?) -> Unit) {
    val claimed = generation.get()
    awaitDatabasePublished { error ->
      if (error != null) {
        onResult(error)
        return@awaitDatabasePublished
      }
      if (generation.get() != claimed) {
        onResult(advertisingStopped())
        return@awaitDatabasePublished
      }
      try {
        begin(options, claimed, onResult)
      } catch (e: GattServerException) {
        onResult(e)
      } catch (e: Exception) {
        onResult(GattServerException("ERR_ADVERTISE", e.message ?: "Advertising failed"))
      }
    }
  }

  /** Android advertises only the adapter name (not a per-advertisement one); localName needs setAdapterName. */
  @SuppressLint("MissingPermission")
  private fun begin(
    options: AdvertiseOptions,
    claimed: Int,
    onResult: (error: GattServerException?) -> Unit,
  ) {
    // Re-check: the adapter can turn off between the server check and the start. Matches iOS error
    // semantics, and the server check follows it so an adapter problem is reported as the root cause.
    bluetoothUnavailable(adapter)?.let { throw it }
    // Non-null by the check above, which reports the unsupported case itself.
    val liveAdapter = adapter ?: throw GattServerException("ERR_BLUETOOTH", BLUETOOTH_UNSUPPORTED_MESSAGE)

    if (!isServerRunning()) {
      throw databaseNotPublished()
    }

    if (options.setAdapterName && options.localName == null) {
      throw IllegalArgumentException(
        "android.setAdapterName was requested without a localName for the adapter to be renamed to."
      )
    }

    if (options.setAdapterName && options.localName != null) {
      applyAdapterName(liveAdapter, options.localName)
    }

    // Null only if no multi-ad support; the enabled check ruled out adapter off. Undo the rename if the
    // start cannot happen at all.
    val leAdvertiser = liveAdapter.bluetoothLeAdvertiser ?: run {
      restoreAdapterName()
      throw GattServerException("ERR_UNSUPPORTED", "BLE advertising is not supported on this device")
    }
    advertiser.set(leAdvertiser)

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(options.mode)
      .setTxPowerLevel(options.txPowerLevel)
      .setConnectable(options.connectable)
      .setTimeout(options.timeoutMs)
      .build()

    // Advertisement payload: 31-byte budget for UUIDs, manufacturer and service data. Name and TX power
    // go in the scan response so they do not compete for it.
    val advData = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .setIncludeTxPowerLevel(false)

    options.serviceUuids.forEach { uuid -> advData.addServiceUuid(ParcelUuid(uuid)) }
    options.manufacturerData.forEach { advData.addManufacturerData(it.companyId, it.data) }
    options.serviceData.forEach { advData.addServiceData(ParcelUuid(it.uuid), it.data) }

    val scanResponse = AdvertiseData.Builder()
      .setIncludeDeviceName(options.includeDeviceName)
      .setIncludeTxPowerLevel(options.includeTxPower)
      .build()

    val callback = object : AdvertiseCallback() {
      /** Is this callback still current, or has it been displaced/stopped? Only current callbacks may touch shared state. */
      private fun current(): Boolean = advertiseCallback.get() === this

      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        logDebug { "Advertising started successfully" }
        if (!current()) return
        advertising.set(true)
        scheduleAirtimeTimeout(options.timeoutMs)
        finishStart(this, null)
        // Re-check: stop may have raced the state changes above (current() is read, not claim).
        if (!current()) {
          advertising.set(false)
          cancelAirtimeTimeout()
        }
      }

      override fun onStartFailure(errorCode: Int) {
        // Claim callback: only owner can restore adapter name; displaced start owns it.
        if (!advertiseCallback.compareAndSet(this, null)) return
        advertising.set(false)
        val msg = when (errorCode) {
          ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "Advertise data too large — the advertisement and the scan response are each limited " +
              "to 31 bytes, which the service UUIDs, manufacturer data and service data share"
          ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
          ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started"
          ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
          ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
          else -> "Advertising failed (error $errorCode)"
        }
        Log.e(TAG, "Advertising failed: $msg")
        // Undo rename; the start never reached the air.
        restoreAdapterName()
        finishStart(this, GattServerException("ERR_ADVERTISE", msg))
      }
    }

    // Callback swapped first (it's what current() tests). Guarded from both the swap and the start.
    val start = PendingStart(callback, onResult)
    try {
      val displaced = advertiseCallback.getAndSet(callback)
      pendingStart.getAndSet(start)
        ?.onResult?.invoke(GattServerException("ERR_ADVERTISE", "Advertising restarted"))
      // Platform keys by callback identity; the displaced one must be stopped to free the slot.
      displaced?.let { leAdvertiser.stopAdvertising(it) }
      // Only cancel if still owner; concurrent starts can displace while in begin().
      if (advertiseCallback.get() === callback) {
        cancelAirtimeTimeout()
      }
      // Armed before the start so a callback delivered immediately still finds it. The expiry re-checks
      // the start's identity.
      armStartTimeout(start)
      leAdvertiser.startAdvertising(settings, advData.build(), scanResponse, callback)
    } catch (e: Exception) {
      // `startAdvertising` rechecks the adapter state itself and throws if it went off. The caller reports
      // that throw, so neither the completion nor the callback may be left installed for a later stop to
      // settle and stop a second time. compareAndSet, so a concurrent restart's own state is left alone.
      val ours = pendingStart.compareAndSet(start, null)
      // Inert once the completion it watched is gone, but cancelled so it does not sit on the looper.
      if (ours) {
        cancelStartTimeout()
      }
      if (advertiseCallback.compareAndSet(callback, null)) {
        // Nothing this call started is on the air and no AdvertiseCallback is coming to say so. Left
        // set, `isAdvertising` would report an advertisement that is not running until the
        // adapter-state receiver happened to clear it.
        advertising.set(false)
        // Nothing reached the air, so the rename this start applied has nothing left to justify it — the
        // same reason `onStartFailure` restores it. Without this, a start that threw because the adapter
        // went off between the check above and the call left the phone named after the application for
        // good, visible in Settings and to every peer.
        //
        // Inside the ownership test, because a start this one has already been displaced by owns both
        // the radio and the name now: restoring unconditionally put the phone back to its original name
        // while the advertisement that had just renamed it was still on the air.
        restoreAdapterName()
      }
      // Swallowed rather than reported when a stop settled this call first: settling it twice throws.
      if (!ours) {
        Log.w(TAG, "Advertising start failed after the call had already been settled", e)
        return
      }
      throw e
    }
    // The callback has to be installed before the start, because it is the only handle the platform accepts
    // for stopping and no lock may be held across the binder call — so a stop that landed during the start
    // took it, and is honoured here instead of leaving the radio advertising with nothing able to stop it.
    // The generation is tested too, because a stop that landed before the callback was installed left
    // nothing for the identity test to find.
    if (advertiseCallback.get() !== callback || generation.get() != claimed) {
      logDebug { "Advertising was stopped while starting — stopping the new advertisement" }
      leAdvertiser.stopAdvertising(callback)
      // Taking the callback back is what stops a late onStartSuccess reporting this advertisement as
      // running; a stop that took it first has already cleared the flag.
      if (advertiseCallback.compareAndSet(callback, null)) {
        advertising.set(false)
        // The stop's own restore was a no-op if it ran before this call applied the rename, because
        // there was no original name recorded yet to put back. Repeated here for that ordering; it
        // no-ops when the stop did reach it, since nothing is recorded any more.
        //
        // Inside the ownership claim, for the reason the catch block above gives: this branch is also
        // reached when a *newer start* displaced this one, and that start owns both the radio and the
        // name. Restoring unconditionally put the phone back to its original name while the
        // advertisement that had just renamed it was still on the air, with nothing left to restore it.
        restoreAdapterName()
      }
      // That stop may have settled the *previous* completion, if it landed before this one was
      // installed, so this call is settled here rather than left pending for good.
      if (pendingStart.compareAndSet(start, null)) {
        onResult(advertisingStopped())
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun stop() {
    // Bump generation so waiting starts know stop was requested.
    generation.incrementAndGet()
    // Claim callback first (it's what current() tests); clearing `advertising` first let onStartSuccess
    // re-arm a stopped advertisement.
    val callback = advertiseCallback.getAndSet(null)
    cancelAirtimeTimeout()
    advertising.set(false)
    callback?.let { advertiser.get()?.stopAdvertising(it) }
    finishStart(advertisingStopped())
    restoreAdapterName()
  }

  /** The adapter taking the stack down stops advertising without any `AdvertiseCallback`. */
  fun handleAdapterOff() {
    advertising.set(false)
    cancelAirtimeTimeout()
    advertiseCallback.set(null)
    advertiser.set(null)
    // Past tense, unlike [BLUETOOTH_OFF_MESSAGE]: this reports the event that ended a call already in
    // flight, not the state a new call is being refused for.
    finishStart(GattServerException("ERR_BLUETOOTH", "Bluetooth was turned off"))
  }

  private fun advertisingStopped() = GattServerException("ERR_ADVERTISE", "Advertising stopped")

  /** Settles the outstanding start once, unconditionally. */
  private fun finishStart(error: GattServerException?) {
    cancelStartTimeout()
    pendingStart.getAndSet(null)?.onResult?.invoke(error)
  }

  /** Settles the outstanding start only if it still belongs to [callback]; displaced starts are ignored. */
  private fun finishStart(callback: AdvertiseCallback, error: GattServerException?) {
    val start = pendingStart.get() ?: return
    if (start.callback !== callback) return
    if (!pendingStart.compareAndSet(start, null)) return
    cancelStartTimeout()
    start.onResult(error)
  }

  /**
   * Bounds the wait for the start callback. The expiry both settles the promise and stops the radio,
   * which cannot be separated: nothing else would know whether anything reached the air.
   */
  @SuppressLint("MissingPermission")
  private fun armStartTimeout(start: PendingStart) {
    val callback = start.callback
    val expiry = Runnable {
      if (pendingStart.get() !== start) return@Runnable
      Log.e(
        TAG,
        "No AdvertiseCallback within $ADVERTISING_START_TIMEOUT_MS ms; reporting the start as failed"
      )
      if (advertiseCallback.compareAndSet(callback, null)) {
        advertising.set(false)
        advertiser.get()?.stopAdvertising(callback)
        // Nothing is known to have reached the air, so a rename this start applied has nothing left to
        // justify it — the same reason `onStartFailure` restores it.
        restoreAdapterName()
      }
      finishStart(
        callback,
        GattServerException(
          "ERR_ADVERTISE",
          "The Bluetooth stack did not report the advertisement as started or failed within " +
            "$ADVERTISING_START_TIMEOUT_MS ms. Nothing is advertising."
        )
      )
    }
    while (true) {
      val current = startTimeout.get()
      // The installed bound belongs to a start that still owns the radio, which means this one has been
      // displaced: replacing it would leave the live start with no bound at all, which is the hang
      // ADVERTISING_START_TIMEOUT_MS exists to prevent. The displaced start is stopped by the tail of
      // `begin`, so it needs no bound of its own.
      if (current != null && current.first !== callback && advertiseCallback.get() === current.first) {
        return
      }
      if (startTimeout.compareAndSet(current, callback to expiry)) {
        current?.let { timeoutHandler.removeCallbacks(it.second) }
        timeoutHandler.postDelayed(expiry, ADVERTISING_START_TIMEOUT_MS)
        return
      }
    }
  }

  private fun cancelStartTimeout() {
    startTimeout.getAndSet(null)?.let { timeoutHandler.removeCallbacks(it.second) }
  }

  /**
   * `AdvertiseSettings.setTimeout` stops advertising at the limit without invoking `AdvertiseCallback`,
   * so [advertising] would otherwise stay set for the rest of the process. Only the flag is cleared —
   * the platform has already stopped the advertisement itself.
   */
  private fun scheduleAirtimeTimeout(timeoutMs: Int) {
    if (timeoutMs <= 0) return
    // The name goes back with the advertisement it was applied for. The platform stops advertising at
    // this limit without reporting it, so nothing else runs here — and an `android.setAdapterName` start
    // that carried a `timeoutMs` used to leave the phone's system-wide Bluetooth name changed for good,
    // with nothing on the air to justify it. iOS's equivalent expiry already goes through its own
    // `stopAdvertising` for the same reason.
    val expiry = Runnable {
      advertising.set(false)
      restoreAdapterName()
    }
    airtimeTimeout.set(expiry)
    timeoutHandler.postDelayed(expiry, timeoutMs.toLong())
  }

  private fun cancelAirtimeTimeout() {
    airtimeTimeout.getAndSet(null)?.let { timeoutHandler.removeCallbacks(it) }
  }

  /**
   * `BluetoothAdapter.setName` changes the device's system-wide Bluetooth name, not this
   * advertisement's — it is visible in the phone's own Bluetooth settings and to every peer, over
   * Classic as well as LE. Only ever called when the consumer explicitly asked for it, and undone by
   * [restoreAdapterName].
   */
  @SuppressLint("MissingPermission")
  private fun applyAdapterName(adapter: BluetoothAdapter, name: String) {
    // compareAndSet, so repeatedly restarting advertising still restores the device's own name rather
    // than the previous advertisement's.
    val previous = adapter.name
    if (previous == null) {
      Log.w(TAG, "The current adapter name is unavailable, so it cannot be restored later")
    } else {
      originalAdapterName.compareAndSet(null, previous)
    }
    if (!adapter.setName(name)) {
      Log.w(TAG, "Could not set the adapter name to \"$name\"")
    }
  }

  @SuppressLint("MissingPermission")
  fun restoreAdapterName() {
    val previous = originalAdapterName.get() ?: return
    val adapter = adapter ?: return
    if (adapter.setName(previous)) {
      originalAdapterName.compareAndSet(previous, null)
    } else {
      // Retained for a later attempt: `setName` fails while the adapter is off, which is exactly when a
      // teardown is most likely to run.
      Log.w(TAG, "Could not restore the adapter name to \"$previous\" yet")
    }
  }
}
