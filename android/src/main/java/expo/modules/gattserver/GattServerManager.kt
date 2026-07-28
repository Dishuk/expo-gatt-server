package expo.modules.gattserver

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ExpoGattServer"

/** Emits debug logs only when enabled via `adb shell setprop log.tag.ExpoGattServer DEBUG`. */
private inline fun logDebug(message: () -> String) {
  if (Log.isLoggable(TAG, Log.DEBUG)) {
    Log.d(TAG, message())
  }
}

/** Default ATT_MTU, in octets — Core Spec Vol 3, Part G, §5.2.1. */
const val DEFAULT_ATT_MTU = 23

/**
 * Octets an `ATT_HANDLE_VALUE_NTF` / `ATT_HANDLE_VALUE_IND` PDU spends before the value: a one-octet
 * Attribute Opcode plus a two-octet Attribute Handle (Core Spec Vol 3, Part F, §§3.4.7.1–3.4.7.2).
 */
const val ATT_NOTIFICATION_HEADER_SIZE = 3

/** Max attribute value length — Core Spec Vol 3, Part F, §3.2.9. BluetoothGattServer.notifyCharacteristicChanged throws IllegalArgumentException above 512. */
const val MAX_ATTRIBUTE_VALUE_LENGTH = 512

/**
 * Upper bound on notifications waiting behind the one the platform is still delivering. Only one may be
 * outstanding per the `onNotificationSent` contract, so without a bound a producer that outruns the
 * link would grow the queue forever.
 */
private const val MAX_QUEUED_NOTIFICATIONS_PER_DEVICE = 64

/**
 * Client Characteristic Configuration descriptor — Core Spec Vol 3, Part G, §3.3.3.3. Its value is two
 * octets, little endian: bit 0 enables notifications and bit 1 indications (Table 3.11), defaulting to
 * 0x0000.
 */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
internal const val CCCD_VALUE_LENGTH = 2
internal const val CCCD_NOTIFY_BIT = 0x0001
internal const val CCCD_INDICATE_BIT = 0x0002

/**
 * Longest duration `AdvertiseSettings.Builder.setTimeout` accepts — "May not exceed 180000
 * milliseconds" — the Bluetooth SIG limit the platform names `LIMITED_ADVERTISING_MAX_MILLIS`.
 */
const val MAX_ADVERTISING_TIMEOUT_MS = 180_000

/** ATT "Invalid Attribute Value Length" — Core Spec Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0D

/** ATT "Prepare Queue Full" — Core Spec Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_PREPARE_QUEUE_FULL = 0x09

/**
 * Prepared writes one device may queue before an execute. The specification leaves the limit to "a
 * higher layer specification" (Core Spec Vol 3, Part F, §3.4.6.1) and answers an overrun with
 * [ATT_ERROR_PREPARE_QUEUE_FULL]. 64 covers a 512-octet attribute written in the smallest parts the
 * default ATT_MTU allows, with room to spare for a reliable write spanning several attributes.
 */
private const val MAX_PREPARED_WRITES_PER_DEVICE = 64

/** ATT "Unlikely Error" — Core Spec Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_UNLIKELY_ERROR = 0x0E

// Worded to match what iOS reports for the same two `CBManagerState` values, since both platforms
// report them under the same `ERR_BLUETOOTH` code.
private const val BLUETOOTH_UNSUPPORTED_MESSAGE = "BLE not supported on this device"
private const val BLUETOOTH_OFF_MESSAGE = "Bluetooth is turned off"

/**
 * The ATT transaction timeout. A transaction not completed within 30 s fails, and no further request,
 * command, indication or notification may then be sent on that ATT bearer — recovering costs a whole
 * new bearer (Core Spec Vol 3, Part F, §3.3.3). A module timeout at or above it could never answer
 * before the peer gives up, so it is the exclusive upper bound on the request timeout.
 */
const val ATT_TRANSACTION_TIMEOUT_MS = 30_000

/**
 * How long a request delegated to JavaScript may go unanswered before the module answers it itself.
 * Sits well inside [ATT_TRANSACTION_TIMEOUT_MS], leaving the peer 20 s of margin so it receives a real
 * ATT error response and its bearer stays usable, while still allowing a handler to do genuine
 * asynchronous work.
 */
const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000

/** Timeout for service registration. addService is async; callback never arriving parks the caller indefinitely without this bound. */
private const val PUBLICATION_TIMEOUT_MS = 30_000L

/** Timeout for advertising start. startAdvertising callback is not guaranteed to arrive; bounds unrecoverable hangs. */
private const val ADVERTISING_START_TIMEOUT_MS = 30_000L

/** Timeout for onNotificationSent callback. Must exceed ATT transaction timeout (Core Spec Vol 3, Part F, §3.3.3) to avoid race with stack's own timeout. */
private const val NOTIFICATION_TIMEOUT_MS = 35_000L

/**
 * How long to wait before offering the stack an entry it refused as busy again.
 *
 * Short, because the refusal means the previous send is still in flight rather than that anything is
 * wrong, and the entry is holding up its device's whole queue while it waits.
 */
private const val NOTIFICATION_BUSY_RETRY_MS = 50L

open class GattServerException(val code: String, message: String) : Exception(message)
class MtuException(code: String, message: String) : GattServerException(code, message)

/** ERROR_GATT_WRITE_REQUEST_BUSY: stack still sending previous notification. Transient; retry without settling as failure. */
class NotifyBusyException(message: String) : GattServerException("ERR_NOTIFY", message)

data class CharacteristicAddress(val service: UUID, val characteristic: UUID)

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
 * The link budget for one device. Android reports the ATT_MTU directly through `onMtuChanged`, so
 * [mtu] is exact and the payload capacity is derived from it.
 */
data class DeviceMtu(val mtu: Int) {
  /**
   * Octets that fit in one notification or indication: `ATT_MTU - 3`, bounded by what an attribute
   * value may hold. Reported to JavaScript so a payload can be sized before it is sent, so it has to
   * agree with what [mtuErrorFor] would accept — at the maximum ATT_MTU of 517 the arithmetic alone
   * would promise 514, and a payload sized to that is refused.
   */
  val maxNotificationPayload: Int =
    minOf(mtu - ATT_NOTIFICATION_HEADER_SIZE, MAX_ATTRIBUTE_VALUE_LENGTH)
}

/** Every flag defaults to `false`, which keeps the module answering the request itself. */
data class CharacteristicDelegation(
  val read: Boolean = false,
  val write: Boolean = false,
) {
  companion object {
    val none = CharacteristicDelegation()
  }
}

/**
 * Maps a [BluetoothAdapter] state constant onto the platform-neutral state union shared with iOS. The
 * two transitional states are reported as `resetting` because the platform documents both as not yet
 * usable, which is exactly what `resetting` means to a consumer.
 */
fun normalizedBluetoothState(state: Int): String = when (state) {
  BluetoothAdapter.STATE_ON -> "poweredOn"
  BluetoothAdapter.STATE_OFF -> "poweredOff"
  BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF -> "resetting"
  else -> "unknown"
}

/** Reads the adapter state without needing a server. `getState()` requires no runtime permission. */
fun currentBluetoothState(context: Context): String {
  val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  val adapter = manager?.adapter ?: return "unsupported"
  return normalizedBluetoothState(adapter.state)
}

/**
 * `MissingPermission` is suppressed per function rather than for the whole class: the permissions are
 * checked in [ExpoGattServerModule] before anything here is reachable, but a class-level suppression
 * also hid every *new* violation, including the module's own broken check.
 */
class GattServerManager(
  private val context: Context,
  private val requestTimeoutMs: Int = DEFAULT_REQUEST_TIMEOUT_MS,
) {
  interface Listener {
    fun onDeviceConnected(deviceId: String, name: String?)
    fun onDeviceDisconnected(deviceId: String)
    fun onCharacteristicReadRequest(
      deviceId: String, requestId: Int, serviceUuid: String,
      characteristicUuid: String, offset: Int
    )
    fun onCharacteristicWriteRequest(
      deviceId: String, requestId: Int, serviceUuid: String,
      characteristicUuid: String, offset: Int, value: ByteArray, responseNeeded: Boolean
    )
    fun onNotificationSent(deviceId: String, characteristicUuid: String, status: Int)
    fun onMtuChanged(deviceId: String, mtu: DeviceMtu)
    fun onCharacteristicSubscribed(deviceId: String, serviceUuid: String, characteristicUuid: String)
    fun onCharacteristicUnsubscribed(deviceId: String, serviceUuid: String, characteristicUuid: String)

    /**
     * The published database went away for a reason no promise is waiting to report. See
     * [reportPublicationFailure].
     */
    fun onServerPublicationFailed(code: String, message: String)
  }

  // Written from JS thread, read from binder/main threads: visibility required.
  @Volatile
  var listener: Listener? = null

  // Use as? to safely handle devices with no Bluetooth and avoid ClassCastException in constructor.
  private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  @Volatile
  private var gattServer: BluetoothGattServer? = null
  // Callbacks posted to main looper; claimed atomically to prevent double-settling Promise.
  private val advertiser = AtomicReference<BluetoothLeAdvertiser?>(null)
  private val advertiseCallback = AtomicReference<AdvertiseCallback?>(null)
  // Pairs callback with result to prevent stale callback from settling wrong completion.
  private val pendingAdvertiseResult = AtomicReference<PendingAdvertiseStart?>(null)

  // Bumped on stop to detect race: start knows if stop requested while waiting for database.
  private val advertisingGeneration = AtomicInteger(0)

  // Thread-safe state read from caller, written from binder threads.
  private val advertising = AtomicBoolean(false)
  private val advertisingTimeout = AtomicReference<Runnable?>(null)
  // Timeout for start, paired with callback to prevent stale timeout from evicting replacement.
  private val advertisingStartTimeout = AtomicReference<Pair<AdvertiseCallback, Runnable>?>(null)
  // Set from the caller's thread, read again during a teardown that may be on another.
  private val originalAdapterName = AtomicReference<String?>(null)
  private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
  private val deviceMtu = ConcurrentHashMap<String, Int>()
  private val pendingRequests = ConcurrentHashMap<RequestKey, PendingRequest>()
  // Expiry tasks are posted here from the binder threads that register the requests, and run on the main
  // looper, which always exists for the lifetime of the process.
  private val timeoutHandler = Handler(Looper.getMainLooper())

  /**
   * The looper the server's own lifecycle work runs on: the adapter-state broadcasts, and the release of
   * everyone parked in [whenDatabasePublished].
   *
   * Both do binder work — `openGattServer`, `addService`, `close`, `setName`, `startAdvertising` — and
   * both first block on [serverLifecycleLock], which `open` and `stop` hold across binder calls of their
   * own. Run on the main thread, as they were, a slow Bluetooth process turned an adapter toggle into an
   * ANR: a `BroadcastReceiver` has around ten seconds before one, and the parked advertising callers
   * released behind it are doing the same kind of work. Created with the receiver and quit with it, so
   * its lifetime cannot outlast the server it serves.
   */
  @Volatile
  private var lifecycleThread: HandlerThread? = null
  // Android: one notification at a time. Queue per device, touched from caller and binder threads.
  private val notificationQueues = ConcurrentHashMap<String, NotificationQueue>()

  // Per-device queue — Core Spec Vol 3, Part F, §3.4.6.1. Bin lock synchronizes binder threads.
  private val preparedWrites = ConcurrentHashMap<String, MutableList<PreparedWrite>>()

  /**
   * Identifies one pending request. The device is part of the key because Android's `requestId` is the raw
   * ATT transaction id, which AOSP assigns from a counter on the per-connection transport control block
   * (`p_cmd->trans_id = ++tcb.trans_id` in `system/stack/gatt/gatt_sr.cc`) and `BluetoothGattServer` passes
   * through untouched — so two connected centrals both produce 1, 2, 3 and would otherwise collide.
   */
  private data class RequestKey(val deviceId: String, val requestId: Int)

  /** A start still waiting for its `AdvertiseCallback`. See [pendingAdvertiseResult]. */
  private class PendingAdvertiseStart(
    val callback: AdvertiseCallback,
    val onResult: (GattServerException?) -> Unit,
  )

  /**
   * One value a partially delegated execute withheld, kept with what the attribute held when the execute
   * was assembled — the only thing a later commit can tell a stale value apart by.
   */
  private class DeferredWrite(
    val value: ByteArray,
    /** `null` when the attribute had no value at all, which is distinct from an empty one. */
    val baseline: ByteArray?,
  )

  /**
   * A request awaiting `sendResponse`. [offset] is the offset the central asked for, retained so a
   * response can be rebased onto it and so the offset handed back to the stack is the one the request
   * carried rather than whatever the caller happened to pass.
   */
  private class PendingRequest(
    val offset: Int,
    val isRead: Boolean,
    /**
     * What a partially delegated execute assembled for the characteristics that did *not* opt in,
     * withheld until the batch is accepted. The queued-write procedure is atomic, so half of it must not
     * be committed while JavaScript may still reject the rest.
     */
    val deferredValues: Map<BluetoothGattCharacteristic, DeferredWrite> = emptyMap(),
    /** CCCD changes withheld until batch accepted to maintain queued-write atomicity. */
    val clientConfigurations: List<Pair<BluetoothGattDescriptor, Int>> = emptyList(),
    /** Non-CCCD descriptor values withheld until batch accepted to maintain queued-write atomicity. */
    val deferredDescriptors: Map<BluetoothGattDescriptor, DeferredWrite> = emptyMap(),
  ) {
    // Assigned after construction; volatile for visibility across binder, main, and caller threads.
    @Volatile
    var timeout: Runnable? = null
  }

  /**
   * One `ATT_PREPARE_WRITE_REQ` held until its execute arrives. The attribute must not change until the
   * execute, and repeats of the same handle are executed in the order received rather than replacing one
   * another (Core Spec Vol 3, Part F, §3.4.6.1).
   */
  private sealed class PreparedWrite {
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

  private class QueuedNotification(
    val device: BluetoothDevice,
    val characteristic: BluetoothGattCharacteristic,
    val characteristicUuid: String,
    val confirm: Boolean,
    val value: ByteArray,
    val onResult: (GattServerException?) -> Unit,
  ) {
    /**
     * When to stop offering this entry to a stack that keeps refusing it as busy, as an uptime
     * milliseconds reading. Zero until the first refusal. Read and written under the queue's monitor.
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

  // Per-client CCCD — Core Spec Vol 3, Part G, §3.3.3.3. Keyed by device and characteristic address (not UUID alone) to avoid collisions.
  private val subscriptions =
    ConcurrentHashMap<String, ConcurrentHashMap<CharacteristicAddress, Int>>()

  // Framework fields (value) are non-volatile and unsynchronized. Monitor all access: reads included, both for ordering and atomicity of read-modify-write.
  private val attributeValueLock = Any()

  // Fixed for server lifetime; read from binder threads. Concurrent for thread-safety.
  private val delegations = ConcurrentHashMap<CharacteristicAddress, CharacteristicDelegation>()
  // Fallback keyed by UUID alone; only for UUIDs occurring once, ensuring no ambiguity.
  private val delegationsByCharacteristic = ConcurrentHashMap<UUID, CharacteristicDelegation>()

  // addService is async and must be done one at a time. Touched from caller and binder threads.
  private val pendingServices = ConcurrentLinkedQueue<BluetoothGattService>()
  private val openCompletion = AtomicReference<((GattServerException?) -> Unit)?>(null)

  /**
   * How far the current round of `addService` calls has got. The adapter being disabled closes the
   * server and discards its database, so this drops back to [IDLE] there and only reaches [PUBLISHED]
   * once the re-registration that follows the next power-on is acknowledged.
   */
  private enum class DatabasePublication {
    /** Nothing is published and a registration round is still expected. */
    IDLE,
    IN_PROGRESS,
    PUBLISHED,
    FAILED,
  }

  // Serializes state transitions and parked caller notifications to prevent double-release.
  private val publicationLock = Any()

  /** Serializes open/stop/handleAdapterOn/handleAdapterOff. open, handleAdapterOn (main), stop (JS) run concurrently without this. One-way ordering: this -> publicationLock. */
  private val serverLifecycleLock = Any()

  /** Set once stop() runs; never cleared (manager rebuilt for each createServer). Prevents orphaned server. */
  private var stopped = false
  private var publication = DatabasePublication.IDLE
  private val readinessWaiters = mutableListOf<(GattServerException?) -> Unit>()

  /** Identifies the current registration round, so a bound armed for one cannot fail another. */
  private val publicationRound = AtomicInteger(0)

  /** The bound on the current round. See [PUBLICATION_TIMEOUT_MS]. */
  private val publicationTimeout = AtomicReference<Runnable?>(null)

  // Factory builds fresh instances for each registration pass; reusing IDs is not documented as supported.
  private val serviceFactory = AtomicReference<(() -> List<BluetoothGattService>)?>(null)

  // Latest round's instances, retained past server closure to preserve values for next round.
  private val publishedServices = AtomicReference<List<BluetoothGattService>>(emptyList())

  /**
   * Call before [open]. The configuration is retained across the server rebuilds that an adapter power
   * cycle triggers.
   */
  fun setDelegations(map: Map<CharacteristicAddress, CharacteristicDelegation>) {
    delegations.clear()
    delegationsByCharacteristic.clear()
    delegations.putAll(map)
    val occurrences = map.keys.groupingBy { it.characteristic }.eachCount()
    for ((address, delegation) in map) {
      if (occurrences[address.characteristic] == 1) {
        delegationsByCharacteristic[address.characteristic] = delegation
      }
    }
  }

  /** See [resolveDelegation] for why the UUID-only map is a last resort rather than a fallback. */
  private fun delegationFor(characteristic: BluetoothGattCharacteristic): CharacteristicDelegation =
    resolveDelegation(
      addressOf(characteristic), characteristic.uuid, delegations, delegationsByCharacteristic
    )

  /**
   * The service-and-characteristic address of a characteristic the framework handed back, or `null` when
   * the published database cannot name its owner.
   *
   * `getService()` is set for every characteristic reached through a registered service, so the search
   * below is only ever a fallback — but the field is plain mutable state the framework owns, and an
   * address guessed from the UUID alone is exactly what the per-service keying exists to avoid. Matching
   * the instance the database actually holds settles it without guessing.
   */
  private fun addressOf(characteristic: BluetoothGattCharacteristic): CharacteristicAddress? {
    characteristic.service?.uuid?.let { return CharacteristicAddress(it, characteristic.uuid) }
    val owner = gattServer?.services?.firstOrNull { service ->
      service.characteristics.any { it === characteristic }
    } ?: return null
    return CharacteristicAddress(owner.uuid, characteristic.uuid)
  }

  /** Invoked for every adapter state change. Written from JS thread, read from main thread. */
  @Volatile
  var onStateChange: ((String) -> Unit)? = null

  private val stateReceiverRegistered = AtomicBoolean(false)

  private val stateReceiver = object : BroadcastReceiver() {
    override fun onReceive(receiverContext: Context?, intent: Intent?) {
      if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
      val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
      logDebug { "Adapter state changed to $state" }
      onStateChange?.invoke(normalizedBluetoothState(state))

      when (state) {
        BluetoothAdapter.STATE_OFF -> handleAdapterOff()
        BluetoothAdapter.STATE_ON -> handleAdapterOn()
      }
    }
  }

  /** Adapter teardown invalidates server; explicit close ensures clean restart on power-on. */
  @SuppressLint("MissingPermission")
  private fun handleAdapterOff(): Unit = synchronized(serverLifecycleLock) {
    logDebug { "Adapter off — closing GATT server" }
    discardPublicationRound()
    // IDLE rather than FAILED: the next power-on re-registers the services, so a caller arriving in the
    // window between the STATE_ON broadcast and that round has to park rather than be turned away.
    finishOpen(DatabasePublication.IDLE, GattServerException(
      "ERR_BLUETOOTH", "Bluetooth was turned off before the server finished opening"
    ))

    // The adapter taking the stack down stops advertising without any AdvertiseCallback.
    advertising.set(false)
    cancelAdvertisingTimeout()
    advertiseCallback.set(null)
    advertiser.set(null)
    finishAdvertise(GattServerException("ERR_BLUETOOTH", "Bluetooth was turned off"))

    gattServer?.close()
    gattServer = null

    val disconnected = connectedDevices.keys.toList()
    connectedDevices.clear()
    deviceMtu.clear()
    discardPendingRequests { true }
    preparedWrites.clear()
    failAllNotifications(GattServerException("ERR_BLUETOOTH", "Bluetooth was turned off"))
    // The server is gone, so no onConnectionStateChange callback will arrive for any of these.
    disconnected.forEach {
      clearSubscriptions(it)
      listener?.onDeviceDisconnected(it)
    }
    subscriptions.clear()
  }

  private fun handleAdapterOn(): Unit = synchronized(serverLifecycleLock) {
    // Exceptions here are fatal (no uncaught handler); guard before arming publication timeout.
    try {
      // Undo name change before reopening; setName fails while adapter is off.
      restoreAdapterName()
      if (serviceFactory.get() == null) return
      logDebug { "Adapter on — reopening GATT server and re-registering services" }
      if (!openServer()) {
        Log.e(TAG, "Failed to reopen GATT server after the adapter was re-enabled")
        // No retries until next cycle; inform parked callers.
        finishOpen(
          DatabasePublication.FAILED,
          GattServerException(
            "ERR_CREATE_SERVER", "Could not reopen the GATT server after Bluetooth was turned back on"
          )
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to reopen the GATT server after the adapter was re-enabled", e)
      finishOpen(
        DatabasePublication.FAILED,
        (e as? GattServerException)
          ?: GattServerException(
            "ERR_CREATE_SERVER", e.message ?: "Could not reopen the GATT server after Bluetooth was turned back on"
          )
      )
    }
  }

  private fun registerStateReceiver() {
    if (!stateReceiverRegistered.compareAndSet(false, true)) return
    val thread = HandlerThread("ExpoGattServerLifecycle").apply { start() }
    lifecycleThread = thread
    // Four-argument overload delivers onReceive on thread's looper, not main thread.
    context.registerReceiver(
      stateReceiver,
      IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
      null,
      Handler(thread.looper),
    )
    // ACTION_STATE_CHANGED only announces changes, not initial state. Report initial state manually for consistency with iOS.
    val initial = currentBluetoothState(context)
    Handler(thread.looper).post { onStateChange?.invoke(initial) }
  }

  private fun unregisterStateReceiver() {
    if (!stateReceiverRegistered.compareAndSet(true, false)) return
    runCatching { context.unregisterReceiver(stateReceiver) }
      .onFailure { Log.w(TAG, "Failed to unregister adapter state receiver", it) }
    // quitSafely: allow pending broadcasts to finish before releasing serverLifecycleLock.
    lifecycleThread?.quitSafely()
    lifecycleThread = null
  }

  /**
   * Where deferred lifecycle work runs, falling back to the main looper once the lifecycle thread has
   * been quit — which is only after `stop`, when the work is a teardown that must still be delivered.
   */
  private fun lifecycleHandler(): Handler =
    lifecycleThread?.looper?.let { Handler(it) } ?: timeoutHandler

  /** One callback per round to identify stale onServiceAdded callbacks from closed servers. */
  private fun gattServerCallback(round: Int) = object : BluetoothGattServerCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      val id = device.address
      logDebug { "onConnectionStateChange: device=$id status=$status newState=$newState" }
      when (newState) {
        BluetoothGattServer.STATE_CONNECTED -> {
          connectedDevices[id] = device
          listener?.onDeviceConnected(id, device.name)
          // Link starts at spec default. onMtuChanged only arrives if central requests exchange. Report initial MTU for iOS parity.
          listener?.onMtuChanged(id, DeviceMtu(deviceMtu[id] ?: DEFAULT_ATT_MTU))
        }
        BluetoothGattServer.STATE_DISCONNECTED -> {
          connectedDevices.remove(id)
          deviceMtu.remove(id)
          discardPendingRequests { it.deviceId == id }
          // Bearer loss clears prepare queue without executing (Core Spec Vol 3, Part F, §3.4.6.1).
          preparedWrites.remove(id)
          // Fail queued notifications; no callback will arrive.
          failNotifications(id, GattServerException("ERR_DEVICE_DISCONNECTED", "Device $id disconnected"))
          clearSubscriptions(id)
          listener?.onDeviceDisconnected(id)
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicReadRequest(
      device: BluetoothDevice, requestId: Int, offset: Int,
      characteristic: BluetoothGattCharacteristic
    ) {
      val value = synchronized(attributeValueLock) {
        @Suppress("DEPRECATION")
        characteristic.value
      }
      // Delegated characteristic always reaches JS, regardless of current stored value.
      val delegated = delegationFor(characteristic).read

      if (!delegated && value != null) {
        // Offset past end → GATT_INVALID_OFFSET (Core Spec Vol 3, Part F, §3.4.1.1). Offset == length is valid, returns empty.
        val responseValue = readSliceAt(value, offset)
        if (responseValue == null) {
          Log.w(TAG, "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset past end of ${value.size}-byte value, rejecting")
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
          return
        }
        logDebug { "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset auto-respond valueLen=${responseValue.size}" }
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseValue)
        return
      }

      logDebug { "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset delegating to JS" }
      registerPendingRequest(requestId, device.address, offset, isRead = true)

      val serviceUuid = characteristic.service?.uuid?.toString() ?: ""
      listener?.onCharacteristicReadRequest(
        device.address, requestId, serviceUuid,
        characteristic.uuid.toString(), offset
      )
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
      preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
    ) {
      val serviceUuid = characteristic.service?.uuid?.toString() ?: ""
      val data = value ?: ByteArray(0)

      if (preparedWrite) {
        queuePreparedWrite(
          device, requestId,
          PreparedWrite.ToCharacteristic(characteristic, offset, data),
          responseNeeded
        )
        return
      }

      // Bound here for spec compliance, not cache management; delegated writes must also be refused.
      if (exceedsAttributeLength(data.size)) {
        Log.w(TAG, "onCharacteristicWriteRequest: ${data.size} octets, past the $MAX_ATTRIBUTE_VALUE_LENGTH-octet limit, rejecting")
        if (responseNeeded) {
          gattServer?.sendResponse(
            device, requestId, ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH, offset, null
          )
        }
        return
      }

      // Write-without-response cannot be delegated; no reply for JS to send.
      val delegatesWrite = delegationFor(characteristic).write
      val delegated = delegatesWrite && responseNeeded
      logDebug { "onCharacteristicWriteRequest: device=${device.address} char=${characteristic.uuid} offset=$offset responseNeeded=$responseNeeded delegated=$delegated" }

      if (delegated) {
        registerPendingRequest(requestId, device.address, offset, isRead = false)
      } else {
        // Store value for later read. Replaced (not spliced) per Core Spec Vol 3, Part F, §3.4.5.1.
        if (!delegatesWrite) {
          storeCharacteristicValue(characteristic, data)
        }
        if (responseNeeded) {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
        }
      }

      listener?.onCharacteristicWriteRequest(
        device.address, requestId, serviceUuid,
        characteristic.uuid.toString(), offset, data, delegated
      )
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWriteRequest(
      device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
    ) {
      // Log length only (not bytes) to avoid exposing descriptor values in traces.
      logDebug { "onDescriptorWriteRequest: device=${device.address} desc=${descriptor.uuid} responseNeeded=$responseNeeded valueLen=${value?.size ?: 0}" }

      if (preparedWrite) {
        queuePreparedWrite(
          device, requestId,
          PreparedWrite.ToDescriptor(descriptor, offset, value ?: ByteArray(0)),
          responseNeeded
        )
        return
      }

      if (value != null && exceedsAttributeLength(value.size)) {
        Log.w(TAG, "onDescriptorWriteRequest: ${value.size} octets, past the $MAX_ATTRIBUTE_VALUE_LENGTH-octet limit, rejecting")
        if (responseNeeded) {
          gattServer?.sendResponse(
            device, requestId, ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH, offset, null
          )
        }
        return
      }

      if (descriptor.uuid == CCCD_UUID) {
        // Spec fixes length at two octets; anything else is malformed.
        if (offset != 0 || value == null || value.size != CCCD_VALUE_LENGTH) {
          Log.w(TAG, "onDescriptorWriteRequest: rejecting malformed CCCD write from ${device.address}")
          if (responseNeeded) {
            gattServer?.sendResponse(
              device, requestId, ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH, offset, null
            )
          }
          return
        }
        applyClientConfiguration(device, descriptor.characteristic, cccdBits(value))
        if (responseNeeded) {
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }
        return
      }

      if (value != null) {
        synchronized(attributeValueLock) {
          @Suppress("DEPRECATION")
          descriptor.value = value
        }
      }
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
      }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorReadRequest(
      device: BluetoothDevice, requestId: Int, offset: Int,
      descriptor: BluetoothGattDescriptor
    ) {
      // CCCD per client (Core Spec Vol 3, Part G, §3.3.3.3). Unknown address reads as 0x0000 (default).
      val value = if (descriptor.uuid == CCCD_UUID) {
        val bits = addressOf(descriptor.characteristic)
          ?.let { clientConfiguration(device.address, it) } ?: 0
        cccdValue(bits)
      } else {
        synchronized(attributeValueLock) {
          @Suppress("DEPRECATION")
          descriptor.value
        } ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
      }
      // Bounds-check as characteristic reads do to handle multi-blob descriptors correctly.
      val responseValue = readSliceAt(value, offset)
      if (responseValue == null) {
        Log.w(TAG, "onDescriptorReadRequest: device=${device.address} desc=${descriptor.uuid} offset=$offset past end of ${value.size}-byte value, rejecting")
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        return
      }
      logDebug { "onDescriptorReadRequest: device=${device.address} desc=${descriptor.uuid} offset=$offset valueLen=${responseValue.size}" }
      gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseValue)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
      val deviceId = device.address

      // onNotificationSent reports only device; in-flight entry identifies characteristic.
      val queue = notificationQueues[deviceId]
      var spentOwed = false
      val finished = queue?.let {
        synchronized(it) {
          // Spend owed callbacks first to avoid misattributing abandoned send's callback to new send.
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
        listener?.onNotificationSent(deviceId, finished.characteristicUuid, status)
        val error = if (status != BluetoothGatt.GATT_SUCCESS) {
          GattServerException(
            "ERR_NOTIFY",
            "Notification for ${finished.characteristicUuid} was not delivered (status $status)"
          )
        } else {
          null
        }
        finished.onResult(error)
      } else {
        // Queue torn down by disconnect or stop; characteristic unknown.
        Log.w(TAG, "onNotificationSent: no in-flight notification for device=$deviceId status=$status")
      }
      pumpNotifications(deviceId)
    }

    /** Applies or discards prepared writes per execute flag. Always responds, even if queue was empty (Core Spec Vol 3, Part F, §3.4.6.3). */
    @SuppressLint("MissingPermission")
    override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
      val queued = preparedWrites.remove(device.address) ?: emptyList<PreparedWrite>()
      logDebug { "onExecuteWrite: device=${device.address} execute=$execute queued=${queued.size}" }

      if (!execute) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        return
      }
      applyPreparedWrites(device, requestId, queued)
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
      // Ignore callbacks from discarded rounds.
      if (publicationRound.get() != round) {
        logDebug { "onServiceAdded: ignoring service=${service.uuid} from discarded round $round" }
        return
      }
      if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.e(TAG, "onServiceAdded: service=${service.uuid} failed with status=$status")
        // Re-check round to avoid overwriting outcome of concurrent teardown.
        failPublicationRound(round, GattServerException(
          "ERR_CREATE_SERVER", "Failed to add service ${service.uuid} (status $status)"
        ))
        return
      }
      logDebug { "onServiceAdded: service=${service.uuid} registered" }
      addNextService(round)
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
      device?.let {
        logDebug { "onMtuChanged: device=${it.address} mtu=$mtu" }
        deviceMtu[it.address] = mtu
        listener?.onMtuChanged(it.address, DeviceMtu(mtu))
      }
    }
  }

  /** Opens GATT server and registers services. onReady called once with null or first failure; may be called on binder thread. buildServices is retained and called on each rebuild. */
  fun open(
    onReady: (error: GattServerException?) -> Unit,
    buildServices: () -> List<BluetoothGattService>,
  ): Unit = synchronized(serverLifecycleLock) {
    // Refuse if stop() already ran; prevent orphaned server.
    if (stopped) {
      onReady(GattServerException("ERR_NO_SERVER", "Server was stopped before it finished opening"))
      return
    }
    openCompletion.set(onReady)
    // All exceptions must settle completion; binder calls can fail (revoked permission, receiver limit, etc).
    try {
      // Install before checking adapter so server publishes on next power-on, matching iOS behavior.
      serviceFactory.set(buildServices)
      registerStateReceiver()

      bluetoothUnavailable()?.let {
        // IDLE allows retry on power-on; FAILED would refuse parked callers.
        finishOpen(DatabasePublication.IDLE, it)
        return
      }

      if (!openServer()) {
        finishOpen(
          DatabasePublication.FAILED,
          GattServerException("ERR_CREATE_SERVER", "Unable to open GATT server")
        )
      }
    } catch (e: Exception) {
      // Reported through the completion, which is the caller's promise, rather than rethrown: the
      // binding's own catch would reject it too, and the completion would stay armed either way.
      finishOpen(
        DatabasePublication.FAILED,
        (e as? GattServerException)
          ?: GattServerException("ERR_CREATE_SERVER", e.message ?: "Unable to open GATT server")
      )
    }
  }

  @SuppressLint("MissingPermission")
  private fun openServer(): Boolean {
    val buildServices = serviceFactory.get() ?: return false
    // Retain values before rebuild to preserve writes across power cycles, matching iOS behavior.
    val retained = currentCharacteristicValues()
    // Claim round before close to avoid stale acknowledgement overwriting current round.
    val round = synchronized(publicationLock) {
      publication = DatabasePublication.IN_PROGRESS
      pendingServices.clear()
      publicationRound.incrementAndGet()
    }
    // Close stale server before opening new one; serializes concurrent opens.
    gattServer?.let {
      logDebug { "Closing the GATT server already open before opening another" }
      it.close()
      gattServer = null
    }
    val server = bluetoothManager?.openGattServer(context, gattServerCallback(round)) ?: return false
    gattServer = server
    server.clearServices()

    val services = buildServices()
    restoreCharacteristicValues(services, retained)
    publishedServices.set(services)
    pendingServices.clear()
    pendingServices.addAll(services)
    logDebug { "Server opened, registering ${services.size} service(s)" }
    armPublicationTimeout(round)
    addNextService(round)
    return true
  }

  /** Bound for service registration. Round id prevents stale timeout from failing replacement round. */
  private fun armPublicationTimeout(round: Int) {
    cancelPublicationTimeout()
    // Stamped with round to ignore stale timeouts from power cycles.
    val timeout = Runnable {
      if (publicationRound.get() != round) return@Runnable
      Log.e(TAG, "No onServiceAdded within $PUBLICATION_TIMEOUT_MS ms; reporting the round as failed")
      // failPublicationRound re-checks round and applies atomically.
      failPublicationRound(
        round,
        GattServerException(
          "ERR_CREATE_SERVER",
          "The Bluetooth stack did not acknowledge a service registration within " +
            "$PUBLICATION_TIMEOUT_MS ms, so the database was not published. Call createServer again " +
            "to retry."
        ),
      )
    }
    publicationTimeout.set(timeout)
    timeoutHandler.postDelayed(timeout, PUBLICATION_TIMEOUT_MS)
  }

  private fun cancelPublicationTimeout() {
    publicationTimeout.getAndSet(null)?.let { timeoutHandler.removeCallbacks(it) }
  }

  /** Current values of latest round's characteristics. Read from retained instances, not gattServer, which closes before next round starts. */
  private fun currentCharacteristicValues(): Map<CharacteristicAddress, ByteArray> {
    val services = publishedServices.get()
    if (services.isEmpty()) return emptyMap()
    val values = HashMap<CharacteristicAddress, ByteArray>()
    synchronized(attributeValueLock) {
      for (service in services) {
        for (characteristic in service.characteristics) {
          @Suppress("DEPRECATION")
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
  private fun restoreCharacteristicValues(
    services: List<BluetoothGattService>,
    values: Map<CharacteristicAddress, ByteArray>,
  ) {
    if (values.isEmpty()) return
    synchronized(attributeValueLock) {
      for (service in services) {
        for (characteristic in service.characteristics) {
          val value = values[CharacteristicAddress(service.uuid, characteristic.uuid)] ?: continue
          @Suppress("DEPRECATION")
          characteristic.value = value
        }
      }
    }
  }

  /** Reports publication failure only when round itself failed (FAILED state) and nobody else reported it. Prevents phantom errors. */
  private fun reportPublicationFailure(
    state: DatabasePublication,
    error: GattServerException?,
    reported: Boolean,
  ) {
    if (reported || state != DatabasePublication.FAILED || error == null) return
    listener?.onServerPublicationFailed(error.code, error.message ?: "The database was not published")
  }

  /** Ends the current round under [publicationLock], so [addNextService] cannot act on a stale one. */
  private fun discardPublicationRound() = synchronized(publicationLock) {
    publicationRound.incrementAndGet()
    pendingServices.clear()
  }

  /** Fails [round] as one atomic step under publicationLock to prevent stale round from overwriting current state. */
  private fun failPublicationRound(round: Int, error: GattServerException) {
    synchronized(publicationLock) {
      if (publicationRound.get() != round) {
        logDebug { "failPublicationRound: round $round was discarded, leaving its outcome alone" }
        return
      }
      publicationRound.incrementAndGet()
      pendingServices.clear()
    }
    finishOpen(DatabasePublication.FAILED, error, onlyIf = DatabasePublication.IN_PROGRESS)
  }

  /** Called from open or onServiceAdded to queue and register next service. Platform limits one in-flight add at a time. Round check/poll/decision atomic under publicationLock. */
  @SuppressLint("MissingPermission")
  private fun addNextService(round: Int) {
    val next = synchronized(publicationLock) {
      if (publicationRound.get() != round) {
        logDebug { "addNextService: round $round was discarded, leaving it to the one that replaced it" }
        return
      }
      val polled = pendingServices.poll()
      if (polled == null) {
        logDebug { "All services registered" }
        finishOpen(DatabasePublication.PUBLISHED, null)
        return
      }
      val server = gattServer
      if (server == null) {
        failPublicationRound(round, GattServerException(
          "ERR_NO_SERVER", "The GATT server was closed before service ${polled.uuid} could be registered"
        ))
        return
      }
      polled to server
    }
    val (service, server) = next
    // False return means add never initiated; no callback coming. Report through round in case it was torn down.
    if (!server.addService(service)) {
      Log.e(TAG, "addService: could not initiate registration of ${service.uuid}")
      failPublicationRound(round, GattServerException(
        "ERR_CREATE_SERVER", "Could not initiate registration of service ${service.uuid}"
      ))
    }
  }

  /** Ends registration round, settles completion and parked callers. onlyIf: race-safe state check. report: false for app-initiated teardowns. */
  private fun finishOpen(
    state: DatabasePublication,
    error: GattServerException?,
    onlyIf: DatabasePublication? = null,
    report: Boolean = true,
  ) {
    // Round complete; disarm timeout.
    cancelPublicationTimeout()
    // onlyIf makes check and transition atomic to prevent overwriting concurrent completion.
    var applied = true
    val parked = synchronized(publicationLock) {
      if (onlyIf != null && publication != onlyIf) {
        applied = false
        emptyList()
      } else {
        publication = state
        val waiters = readinessWaiters.toList()
        readinessWaiters.clear()
        waiters
      }
    }
    if (!applied) return
    val completion = openCompletion.getAndSet(null)
    completion?.invoke(error)
    if (report) {
      reportPublicationFailure(state, error, reported = completion != null || parked.isNotEmpty())
    }
    if (parked.isEmpty()) return
    // Release on lifecycle looper, not GATT callback thread or main thread; both hold up later calls.
    val release = Runnable { parked.forEach { it(error) } }
    // Fallback: post() fails once looper quits; main looper never quits.
    if (!lifecycleHandler().post(release) && !timeoutHandler.post(release)) {
      Log.w(TAG, "No looper accepted the release of ${parked.size} parked caller(s); running inline")
      release.run()
    }
  }

  /** Invokes onReady once services registered; parks if registration in progress, settles immediately if failed or adapter unavailable. */
  private fun whenDatabasePublished(onReady: (error: GattServerException?) -> Unit) {
    // Check adapter first to avoid parking caller on unavailable state.
    bluetoothUnavailable()?.let {
      onReady(it)
      return
    }
    // Release outside monitor; prevent blocking other updates.
    val failure = synchronized(publicationLock) {
      when (publication) {
        DatabasePublication.PUBLISHED -> null
        DatabasePublication.FAILED -> databaseNotPublished()
        DatabasePublication.IDLE, DatabasePublication.IN_PROGRESS -> {
          readinessWaiters.add(onReady)
          return
        }
      }
    }
    onReady(failure)
  }

  private fun databaseNotPublished() = GattServerException(
    "ERR_NO_SERVER",
    "No GATT database is published, so there is nothing to advertise. isServerRunning reports " +
      "whether the database is still there, which a failed registration or Bluetooth going down undoes."
  )

  /** Bluetooth-level failure or null if usable. isEnabled is @RequiresNoPermission. */
  private fun bluetoothUnavailable(): GattServerException? {
    val adapter = bluetoothAdapter
      ?: return GattServerException("ERR_BLUETOOTH", BLUETOOTH_UNSUPPORTED_MESSAGE)
    if (!adapter.isEnabled) {
      return GattServerException("ERR_BLUETOOTH", BLUETOOTH_OFF_MESSAGE)
    }
    return null
  }

  /** Adapter off closes server; report as Bluetooth problem (not ERR_NO_SERVER) for user to enable Bluetooth. */
  private fun serverUnavailable(): GattServerException =
    bluetoothUnavailable() ?: GattServerException("ERR_NO_SERVER", "The GATT server is not open")

  /** Advertises once database registered, holding call rather than refusing. onResult called exactly once. Stop races reject with single meaning: stop requested. */
  fun startAdvertising(options: AdvertiseOptions, onResult: (error: GattServerException?) -> Unit) {
    val generation = advertisingGeneration.get()
    whenDatabasePublished { error ->
      if (error != null) {
        onResult(error)
        return@whenDatabasePublished
      }
      if (advertisingGeneration.get() != generation) {
        onResult(advertisingStopped())
        return@whenDatabasePublished
      }
      try {
        beginAdvertising(options, generation, onResult)
      } catch (e: GattServerException) {
        onResult(e)
      } catch (e: Exception) {
        onResult(GattServerException("ERR_ADVERTISE", e.message ?: "Advertising failed"))
      }
    }
  }

  /** Android advertises only adapter name (not per-advertisement name). localName requires setAdapterName. */
  private fun beginAdvertising(
    options: AdvertiseOptions,
    generation: Int,
    onResult: (error: GattServerException?) -> Unit,
  ) {
    // Re-check: adapter can turn off between server check and start. Matches iOS error semantics.
    val adapter = bluetoothAdapter
      ?: throw GattServerException("ERR_BLUETOOTH", BLUETOOTH_UNSUPPORTED_MESSAGE)
    if (!adapter.isEnabled) {
      throw GattServerException("ERR_BLUETOOTH", BLUETOOTH_OFF_MESSAGE)
    }

    // Server check after adapter check (iOS order) to report adapter problem as root cause.
    if (!isServerRunning()) {
      throw databaseNotPublished()
    }

    if (options.setAdapterName && options.localName == null) {
      throw IllegalArgumentException(
        "android.setAdapterName was requested without a localName for the adapter to be renamed to."
      )
    }

    if (options.setAdapterName && options.localName != null) {
      applyAdapterName(adapter, options.localName)
    }

    // Null only if no multi-ad support; enabled check ruled out adapter off. Undo name rename if start fails.
    val leAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
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

    // Advertisement payload: 31-byte budget for UUIDs, manufacturer and service data. Name/TX power in scan response to avoid budget competition.
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
        scheduleAdvertisingTimeout(options.timeoutMs)
        finishAdvertise(this, null)
        // Re-check: stop may have raced the state changes above (current() is read, not claim).
        if (!current()) {
          advertising.set(false)
          cancelAdvertisingTimeout()
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
        // Undo rename; start never reached air.
        restoreAdapterName()
        finishAdvertise(this, GattServerException("ERR_ADVERTISE", msg))
      }
    }

    // Callback swapped first (it's what current() tests). Guarded from both swap and start binder call.
    val start = PendingAdvertiseStart(callback, onResult)
    try {
      val displaced = advertiseCallback.getAndSet(callback)
      pendingAdvertiseResult.getAndSet(start)
        ?.onResult?.invoke(GattServerException("ERR_ADVERTISE", "Advertising restarted"))
      // Platform keys by callback identity; displaced callback must be stopped to free slot and stop broadcast.
      displaced?.let { leAdvertiser.stopAdvertising(it) }
      // Only cancel if still owner; concurrent starts can displace while in beginAdvertising.
      if (advertiseCallback.get() === callback) {
        cancelAdvertisingTimeout()
      }
      // Arm before start so callback delivered immediately still finds armed timeout. Expiry re-checks start identity.
      armAdvertisingStartTimeout(start)
      leAdvertiser.startAdvertising(settings, advData.build(), scanResponse, callback)
    } catch (e: Exception) {
      // `startAdvertising` rechecks the adapter state itself and throws if it went off. The caller reports
      // that throw, so neither the completion nor the callback may be left installed for a later stop to
      // settle and stop a second time. compareAndSet, so a concurrent restart's own state is left alone.
      val ours = pendingAdvertiseResult.compareAndSet(start, null)
      // Inert once the completion it watched is gone, but cancelled so it does not sit on the looper.
      if (ours) {
        cancelAdvertisingStartTimeout()
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
    if (advertiseCallback.get() !== callback || advertisingGeneration.get() != generation) {
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
      if (pendingAdvertiseResult.compareAndSet(start, null)) {
        onResult(advertisingStopped())
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun stopAdvertising() {
    // Bump generation so waiting starts know stop was requested.
    advertisingGeneration.incrementAndGet()
    // Claim callback first (it's what current() tests); clearing advertising first let onStartSuccess re-arm a stopped ad.
    val callback = advertiseCallback.getAndSet(null)
    cancelAdvertisingTimeout()
    advertising.set(false)
    callback?.let { advertiser.get()?.stopAdvertising(it) }
    finishAdvertise(advertisingStopped())
    restoreAdapterName()
  }

  private fun advertisingStopped() = GattServerException("ERR_ADVERTISE", "Advertising stopped")

  /** Settles outstanding start once, unconditionally. Error code distinguishes ads failure from Bluetooth/server failures. */
  private fun finishAdvertise(error: GattServerException?) {
    cancelAdvertisingStartTimeout()
    pendingAdvertiseResult.getAndSet(null)?.onResult?.invoke(error)
  }

  /** Settles outstanding start only if it still belongs to [callback]; displaced starts are ignored. */
  private fun finishAdvertise(callback: AdvertiseCallback, error: GattServerException?) {
    val start = pendingAdvertiseResult.get() ?: return
    if (start.callback !== callback) return
    if (!pendingAdvertiseResult.compareAndSet(start, null)) return
    cancelAdvertisingStartTimeout()
    start.onResult(error)
  }

  /** Bounds start callback wait. Expiry both settles promise and stops radio (cannot separate them). Stops all cbs even if none started (platform-safe). */
  @SuppressLint("MissingPermission")
  private fun armAdvertisingStartTimeout(start: PendingAdvertiseStart) {
    val callback = start.callback
    val expiry = Runnable {
      if (pendingAdvertiseResult.get() !== start) return@Runnable
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
      finishAdvertise(
        callback,
        GattServerException(
          "ERR_ADVERTISE",
          "The Bluetooth stack did not report the advertisement as started or failed within " +
            "$ADVERTISING_START_TIMEOUT_MS ms. Nothing is advertising."
        )
      )
    }
    while (true) {
      val current = advertisingStartTimeout.get()
      // The installed bound belongs to a start that still owns the radio, which means this one has been
      // displaced: replacing it would leave the live start with no bound at all, which is the hang
      // ADVERTISING_START_TIMEOUT_MS exists to prevent. The displaced start is stopped by the tail of
      // `beginAdvertising`, so it needs no bound of its own.
      if (current != null && current.first !== callback && advertiseCallback.get() === current.first) {
        return
      }
      if (advertisingStartTimeout.compareAndSet(current, callback to expiry)) {
        current?.let { timeoutHandler.removeCallbacks(it.second) }
        timeoutHandler.postDelayed(expiry, ADVERTISING_START_TIMEOUT_MS)
        return
      }
    }
  }

  private fun cancelAdvertisingStartTimeout() {
    advertisingStartTimeout.getAndSet(null)?.let { timeoutHandler.removeCallbacks(it.second) }
  }

  /**
   * `AdvertiseSettings.setTimeout` stops advertising at the limit without invoking `AdvertiseCallback`,
   * so [advertising] would otherwise stay set for the rest of the process. Only the flag is cleared —
   * the platform has already stopped the advertisement itself.
   */
  private fun scheduleAdvertisingTimeout(timeoutMs: Int) {
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
    advertisingTimeout.set(expiry)
    timeoutHandler.postDelayed(expiry, timeoutMs.toLong())
  }

  private fun cancelAdvertisingTimeout() {
    advertisingTimeout.getAndSet(null)?.let { timeoutHandler.removeCallbacks(it) }
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
  private fun restoreAdapterName() {
    val previous = originalAdapterName.get() ?: return
    val adapter = bluetoothAdapter ?: return
    if (adapter.setName(previous)) {
      originalAdapterName.compareAndSet(previous, null)
    } else {
      // Retained for a later attempt: `setName` fails while the adapter is off, which is exactly when a
      // teardown is most likely to run.
      Log.w(TAG, "Could not restore the adapter name to \"$previous\" yet")
    }
  }

  /**
   * Queues a notification for [deviceId] and reports the outcome through [onResult] — with `null` once
   * the platform confirms delivery through `onNotificationSent`. Throws only for problems detectable
   * before the send is accepted into the queue.
   *
   * The call deliberately does not complete as soon as the payload is handed to the stack: a device may
   * have one notification outstanding at a time, so anything sent while an earlier one is still in
   * flight waits its turn instead of being discarded by the stack.
   *
   * [confirm] selects an indication over a notification, which the characteristic must declare the
   * matching property for; [requireSubscription] additionally refuses the send when the device has not
   * enabled that same transmission in its own CCCD. Clearing it sends anyway, since the platform does
   * not consult the CCCD before transmitting. The property check is not optional either way.
   */
  fun sendNotification(
    deviceId: String,
    serviceUuid: String,
    characteristicUuid: String,
    value: ByteArray,
    confirm: Boolean,
    requireSubscription: Boolean,
    onResult: (GattServerException?) -> Unit,
  ) {
    val server = gattServer ?: throw serverUnavailable()

    // An unknown service and an unknown characteristic collapse into one code, because an address that
    // names nothing in the published database is the same mistake either way — and because that is the
    // only distinction iOS can draw, where `CBATTRequest.characteristic.service` is a weak reference.
    //
    // Checked before the connection, and iOS checks them in the same order, so a call carrying both a
    // stale deviceId and a mistyped UUID reports the same code on either platform. The address is the
    // permanent fault of the two: no retry fixes it, while a disconnection may well resolve itself.
    val serviceId = parseUuid(serviceUuid, "service")
    val characteristicId = parseUuid(characteristicUuid, "characteristic")
    val characteristic = server.getService(serviceId)
      ?.getCharacteristic(characteristicId)
      ?: throw GattServerException(
        "ERR_CHARACTERISTIC_NOT_FOUND",
        "Characteristic $characteristicUuid was not found in service $serviceUuid"
      )

    confirmError(characteristic, confirm)?.let { throw it }

    val device = connectedDevices[deviceId]
      ?: throw GattServerException(
        "ERR_DEVICE_DISCONNECTED", "Device $deviceId is not connected"
      )

    val address = CharacteristicAddress(serviceId, characteristicId)
    if (requireSubscription && !hasEnabled(deviceId, address, confirm)) {
      val kind = if (confirm) "indications" else "notifications"
      throw GattServerException(
        "ERR_NO_SUBSCRIBER",
        "Device $deviceId has not enabled $kind on characteristic $characteristicUuid. Wait for " +
          "onCharacteristicSubscribed, or pass requireSubscription: false to send anyway."
      )
    }

    // Refused before the send is queued, so an oversized payload never reaches the stack.
    mtuErrorFor(deviceId, value.size)?.let { throw it }

    val entry = QueuedNotification(device, characteristic, characteristicUuid, confirm, value, onResult)
    // `computeIfAbsent` rather than `getOrPut`, which is a plain `get() ?: put()` — `kotlin.concurrent`
    // is not imported, so the atomic overload is not the one that resolves. Two first sends to the same
    // device racing each other both built a queue and the second replaced the first in the map, leaving
    // whatever the first had enqueued in a queue nothing would drain. The same hazard `subscriptions`
    // uses `compute` for.
    val queue = notificationQueues.computeIfAbsent(deviceId) { NotificationQueue() }
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
    // Two ways this entry can land somewhere nothing will drain it, both from a teardown running between
    // the lookup above and the enqueue.
    //
    // The device may simply have gone away. Or — the case testing `connectedDevices` alone missed — the
    // central may have disconnected and reconnected inside the window: the teardown detached this queue
    // from the map and the reconnection registered a new one, so the device is present again while this
    // entry sits in the detached queue with no timeout armed and a promise that never settles. A
    // detached queue is never re-registered, so its identity is what distinguishes the two.
    val detached = notificationQueues[deviceId] !== queue
    val disconnected = !connectedDevices.containsKey(deviceId)
    if (detached || disconnected) {
      val error = GattServerException("ERR_DEVICE_DISCONNECTED", "Device $deviceId disconnected")
      // Only when the device itself is gone. A queue the central has already reconnected behind belongs
      // to the live connection, and failing its entries would settle sends that are still perfectly good.
      if (disconnected) failNotifications(deviceId, error)
      // Settled straight from this entry either way, because the drain above cannot reach a queue that
      // is no longer the registered one.
      if (takeQueued(queue, entry)) entry.onResult(error)
      return
    }
    pumpNotifications(deviceId)
  }

  /**
   * The current link budget for [deviceId], or `null` when the device is not connected. A device that
   * has not negotiated an MTU is reported at the specification default rather than as unknown, because
   * that default is what the link actually carries until a negotiation happens.
   */
  fun mtuFor(deviceId: String): DeviceMtu? {
    if (!connectedDevices.containsKey(deviceId)) return null
    return DeviceMtu(deviceMtu[deviceId] ?: DEFAULT_ATT_MTU)
  }

  /**
   * The centrals connected to *this* server, from the module's own tracking of `onConnectionStateChange`.
   * `BluetoothManager.getConnectedDevices(GATT_SERVER)` is deliberately not used: it reports centrals
   * connected to any GATT server on the device, including other apps'.
   */
  @SuppressLint("MissingPermission")
  fun connectedDeviceList(): List<Pair<String, String?>> =
    connectedDevices.values.map { it.address to it.name }

  /** Whether a GATT database is currently published, which the adapter going down undoes. */
  fun isServerRunning(): Boolean =
    synchronized(publicationLock) { publication == DatabasePublication.PUBLISHED }

  fun isAdvertising(): Boolean = advertising.get()

  /**
   * Asks the stack to drop [deviceId]. `cancelConnection` returns nothing, so there is no outcome to
   * report — the disconnection surfaces through `onConnectionStateChange`, which is what clears this
   * device's state.
   */
  @SuppressLint("MissingPermission")
  fun disconnect(deviceId: String) {
    val server = gattServer ?: throw serverUnavailable()
    val device = connectedDevices[deviceId]
      ?: throw GattServerException("ERR_DEVICE_DISCONNECTED", "Device $deviceId is not connected")
    logDebug { "Cancelling connection to $deviceId" }
    server.cancelConnection(device)
  }

  /** The two-octet configuration this client last wrote, or the specified default of 0x0000. */
  internal fun clientConfiguration(deviceId: String, address: CharacteristicAddress): Int =
    subscriptions[deviceId]?.get(address) ?: 0

  /**
   * Whether [deviceId] set either the notification or the indication bit of its own CCCD. This is the
   * coarse question the subscribe and unsubscribe events answer; a send asks [hasEnabled] about one
   * specific bit.
   */
  private fun isSubscribed(deviceId: String, address: CharacteristicAddress): Boolean =
    cccdSubscribed(clientConfiguration(deviceId, address))

  /** Whether [deviceId] enabled exactly the transmission [confirm] selects. See [cccdEnables]. */
  internal fun hasEnabled(
    deviceId: String,
    address: CharacteristicAddress,
    confirm: Boolean,
  ): Boolean = cccdEnables(clientConfiguration(deviceId, address), confirm)

  /** See [expo.modules.gattserver.confirmError], which this reads the declaration for. */
  private fun confirmError(
    characteristic: BluetoothGattCharacteristic,
    confirm: Boolean,
  ): GattServerException? = confirmError(characteristic.properties, characteristic.uuid, confirm)

  /**
   * Records a client's new CCCD value and reports the transition. Only the change from "receiving
   * nothing" to "receiving something" and back is surfaced, because switching between notifications and
   * indications leaves the client subscribed throughout.
   */
  private fun applyClientConfiguration(
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    bits: Int,
  ) {
    val deviceId = device.address
    val characteristicUuid = characteristic.uuid
    val enabled = bits and (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) != 0

    // An unresolvable subscription is reported but not recorded, as iOS does with the same situation:
    // filed under the wrong service it would make a send to another service's same-named characteristic
    // look deliverable, and the stack transmits whatever it is handed without consulting the CCCD.
    val address = addressOf(characteristic)
    if (address == null) {
      Log.w(TAG, "CCCD: cannot name the service owning $characteristicUuid, not recording the subscription")
      if (enabled) {
        listener?.onCharacteristicSubscribed(deviceId, "", characteristicUuid.toString())
      } else {
        listener?.onCharacteristicUnsubscribed(deviceId, "", characteristicUuid.toString())
      }
      return
    }

    // Both branches go through `compute`, which holds the bin lock for the key, so the whole
    // read-modify-write is one step on the outer map. Android 13+ gives one connection several concurrent
    // ATT bearers, so two CCCD writes from the same central really do arrive on two binder threads.
    //
    // `getOrPut` is `get() ?: put()`: both threads saw no inner map, both built one, and the second
    // replaced the first — stranding whatever the loser had recorded, so the central looked unsubscribed
    // to every later send. The removal had the matching hazard: `remove(deviceId, forDevice)` matches on
    // the instance, so a subscription added between the emptiness check and the removal went with it.
    //
    // The previous state is read inside the same `compute` for the same reason. Sampling it separately
    // left the *decision* racy even though the map was not: two enabling writes could both observe "not
    // subscribed" and emit two `onCharacteristicSubscribed`, and an enable interleaved with a disable
    // could emit two subscribes and no unsubscribe, so a consumer counting subscribers drifted.
    var wasEnabled = false
    subscriptions.compute(deviceId) { _, forDevice ->
      val previous = forDevice?.get(address) ?: 0
      wasEnabled = previous and (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) != 0
      if (bits == 0) {
        forDevice?.remove(address)
        if (forDevice.isNullOrEmpty()) null else forDevice
      } else {
        (forDevice ?: ConcurrentHashMap()).also { it[address] = bits }
      }
    }

    val serviceUuid = address.service.toString()
    logDebug { "CCCD: device=$deviceId service=$serviceUuid char=$characteristicUuid bits=$bits subscribed=$enabled" }
    if (enabled && !wasEnabled) {
      listener?.onCharacteristicSubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    } else if (!enabled && wasEnabled) {
      listener?.onCharacteristicUnsubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    }
  }

  private fun clearSubscriptions(deviceId: String) {
    val forDevice = subscriptions.remove(deviceId) ?: return
    for ((address, bits) in forDevice) {
      if (bits and (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) == 0) continue
      // The service comes from the address the subscription was recorded under, so it names the very
      // attribute the client configured rather than the first service happening to declare that UUID.
      listener?.onCharacteristicUnsubscribed(
        deviceId, address.service.toString(), address.characteristic.toString()
      )
    }
  }

  /**
   * Hands the next queued notification to the stack if the device's single outstanding slot is free.
   * Entries the stack refuses outright never produce a callback, so they are completed here and the loop
   * moves on to the next one.
   */
  private fun pumpNotifications(deviceId: String) {
    val queue = notificationQueues[deviceId] ?: return
    while (true) {
      val next = synchronized(queue) {
        if (queue.inFlight != null) return
        val candidate = queue.waiting.removeFirstOrNull() ?: return
        queue.inFlight = candidate
        candidate
      }
      // Armed only once the stack has accepted the send, because the bound exists for a callback that
      // never arrives — and a dispatch that fails outright settles the entry here instead, which would
      // leave a timer running against an entry already gone.
      val error = dispatchNotification(deviceId, next)
      if (error == null) {
        armNotificationTimeout(deviceId, queue, next)
        return
      }
      // A busy stack has refused the offer, not the entry, so the entry goes back where it was rather
      // than being failed — and the loop stops, because offering the next one now would be refused for
      // exactly the same reason and take the whole backlog down with it.
      if (error is NotifyBusyException && reparkBusyNotification(deviceId, queue, next)) return
      // Only the thread that still owns the entry may settle it: a disconnect or a stop can take it
      // during the dispatch and settle it first, and a second settle throws on a release build.
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
   * Puts an entry the stack refused as busy back at the head of its queue and schedules another attempt,
   * reporting whether it did.
   *
   * `false` means the entry must be settled by the caller instead: either its budget is spent — bounded
   * by [NOTIFICATION_TIMEOUT_MS], the same outer bound a send the stack accepted gets, so a device whose
   * stack never frees up fails its sends rather than retrying for the life of the process — or something
   * else has taken it already, in which case the caller's own ownership check declines to settle it too.
   */
  private fun reparkBusyNotification(
    deviceId: String,
    queue: NotificationQueue,
    entry: QueuedNotification,
  ): Boolean {
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
    // Not the main looper: the retry re-enters `notifyValue`, a binder call that holds
    // [attributeValueLock] before Tiramisu, and a stalled central retries it every 50 ms for 35 s.
    lifecycleHandler().postDelayed({ pumpNotifications(deviceId) }, NOTIFICATION_BUSY_RETRY_MS)
    return true
  }

  /**
   * Bounds the wait for one entry's `onNotificationSent`.
   *
   * The timer names the entry it was armed for and settles it through [takeQueued], so it needs no
   * cancelling: one that fires after the callback already arrived finds the entry gone and does nothing.
   * That keeps the bound off every path that clears `inFlight` — the callback, a disconnect, an adapter
   * power cycle and `stop` — none of which can then forget to cancel it.
   */
  private fun armNotificationTimeout(
    deviceId: String,
    queue: NotificationQueue,
    entry: QueuedNotification,
  ) {
    // Off the main looper for the same reason as [reparkBusyNotification]: this ends by pumping the
    // queue, which re-enters the binder.
    lifecycleHandler().postDelayed({
      val abandoned = synchronized(queue) {
        if (queue.inFlight === entry) {
          queue.inFlight = null
          // Only this branch records one: the stack accepted this send and still owes a callback for
          // it. An entry still waiting was never handed over. See
          // [NotificationQueue.callbacksOwedToAbandonedSends].
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
      pumpNotifications(deviceId)
    }, NOTIFICATION_TIMEOUT_MS)
  }

  /** Returns `null` when the stack accepted the send and a callback is now expected. */
  private fun dispatchNotification(deviceId: String, entry: QueuedNotification): GattServerException? {
    val server = gattServer ?: return serverUnavailable()
    // Re-checked as well as at enqueue time: the MTU can change while an entry waits its turn, and the
    // payload must never reach the stack if it cannot be carried intact.
    mtuErrorFor(deviceId, entry.value.size)?.let { return it }
    // Reported as a refusal rather than allowed to propagate. Two of the three callers —
    // `onNotificationSent` and the timeout runnable — are the Bluetooth binder thread and the main
    // looper, where nothing catches, so a throw from the stack would take the process down instead of
    // failing the one send. `notifyCharacteristicChanged` does throw for arguments it will not carry,
    // and the checks above cannot be assumed to have anticipated every one of them.
    return try {
      notifyValue(server, entry.device, entry.characteristic, entry.confirm, entry.value)
    } catch (e: Exception) {
      Log.e(TAG, "The Bluetooth stack refused the notification for ${entry.characteristicUuid}", e)
      GattServerException(
        "ERR_NOTIFY",
        "The Bluetooth stack refused the notification for ${entry.characteristicUuid}: " +
          (e.message ?: e::class.java.simpleName)
      )
    }
  }

  /**
   * Removes [entry] from [queue] if it is still there, reporting whether this call is the one that took
   * it — so an entry a concurrent drain has already claimed is not settled a second time.
   */
  private fun takeQueued(queue: NotificationQueue, entry: QueuedNotification): Boolean =
    synchronized(queue) {
      if (queue.inFlight === entry) {
        queue.inFlight = null
        true
      } else {
        queue.waiting.remove(entry)
      }
    }

  private fun failNotifications(deviceId: String, error: GattServerException) {
    val queue = notificationQueues.remove(deviceId) ?: return
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

  private fun failAllNotifications(error: GattServerException) {
    notificationQueues.keys.toList().forEach { failNotifications(it, error) }
  }

  /** See [expo.modules.gattserver.mtuErrorFor], which this supplies the link's negotiated MTU to. */
  private fun mtuErrorFor(deviceId: String, size: Int): MtuException? =
    mtuErrorFor(deviceMtu[deviceId], size)

  /**
   * Holds one part of a long or reliable write until the execute arrives, and echoes it back: the
   * response's handle, offset and part value "shall be set to the same value as in the corresponding
   * ATT_PREPARE_WRITE_REQ PDU" (Core Spec Vol 3, Part F, §3.4.6.2), which a Reliable Write client
   * compares and cancels the whole procedure over. A refused prepare leaves the existing queue untouched,
   * as the specification requires.
   */
  @SuppressLint("MissingPermission")
  private fun queuePreparedWrite(
    device: BluetoothDevice,
    requestId: Int,
    write: PreparedWrite,
    responseNeeded: Boolean,
  ) {
    var accepted = false
    preparedWrites.compute(device.address) { _, existing ->
      val queue = existing ?: mutableListOf()
      if (queue.size < MAX_PREPARED_WRITES_PER_DEVICE) {
        queue.add(write)
        accepted = true
      }
      queue
    }
    if (!accepted) {
      Log.w(TAG, "onPreparedWrite: queue full for device=${device.address}, rejecting")
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, ATT_ERROR_PREPARE_QUEUE_FULL, write.offset, null)
      }
      return
    }
    if (responseNeeded) {
      gattServer?.sendResponse(
        device, requestId, BluetoothGatt.GATT_SUCCESS, write.offset, write.value
      )
    }
  }

  /**
   * What one execute assembled. Everything it could commit is already committed by the time this exists;
   * what remains is the response, the CCCD transitions and the events — none of which may run under
   * [attributeValueLock].
   */
  private class AssembledExecute(
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
   * Executes [queued] as one atomic operation, in the order the parts were received. Parts are assembled
   * onto each attribute's current value first and nothing is applied until every one of them has been
   * validated, because the execute either wholly succeeds or wholly fails: a part starting past the end
   * of its attribute is answered with "Invalid Offset" and discards the entire queue (Core Spec Vol 3,
   * Part F, §3.4.6.3).
   *
   * [assemblePreparedWrites] does the whole read-modify-write in one critical section; the response and
   * the events follow it, because a listener may re-enter the module.
   */
  @SuppressLint("MissingPermission")
  private fun applyPreparedWrites(
    device: BluetoothDevice,
    requestId: Int,
    queued: List<PreparedWrite>,
  ) {
    val assembled = assemblePreparedWrites(queued)

    assembled.attError?.let { attError ->
      gattServer?.sendResponse(device, requestId, attError, 0, null)
      return
    }

    // A delegated write is JavaScript's to accept or reject, so the assembled values were withheld until
    // it answers, and one pending request stands for the whole atomic execute. The configuration changes
    // ride along with them: the queued-write procedure is atomic, so a subscription the same execute
    // asked for must not survive a rejection of it.
    if (assembled.delegated.isNotEmpty()) {
      registerPendingRequest(
        requestId, device.address, offset = 0, isRead = false,
        deferredValues = assembled.deferredValues,
        clientConfigurations = assembled.clientConfigurations,
        deferredDescriptors = assembled.deferredDescriptors,
      )
    } else {
      // Applied here rather than during the assembly, because a subscribe or unsubscribe transition
      // reports to a listener.
      for ((descriptor, bits) in assembled.clientConfigurations) {
        applyClientConfiguration(device, descriptor.characteristic, bits)
      }
      gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
    }

    // Reported once per attribute from offset 0, rather than replaying the fragments the client happened
    // to split the value into.
    //
    // Exactly one attribute is marked `responseNeeded`, because the execute is a single request and one
    // pending request stands for the whole batch — the answer covers every attribute in it. Marking each
    // delegated attribute instead handed them all the batch's one `requestId`, so the second
    // `sendResponse` rejected with `REQUEST_NOT_FOUND` after the first had already answered the execute.
    // A second delegated attribute still receives its event and can commit its value with
    // `updateCharacteristicValue`; it simply must not answer again. This is the shape iOS reports.
    val responder = assembled.characteristicValues.keys.firstOrNull { it in assembled.delegated }
    assembled.characteristicValues.forEach { (characteristic, value) ->
      listener?.onCharacteristicWriteRequest(
        device.address, requestId, characteristic.service?.uuid?.toString() ?: "",
        characteristic.uuid.toString(), 0, value, characteristic === responder
      )
    }
  }

  /**
   * Merges every queued part onto the value its attribute holds *now* and commits the result, the whole
   * read-modify-write under [attributeValueLock]. Assembling outside it would merge onto a value a
   * concurrent write or `updateCharacteristicValue` had already replaced, and the commit would then lose
   * that write.
   *
   * Nothing is committed unless every part validates. A CCCD is only assembled and returned, because
   * applying one reports to a listener.
   */
  private fun assemblePreparedWrites(queued: List<PreparedWrite>): AssembledExecute =
    synchronized(attributeValueLock) {
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
          return@synchronized AssembledExecute(attError = BluetoothGatt.GATT_INVALID_OFFSET)
        }
        // Checked on the assembled result rather than on each part: the parts are individually within
        // what a PDU carries, and it is only their placement that can push the attribute past what one
        // may hold. Left unchecked, a peer could commit a value longer than the specification allows —
        // which the module then refused to notify for the rest of the server's life, since the
        // notification bound is the same 512 octets, and carried across every adapter power cycle.
        if (exceedsAttributeLength(merged.size)) {
          Log.w(TAG, "onExecuteWrite: assembles to ${merged.size} octets, past the $MAX_ATTRIBUTE_VALUE_LENGTH-octet limit, rejecting")
          return@synchronized AssembledExecute(attError = ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH)
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
          return@synchronized AssembledExecute(attError = ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH)
        }
      }

      // Decided per characteristic, as the direct write path already does: one that never opted in must
      // still have its value applied, even when a sibling in the same execute delegates. Worked out
      // before anything is committed, because whether this execute is still refusable is what decides
      // which parts of it may be applied now.
      val delegated = characteristicValues.keys.filterTo(LinkedHashSet()) { delegationFor(it).write }
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

  /** Replaces the mirrored value a read of [characteristic] is answered from, under the value monitor. */
  private fun storeCharacteristicValue(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
  ) {
    synchronized(attributeValueLock) {
      @Suppress("DEPRECATION")
      characteristic.value = value
    }
  }

  /**
   * Arms the expiry that answers a delegated request if JavaScript never does. Called before the event is
   * emitted, so a listener that responds synchronously still finds the request.
   */
  private fun registerPendingRequest(
    requestId: Int,
    deviceId: String,
    offset: Int,
    isRead: Boolean,
    deferredValues: Map<BluetoothGattCharacteristic, DeferredWrite> = emptyMap(),
    clientConfigurations: List<Pair<BluetoothGattDescriptor, Int>> = emptyList(),
    deferredDescriptors: Map<BluetoothGattDescriptor, DeferredWrite> = emptyMap(),
  ) {
    val key = RequestKey(deviceId, requestId)
    val pending =
      PendingRequest(offset, isRead, deferredValues, clientConfigurations, deferredDescriptors)
    // The expiry names the entry it was armed for, so one already dispatched onto the main looper when its
    // request was answered or displaced cannot remove — and answer — whatever took its place.
    val timeout = if (requestTimeoutMs > 0) Runnable { expireRequest(key, pending) } else null
    pending.timeout = timeout
    val displaced = pendingRequests.put(key, pending)
    // A displaced entry can no longer be answered, and leaving its expiry armed would let it answer this
    // one instead.
    displaced?.timeout?.let { timeoutHandler.removeCallbacks(it) }
    if (timeout != null) {
      timeoutHandler.postDelayed(timeout, requestTimeoutMs.toLong())
    }
  }

  /**
   * Answers a request JavaScript left unanswered, so the central's transaction completes with an
   * error rather than stalling until its own ATT transaction timeout drops the connection.
   *
   * "Unlikely Error" is the closest the specification offers: the request was valid and the server
   * simply failed to produce a response, which none of the more specific codes describes.
   */
  @SuppressLint("MissingPermission")
  private fun expireRequest(key: RequestKey, pending: PendingRequest) {
    if (!pendingRequests.remove(key, pending)) return
    Log.w(TAG, "Request ${key.requestId} unanswered after ${requestTimeoutMs}ms, answering with an ATT error")
    val device = connectedDevices[key.deviceId] ?: return
    gattServer?.sendResponse(device, key.requestId, ATT_ERROR_UNLIKELY_ERROR, pending.offset, null)
  }

  /**
   * Answers every matching request with [status] and then forgets it — the counterpart of iOS's
   * `answerAndDiscardPendingRequests`, and the reason [discardPendingRequests] is reserved for the
   * paths where the link is already gone.
   *
   * `stop` disconnects nobody, so a central whose read or write is still outstanding is very likely
   * still connected, and dropping the request silently stalls its ATT bearer until the 30 s
   * transaction timeout retires it — after which no further request, notification or indication may
   * be sent on it at all (Core Spec Vol 3, Part F, §3.3.3).
   *
   * Must run while `gattServer` and `connectedDevices` are still populated, which is why `stop`
   * calls it before `close()` rather than alongside its other bookkeeping.
   */
  @SuppressLint("MissingPermission")
  private fun answerAndDiscardPendingRequests(status: Int, predicate: (RequestKey) -> Boolean) {
    val iterator = pendingRequests.entries.iterator()
    while (iterator.hasNext()) {
      val (key, pending) = iterator.next()
      if (!predicate(key)) continue
      pending.timeout?.let { timeoutHandler.removeCallbacks(it) }
      iterator.remove()
      val device = connectedDevices[key.deviceId] ?: continue
      gattServer?.sendResponse(device, key.requestId, status, pending.offset, null)
    }
  }

  /**
   * Forgets every matching request without answering it. Correct only where the central cannot hear
   * a response anyway — a disconnect, or the adapter going down. Everywhere else use
   * [answerAndDiscardPendingRequests].
   */
  private fun discardPendingRequests(predicate: (RequestKey) -> Boolean) {
    val iterator = pendingRequests.entries.iterator()
    while (iterator.hasNext()) {
      val (key, pending) = iterator.next()
      if (!predicate(key)) continue
      pending.timeout?.let { timeoutHandler.removeCallbacks(it) }
      iterator.remove()
    }
  }

  /**
   * Answers a pending read or write request.
   *
   * A read response is deliberately not size-checked: the central continues a value longer than one
   * `ATT_READ_RSP` with `ATT_READ_BLOB_REQ`, so answering with more than fits is normal ATT rather than a
   * failure, and the automatic read path already answers with the whole remainder from the requested
   * offset.
   *
   * [offset] states where [value] begins within the attribute, and the response is rebased onto the
   * offset the request actually asked for — so passing offset 0 with the whole value answers a Read Blob
   * continuation correctly, and passing the request's own offset with a pre-sliced value works too. iOS
   * honours the same contract.
   */
  @SuppressLint("MissingPermission")
  fun sendResponse(deviceId: String, requestId: Int, status: Int, offset: Int, value: ByteArray) {
    // Every rejection the caller could have caused is checked before the pending entry is touched, so a
    // rejected attempt leaves the request answerable instead of stranding the central until its ATT
    // transaction times out. The request is looked up first, so answering one the server has forgotten —
    // which is what losing the database to a stop or a power cycle leaves behind — reports the same code
    // iOS reports for it.
    // Range-checked natively as well as in JavaScript, because the module is reachable directly. The
    // framework narrows `status` to a byte on its way into the stack, so a wider value would go out as an
    // unrelated ATT error rather than be reported — 257 becoming 0x01 "Invalid Handle", say.
    if (status !in 0..0xFF) {
      throw GattServerException(
        "ERR_RESPONSE",
        "Invalid response status $status. An ATT error code is a single byte, so it must be between " +
          "0 and 255."
      )
    }
    val key = RequestKey(deviceId, requestId)
    val pending = pendingRequests[key] ?: throw unknownRequest(deviceId, requestId)
    val server = gattServer ?: throw serverUnavailable()
    val device = connectedDevices[deviceId]
      ?: throw GattServerException(
        "ERR_DEVICE_DISCONNECTED", "Device $deviceId is not connected"
      )
    val payload = responsePayload(pending, requestId, offset, value)

    // Claimed before the response goes out, not after: once the stack has it the transaction is answered,
    // and an expiry already dispatched onto the main looper would answer it a second time —
    // `removeCallbacks` cannot recall one that has left the queue. A refused send therefore leaves the
    // request unanswerable, which is the lesser fault, since the stack that refused it is gone anyway.
    if (!pendingRequests.remove(key, pending)) throw unknownRequest(deviceId, requestId)
    pending.timeout?.let { timeoutHandler.removeCallbacks(it) }

    // Committed before the response goes out, so a central that reads straight after its write response
    // sees what it wrote. Only a success commits them: any ATT error rejects the whole execute, which the
    // queued-write procedure treats as one atomic operation.
    val committed = if (status == BluetoothGatt.GATT_SUCCESS) {
      commitDeferredValues(pending.deferredValues)
    } else {
      emptyMap()
    }
    // The plain descriptors of the same execute, held back for the same reason and applied at the same
    // moment — against the baseline each was assembled from, as the characteristics are. A delegated
    // execute stays open for up to `requestTimeoutMs`, and an unqueued descriptor write or another
    // device's execute can land in that window, so committing regardless reverted a newer value.
    val committedDescriptors = LinkedHashMap<BluetoothGattDescriptor, DeferredWrite>()
    if (status == BluetoothGatt.GATT_SUCCESS) {
      synchronized(attributeValueLock) {
        for ((descriptor, write) in pending.deferredDescriptors) {
          @Suppress("DEPRECATION")
          if (!descriptor.value.contentEquals(write.baseline)) {
            logDebug { "Deferred write to descriptor ${descriptor.uuid} was superseded, keeping the newer value" }
            continue
          }
          @Suppress("DEPRECATION")
          descriptor.value = write.value
          committedDescriptors[descriptor] = write
        }
      }
    }

    // The offset handed to the stack is the request's own, so it always describes where `payload` sits
    // within the attribute regardless of what the caller passed.
    if (!server.sendResponse(device, requestId, status, pending.offset, payload)) {
      // The central never received the response, so the execute did not complete for it either.
      revertDeferredValues(committed)
      synchronized(attributeValueLock) {
        for ((descriptor, write) in committedDescriptors) {
          // Only where nothing has written it since, as [revertDeferredValues] undoes a characteristic.
          @Suppress("DEPRECATION")
          if (!descriptor.value.contentEquals(write.value)) continue
          @Suppress("DEPRECATION")
          descriptor.value = write.baseline
        }
      }
      throw GattServerException(
        "ERR_RESPONSE",
        "The Bluetooth stack did not accept the response for request $requestId"
      )
    }

    // Applied only once the acceptance has actually reached the central, and only for a success: a
    // rejected execute leaves the client's configuration exactly as it was, which is what the central
    // believes. Left until after the response because each transition reports to a listener, and there is
    // nothing to undo if the send is refused.
    if (status == BluetoothGatt.GATT_SUCCESS) {
      for ((descriptor, bits) in pending.clientConfigurations) {
        applyClientConfiguration(device, descriptor.characteristic, bits)
      }
    }
  }

  /**
   * Applies the values a partially delegated execute withheld, and reports which of them were actually
   * applied.
   *
   * An attribute something else has written since the execute was assembled — `updateCharacteristicValue`
   * or another client — keeps that newer value: silently undoing a write the application already
   * completed successfully is the one outcome nothing downstream could detect or recover from.
   */
  private fun commitDeferredValues(
    deferred: Map<BluetoothGattCharacteristic, DeferredWrite>,
  ): Map<BluetoothGattCharacteristic, DeferredWrite> {
    if (deferred.isEmpty()) return emptyMap()
    val committed = LinkedHashMap<BluetoothGattCharacteristic, DeferredWrite>()
    synchronized(attributeValueLock) {
      for ((characteristic, write) in deferred) {
        @Suppress("DEPRECATION")
        if (!characteristic.value.contentEquals(write.baseline)) {
          logDebug { "Deferred write to ${characteristic.uuid} was superseded, keeping the newer value" }
          continue
        }
        @Suppress("DEPRECATION")
        characteristic.value = write.value
        committed[characteristic] = write
      }
    }
    return committed
  }

  /** Undoes [commitDeferredValues] where nothing has written the attribute since. */
  private fun revertDeferredValues(committed: Map<BluetoothGattCharacteristic, DeferredWrite>) {
    if (committed.isEmpty()) return
    synchronized(attributeValueLock) {
      for ((characteristic, write) in committed) {
        @Suppress("DEPRECATION")
        if (!characteristic.value.contentEquals(write.value)) continue
        @Suppress("DEPRECATION")
        characteristic.value = write.baseline
      }
    }
  }

  /**
   * Distinguishes a request id this device never had from one another connected device holds, which a
   * per-connection transaction id makes an ordinary occurrence rather than a corner case.
   */
  private fun unknownRequest(deviceId: String, requestId: Int): GattServerException {
    pendingRequests.keys.firstOrNull { it.requestId == requestId }?.let { other ->
      return GattServerException(
        "REQUEST_DEVICE_MISMATCH",
        "Request $requestId belongs to device ${other.deviceId}, not $deviceId"
      )
    }
    return GattServerException(
      "REQUEST_NOT_FOUND", "Request $requestId not found or already responded"
    )
  }

  /** See [expo.modules.gattserver.rebasedResponseValue], which this supplies the request's offset to. */
  private fun responsePayload(
    pending: PendingRequest,
    requestId: Int,
    offset: Int,
    value: ByteArray,
  ): ByteArray = rebasedResponseValue(
    value, pending.isRead, suppliedOffset = offset, requestedOffset = pending.offset,
    requestId = requestId
  )

  /**
   * Hands one notification to the stack. Returns `null` when it was accepted — and only then will
   * `onNotificationSent` arrive. The API 33 overload reports a `BluetoothStatusCodes` value and the older
   * one a plain boolean; both are checked, because a refused call produces no callback at all.
   */
  @SuppressLint("MissingPermission")
  private fun notifyValue(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    confirm: Boolean,
    payload: ByteArray,
  ): GattServerException? {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      val status = server.notifyCharacteristicChanged(device, characteristic, confirm, payload)
      if (status == android.bluetooth.BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
        return NotifyBusyException(
          "The Bluetooth stack is still carrying the previous notification for this device"
        )
      }
      if (status != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
        return GattServerException(
          "ERR_NOTIFY",
          "The Bluetooth stack refused the notification for ${characteristic.uuid} (status $status)"
        )
      }
      return null
    }
    // No pre-33 overload takes the payload: `notifyCharacteristicChanged(device, characteristic,
    // confirm)` reads it from `characteristic.getValue()` and rejects a null one outright. So the payload
    // is parked in the mirrored value for the duration of the call and the stored value put back
    // afterwards, which is what keeps a send from changing what a read returns here as it does on 33+.
    // Restoring cannot truncate the notification: the framework reads the field and hands the array over
    // binder before returning.
    val triggered = synchronized(attributeValueLock) {
      @Suppress("DEPRECATION")
      val stored = characteristic.value
      @Suppress("DEPRECATION")
      characteristic.value = payload
      try {
        @Suppress("DEPRECATION")
        server.notifyCharacteristicChanged(device, characteristic, confirm)
      } finally {
        @Suppress("DEPRECATION")
        characteristic.value = stored
      }
    }
    if (!triggered) {
      return GattServerException(
        "ERR_NOTIFY",
        "The Bluetooth stack could not trigger the notification for ${characteristic.uuid}"
      )
    }
    return null
  }

  /**
   * Replaces the mirrored value that a read of this characteristic is answered from. An address that
   * names nothing in the published database is reported rather than dropped, so a mistyped UUID cannot
   * look exactly like a successful update while the characteristic keeps serving its old value.
   */
  fun updateCharacteristicValue(serviceUuid: String, characteristicUuid: String, value: ByteArray) {
    val server = gattServer ?: throw serverUnavailable()
    val characteristic = server.getService(parseUuid(serviceUuid, "service"))
      ?.getCharacteristic(parseUuid(characteristicUuid, "characteristic"))
      ?: throw GattServerException(
        "ERR_CHARACTERISTIC_NOT_FOUND",
        "Characteristic $characteristicUuid was not found in service $serviceUuid"
      )
    storeCharacteristicValue(characteristic, value)
  }

  @SuppressLint("MissingPermission")
  fun stop(): Unit = synchronized(serverLifecycleLock) {
    stopped = true
    serviceFactory.set(null)
    // Clear only on stop (not adapter off); power cycles preserve values via factory.
    publishedServices.set(emptyList())
    // Stop advertising before unregistering receiver; receiver retries restoreAdapterName.
    stopAdvertising()
    unregisterStateReceiver()
    onStateChange = null
    discardPublicationRound()
    finishOpen(
      DatabasePublication.FAILED,
      GattServerException("ERR_NO_SERVER", "Server was stopped before it finished opening"),
      report = false,
    )
    // Answer pending reads/writes before close() takes device handles.
    answerAndDiscardPendingRequests(ATT_ERROR_UNLIKELY_ERROR) { true }
    gattServer?.close()
    gattServer = null
    connectedDevices.clear()
    deviceMtu.clear()
    preparedWrites.clear()
    failAllNotifications(GattServerException("ERR_NO_SERVER", "Server stopped"))
    subscriptions.clear()
    delegations.clear()
    delegationsByCharacteristic.clear()
    // Clear last. close() doesn't disconnect; STATE_DISCONNECTED or onNotificationSent may arrive after close(). Only clear listener, not timeoutHandler queue (finishOpen posts to it).
    listener = null
  }
}
