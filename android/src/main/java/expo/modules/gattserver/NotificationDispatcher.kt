package expo.modules.gattserver

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Upper bound on notifications waiting behind the one the platform is still delivering. Only one may be
 * outstanding per the `onNotificationSent` contract, so without a bound a producer that outruns the
 * link would grow the queue forever.
 */
private const val MAX_QUEUED_NOTIFICATIONS_PER_DEVICE = 64

/** Timeout for onNotificationSent callback. Must exceed ATT transaction timeout (Core Spec Vol 3, Part F, §3.3.3) to avoid race with stack's own timeout. */
private const val NOTIFICATION_TIMEOUT_MS = 35_000L

/** How long to wait before re-offering an entry the stack refused as busy. */
private const val NOTIFICATION_BUSY_RETRY_MS = 50L

internal class QueuedNotification(
  val device: BluetoothDevice,
  val characteristic: BluetoothGattCharacteristic,
  val characteristicUuid: String,
  val confirm: Boolean,
  val value: ByteArray,
  val onResult: (GattServerException?) -> Unit,
) {
  /**
   * Uptime milliseconds at which to stop re-offering this entry to a stack that keeps refusing it as
   * busy. Zero until the first refusal. Read and written under the queue's monitor.
   */
  var busyDeadline = 0L
}

/** Every field is read and written under the instance's own monitor. */
private class NotificationQueue {
  val waiting = ArrayDeque<QueuedNotification>()
  var inFlight: QueuedNotification? = null

  /** Callbacks owed for abandoned sends. onNotificationSent names only device; spend excess to prevent misattribution. */
  var callbacksOwedToAbandonedSends = 0
}

/**
 * One send queue per device, because Android allows a single outstanding notification per link: anything
 * offered while an earlier one is still in flight waits its turn rather than being discarded.
 *
 * Everything that touches the Bluetooth stack is left to the caller. [dispatch] hands one entry over and
 * reports `null` once the stack has accepted it — and only then is an `onNotificationSent` expected.
 * [handler] supplies the looper the retries and expiries run on, which must not be the binder thread.
 */
internal class NotificationDispatcher(
  private val dispatch: (deviceId: String, entry: QueuedNotification) -> GattServerException?,
  private val handler: () -> Handler,
) {
  private val queues = ConcurrentHashMap<String, NotificationQueue>()

  /**
   * Queues one entry for [deviceId] and starts it moving. Throws only when the device's queue is already
   * at its bound, which is the one refusal detectable before the send is accepted.
   *
   * [stillConnected] is consulted after the enqueue rather than before, closing the window a teardown
   * running between the two would open.
   */
  fun enqueue(deviceId: String, entry: QueuedNotification, stillConnected: () -> Boolean) {
    // `computeIfAbsent`, not `getOrPut`: the latter resolves to a plain `get() ?: put()` here and two
    // racing first sends would each build a queue, one of them orphaned with an entry in it.
    val queue = queues.computeIfAbsent(deviceId) { NotificationQueue() }
    synchronized(queue) {
      if (queue.waiting.size >= MAX_QUEUED_NOTIFICATIONS_PER_DEVICE) {
        throw GattServerException(
          "ERR_NOTIFY_QUEUE_FULL",
          "Device $deviceId already has $MAX_QUEUED_NOTIFICATIONS_PER_DEVICE notifications " +
            "waiting to be sent. Wait for earlier sends to resolve before queueing more."
        )
      }
      queue.waiting.addLast(entry)
    }
    // A teardown between the caller's connection check and the enqueue can leave this entry in a queue
    // nothing drains: the device may be gone, or it may have disconnected and reconnected, which detaches
    // this queue and registers a new one. A detached queue is never re-registered, so identity tells the
    // two apart.
    val detached = queues[deviceId] !== queue
    val disconnected = !stillConnected()
    if (detached || disconnected) {
      val error = GattServerException("ERR_DEVICE_DISCONNECTED", "Device $deviceId disconnected")
      // Only when the device itself is gone: a queue the central has reconnected behind holds sends that
      // are still good.
      if (disconnected) failFor(deviceId, error)
      // Settled here either way, since the pump cannot reach a queue that is no longer the registered one.
      if (take(queue, entry)) entry.onResult(error)
      return
    }
    pump(deviceId)
  }

  /**
   * Hands the next queued notification to the stack if the device's single outstanding slot is free.
   * Entries the stack refuses outright never produce a callback, so they are completed here and the loop
   * moves on to the next one.
   */
  fun pump(deviceId: String) {
    val queue = queues[deviceId] ?: return
    while (true) {
      val next = synchronized(queue) {
        if (queue.inFlight != null) return
        val candidate = queue.waiting.removeFirstOrNull() ?: return
        queue.inFlight = candidate
        candidate
      }
      // Armed only once the stack has accepted the send: the bound exists for a callback that never
      // arrives, and a dispatch that fails outright settles the entry below instead.
      val error = dispatch(deviceId, next)
      if (error == null) {
        armTimeout(deviceId, queue, next)
        return
      }
      // A busy stack refused the offer, not the entry, so it goes back where it was. The loop stops too:
      // the next entry would be refused for the same reason.
      if (error is NotifyBusyException && repark(deviceId, queue, next)) return
      // Only the thread that still owns the entry may settle it: a disconnect or a stop can take it
      // during the dispatch, and settling twice throws.
      val stillOurs = synchronized(queue) {
        if (queue.inFlight !== next) {
          false
        } else {
          queue.inFlight = null
          true
        }
      }
      if (!stillOurs) return
      next.onResult(error)
    }
  }

  /**
   * Settles the send `onNotificationSent` reports, then pumps the queue. [report] is handed the finished
   * entry's characteristic, which the platform callback itself does not name.
   */
  fun onSent(deviceId: String, status: Int, report: (characteristicUuid: String, status: Int) -> Unit) {
    val queue = queues[deviceId]
    var spentOwed = false
    val finished = queue?.let {
      synchronized(it) {
        // Spend owed callbacks first to avoid misattributing an abandoned send's callback to a new send.
        if (it.callbacksOwedToAbandonedSends > 0) {
          it.callbacksOwedToAbandonedSends -= 1
          spentOwed = true
          null
        } else {
          val entry = it.inFlight
          it.inFlight = null
          entry
        }
      }
    }
    if (spentOwed) {
      Log.w(TAG, "onNotificationSent: late callback for an abandoned send to device=$deviceId (status $status)")
    } else if (finished != null) {
      report(finished.characteristicUuid, status)
      finished.onResult(
        if (status != BluetoothGatt.GATT_SUCCESS) {
          GattServerException(
            "ERR_NOTIFY",
            "Notification for ${finished.characteristicUuid} was not delivered (status $status)"
          )
        } else {
          null
        }
      )
    } else {
      // Queue torn down by disconnect or stop; characteristic unknown.
      Log.w(TAG, "onNotificationSent: no in-flight notification for device=$deviceId status=$status")
    }
    pump(deviceId)
  }

  /** Detaches [deviceId]'s queue and settles everything in it. */
  fun failFor(deviceId: String, error: GattServerException) {
    val queue = queues.remove(deviceId) ?: return
    val abandoned = synchronized(queue) {
      val all = ArrayList<QueuedNotification>()
      queue.inFlight?.let { all.add(it) }
      queue.inFlight = null
      all.addAll(queue.waiting)
      queue.waiting.clear()
      all
    }
    abandoned.forEach { it.onResult(error) }
  }

  fun failAll(error: GattServerException) {
    queues.keys.toList().forEach { failFor(it, error) }
  }

  /**
   * Puts an entry the stack refused as busy back at the head of its queue and schedules another attempt,
   * reporting whether it did.
   *
   * `false` means the caller must settle it instead: either the retry budget is spent — bounded by
   * [NOTIFICATION_TIMEOUT_MS], so a stack that never frees up fails the send rather than retrying
   * forever — or something else has already taken the entry.
   */
  private fun repark(deviceId: String, queue: NotificationQueue, entry: QueuedNotification): Boolean {
    val now = SystemClock.uptimeMillis()
    synchronized(queue) {
      if (queue.inFlight !== entry) return false
      if (entry.busyDeadline == 0L) {
        entry.busyDeadline = now + NOTIFICATION_TIMEOUT_MS
      }
      if (now >= entry.busyDeadline) {
        Log.w(TAG, "The stack has been busy for $NOTIFICATION_TIMEOUT_MS ms; failing the send to $deviceId")
        return false
      }
      queue.inFlight = null
      queue.waiting.addFirst(entry)
    }
    // Not the main looper: the retry re-enters the binder, and a stalled central retries it every 50 ms
    // for 35 s.
    handler().postDelayed({ pump(deviceId) }, NOTIFICATION_BUSY_RETRY_MS)
    return true
  }

  /**
   * Bounds the wait for one entry's `onNotificationSent`.
   *
   * The timer names the entry it was armed for, so it needs no cancelling: one that fires after the
   * callback arrived finds the entry gone and does nothing. No path that clears `inFlight` — the
   * callback, a disconnect, a power cycle, `stop` — has to remember to cancel it.
   */
  private fun armTimeout(deviceId: String, queue: NotificationQueue, entry: QueuedNotification) {
    // Off the main looper for the same reason as [repark]: this ends by pumping the queue, which
    // re-enters the binder.
    handler().postDelayed({
      val abandoned = synchronized(queue) {
        if (queue.inFlight === entry) {
          queue.inFlight = null
          // Only this branch: the stack accepted this send and still owes a callback for it. An entry
          // still waiting was never handed over.
          queue.callbacksOwedToAbandonedSends += 1
          true
        } else {
          queue.waiting.remove(entry)
        }
      }
      if (!abandoned) return@postDelayed
      Log.w(TAG, "No onNotificationSent for $deviceId within $NOTIFICATION_TIMEOUT_MS ms; failing the send")
      entry.onResult(
        GattServerException(
          "ERR_NOTIFY",
          "The Bluetooth stack accepted the notification but never reported it as sent within " +
            "$NOTIFICATION_TIMEOUT_MS ms. The send is abandoned so the queue for this device can " +
            "continue."
        )
      )
      pump(deviceId)
    }, NOTIFICATION_TIMEOUT_MS)
  }

  /**
   * Removes [entry] from [queue] if it is still there, reporting whether this call is the one that took
   * it, so a concurrent drain cannot settle it twice.
   */
  private fun take(queue: NotificationQueue, entry: QueuedNotification): Boolean =
    synchronized(queue) {
      if (queue.inFlight === entry) {
        queue.inFlight = null
        true
      } else {
        queue.waiting.remove(entry)
      }
    }
}
