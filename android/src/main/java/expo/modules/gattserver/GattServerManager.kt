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
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ExpoGattServer"

/** Default ATT_MTU, in octets — Core Spec Vol 3, Part G, §5.2.1. */
const val DEFAULT_ATT_MTU = 23

/**
 * Octets an `ATT_HANDLE_VALUE_NTF` / `ATT_HANDLE_VALUE_IND` PDU spends before the value: a one-octet
 * Attribute Opcode plus a two-octet Attribute Handle (Core Spec Vol 3, Part F, §§3.4.7.1–3.4.7.2).
 */
const val ATT_NOTIFICATION_HEADER_SIZE = 3

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

/**
 * How long one registration round may go unacknowledged before the publication is reported failed.
 *
 * `addService` is local bookkeeping that completes in milliseconds, so nothing healthy comes close to
 * this. It exists only so a round that can never finish is *reported*: `onServiceAdded` is the sole thing
 * that advances the queue, and an `addService` that returns true without ever calling back left
 * `createServer` and every caller parked in [GattServerManager.whenDatabasePublished] pending for the
 * life of the process. Deliberately generous, and matched on iOS.
 */
private const val PUBLICATION_TIMEOUT_MS = 30_000L

open class GattServerException(val code: String, message: String) : Exception(message)
class MtuException(code: String, message: String) : GattServerException(code, message)

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
  /** Octets that fit in one notification or indication: `ATT_MTU - 3`. */
  val maxNotificationPayload: Int = mtu - ATT_NOTIFICATION_HEADER_SIZE
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
  }

  var listener: Listener? = null

  // `as?` rather than a cast: a device with no Bluetooth returns null from `getSystemService`, and a
  // failed cast in a property initialiser throws out of the constructor — so the very device
  // [bluetoothUnavailable] exists to report was answered with ERR_CREATE_SERVER and a
  // ClassCastException message instead of the documented ERR_BLUETOOTH. `currentBluetoothState` already
  // reads the service this way.
  private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  @Volatile
  private var gattServer: BluetoothGattServer? = null
  // The advertising callbacks are posted to the main looper — `BluetoothLeAdvertiser` wraps them in
  // `mHandler.post` — while starts arrive on the module's queue, `stopAdvertising` on the JS thread and the
  // adapter teardown on the receiver's. So the callback and the completion are claimed atomically: without
  // that, a start and a stop both read the same completion and settle one Promise twice, which throws.
  private val advertiser = AtomicReference<BluetoothLeAdvertiser?>(null)
  private val advertiseCallback = AtomicReference<AdvertiseCallback?>(null)
  private val pendingAdvertiseResult = AtomicReference<((GattServerException?) -> Unit)?>(null)

  // Bumped by every stop, so a start still waiting for the database can tell that the application asked
  // for the opposite while it waited. Written from the JS thread and from the receiver's binder thread,
  // read on whichever thread releases the parked caller.
  private val advertisingGeneration = AtomicInteger(0)

  // Answers public queries from the caller's thread while being written from the binder threads that
  // deliver the adapter state broadcast and the advertising callbacks.
  private val advertising = AtomicBoolean(false)
  private val advertisingTimeout = AtomicReference<Runnable?>(null)
  // Set from the caller's thread, read again during a teardown that may be on another.
  private val originalAdapterName = AtomicReference<String?>(null)
  private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
  private val deviceMtu = ConcurrentHashMap<String, Int>()
  private val pendingRequests = ConcurrentHashMap<RequestKey, PendingRequest>()
  // Expiry tasks are posted here from the binder threads that register the requests, and run on the main
  // looper, which always exists for the lifetime of the process.
  private val timeoutHandler = Handler(Looper.getMainLooper())
  // Android delivers one notification at a time: an application "must wait for this callback to be
  // received before sending additional notifications" (onNotificationSent). Sends are therefore queued
  // per device and handed to the stack one at a time. The map is touched from the caller's thread and
  // from the binder thread that delivers the callback.
  private val notificationQueues = ConcurrentHashMap<String, NotificationQueue>()

  // "Each client's queued values are separate; the execution of one queue shall not affect the
  // preparation or execution of any other client's queued values" (Core Spec Vol 3, Part F, §3.4.6.1),
  // so the queue is keyed by device. Every mutation goes through `compute`, whose bin lock is what makes
  // the lists safe against the binder threads that deliver prepares, executes and disconnects.
  private val preparedWrites = ConcurrentHashMap<String, MutableList<PreparedWrite>>()

  /**
   * Identifies one pending request. The device is part of the key because Android's `requestId` is the raw
   * ATT transaction id, which AOSP assigns from a counter on the per-connection transport control block
   * (`p_cmd->trans_id = ++tcb.trans_id` in `system/stack/gatt/gatt_sr.cc`) and `BluetoothGattServer` passes
   * through untouched — so two connected centrals both produce 1, 2, 3 and would otherwise collide.
   */
  private data class RequestKey(val deviceId: String, val requestId: Int)

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
    /**
     * The Client Characteristic Configuration changes the same execute carried, withheld for the same
     * reason. Applying them up front let a rejected batch still leave the client subscribed — and its
     * subscribe event already delivered — so the server and the central disagreed about an execute the
     * central saw refused.
     */
    val clientConfigurations: List<Pair<BluetoothGattDescriptor, Int>> = emptyList(),
  ) {
    // Assigned once, immediately after construction, because the expiry has to name the entry it expires.
    // Volatile because it is armed on a binder thread and read from the main looper and the caller's.
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
  )

  /** Every field is read and written under the instance's own monitor. */
  private class NotificationQueue {
    val waiting = ArrayDeque<QueuedNotification>()
    var inFlight: QueuedNotification? = null
  }

  // "Each client has its own instantiation of the Client Characteristic Configuration" and reads and
  // writes of it only concern that client (Core Spec Vol 3, Part G, §3.3.3.3). The framework hands out
  // one shared BluetoothGattDescriptor per characteristic, so the per-client configuration is kept here
  // instead: device address, then the characteristic's address, then the raw two-octet configuration
  // bits.
  //
  // Keyed by service as well as characteristic, because GATT permits the same characteristic UUID in two
  // services: under a UUID-only key one instance's configuration answered for the other's, so a send to a
  // characteristic nobody had subscribed to passed the subscription check and went out over the air.
  private val subscriptions =
    ConcurrentHashMap<String, ConcurrentHashMap<CharacteristicAddress, Int>>()

  // `BluetoothGattCharacteristic.value` and `BluetoothGattDescriptor.value` are plain non-volatile fields
  // the framework never synchronises, and the characteristic's is also — on the pre-33
  // `notifyCharacteristicChanged` overload — the payload a send reads. So every access goes through this
  // monitor, reads included: one only writers took would order nothing for the binder threads that answer
  // reads. It is also what makes a read-modify-write a single step, which the pre-33 park-notify-restore
  // sequence and the prepared-write assembly both need.
  //
  // A stored value is only ever replaced, never mutated in place, so a reference read under the monitor
  // stays usable after it is released.
  //
  // Nothing that can re-enter the module runs under it: `sendResponse` and every listener callback happen
  // after it is released. The pre-33 notify is the sole exception, because the framework takes the payload
  // from the field and there is no other way to make that pair atomic.
  private val attributeValueLock = Any()

  // Delegation is fixed for the lifetime of a server but is read from the binder threads that deliver
  // the GATT callbacks, so both maps are concurrent.
  private val delegations = ConcurrentHashMap<CharacteristicAddress, CharacteristicDelegation>()
  // Fallback for a characteristic whose owning service cannot be identified. Only populated for
  // characteristic UUIDs that occur exactly once in the configuration, so a hit is unambiguous.
  private val delegationsByCharacteristic = ConcurrentHashMap<UUID, CharacteristicDelegation>()

  // `BluetoothGattServer.addService` is asynchronous and documents "Do not add another service before
  // this callback", so services are queued and added strictly one at a time. Both fields are touched
  // from the caller's thread and from the binder thread that delivers `onServiceAdded`.
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

  // The state and the callers parked on it share one monitor, so a caller cannot be appended in the
  // window just after the transition that would have released it. Nothing that can re-enter the module
  // runs under it: every parked caller is invoked after it is released.
  private val publicationLock = Any()
  private var publication = DatabasePublication.IDLE
  private val readinessWaiters = mutableListOf<(GattServerException?) -> Unit>()

  /** Identifies the current registration round, so a bound armed for one cannot fail another. */
  private val publicationRound = AtomicInteger(0)

  /** The bound on the current round. See [PUBLICATION_TIMEOUT_MS]. */
  private val publicationTimeout = AtomicReference<Runnable?>(null)

  // The adapter being disabled invalidates the whole server, so the configuration is retained as a
  // factory and fresh BluetoothGattService instances are built for every registration pass. Re-adding
  // the previously registered instances would reuse the instance IDs the framework assigned them, which
  // the platform does not document as supported.
  private val serviceFactory = AtomicReference<(() -> List<BluetoothGattService>)?>(null)

  // The instances the latest registration round built, retained past the server that held them so the
  // values they accumulated can be carried onto the fresh instances the next round produces. Replaced as
  // a whole, so a reference read here stays usable.
  private val publishedServices = AtomicReference<List<BluetoothGattService>>(emptyList())

  /**
   * Call before [open]. The configuration is retained across the server rebuilds that an adapter power
   * cycle triggers.
   */
  fun setDelegations(map: Map<CharacteristicAddress, CharacteristicDelegation>) {
    delegations.clear()
    delegationsByCharacteristic.clear()

  /**
   * Serialises the four entry points that open or close the `BluetoothGattServer`: [open], [stop],
   * [handleAdapterOn] and [handleAdapterOff].
   *
   * The class otherwise reads as if the server lifecycle were single-threaded, and it is not. [open]
   * runs on Expo's `AsyncFunctionQueue` HandlerThread; the adapter handlers run on the main thread,
   * because `registerReceiver` is called without one; [stop] runs on the JS thread, since `stopServer`
   * is a synchronous `Function`. Nothing serialised them, so a `createServer` racing a `STATE_ON`
   * broadcast could run `openGattServer` twice against the same shared callback — one of the two
   * servers then had no reference left to close it, leaking a GATT interface registration for the life
   * of the process while still serving a live copy of the database, and both rounds drove
   * `addNextService` over the same `pendingServices`.
   *
   * Held across the binder calls into the Bluetooth process, which is what makes the pairing of
   * `gattServer` with its round indivisible. Ordering with [publicationLock] is one-way — code holding
   * this may take that, never the reverse — so the two cannot deadlock.
   */
  private val serverLifecycleLock = Any()
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

  /** Invoked for every adapter state change while the server is open. */
  var onStateChange: ((String) -> Unit)? = null

  private val stateReceiverRegistered = AtomicBoolean(false)

  private val stateReceiver = object : BroadcastReceiver() {
    override fun onReceive(receiverContext: Context?, intent: Intent?) {
      if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
      val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
      Log.d(TAG, "Adapter state changed to $state")
      onStateChange?.invoke(normalizedBluetoothState(state))

      when (state) {
        BluetoothAdapter.STATE_OFF -> handleAdapterOff()
        BluetoothAdapter.STATE_ON -> handleAdapterOn()
      }
    }
  }

  /**
   * Disabling the adapter tears down the Bluetooth stack, which invalidates the server interface this
   * process registered. Closing it explicitly means the next power-on starts from a clean server rather
   * than relying on undocumented survival of the old one.
   */
  @SuppressLint("MissingPermission")
  private fun handleAdapterOff(): Unit = synchronized(serverLifecycleLock) {
    Log.d(TAG, "Adapter off — closing GATT server")
    pendingServices.clear()
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
    // The later attempt [restoreAdapterName] logs about when it fails. `setName` cannot succeed while the
    // adapter is off, which is exactly when a teardown is most likely to run, so a rename that
    // `android.setAdapterName` made would otherwise survive the power cycle that prevented its undo.
    restoreAdapterName()
    if (serviceFactory.get() == null) return
    Log.d(TAG, "Adapter on — reopening GATT server and re-registering services")
    if (!openServer()) {
      Log.e(TAG, "Failed to reopen GATT server after the adapter was re-enabled")
      // Nothing retries until the adapter cycles again, so anyone parked is told rather than left there.
      finishOpen(
        DatabasePublication.FAILED,
        GattServerException(
          "ERR_CREATE_SERVER", "Could not reopen the GATT server after Bluetooth was turned back on"
        )
      )
    }
  }

  private fun registerStateReceiver() {
    if (!stateReceiverRegistered.compareAndSet(false, true)) return
    context.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
  }

  private fun unregisterStateReceiver() {
    if (!stateReceiverRegistered.compareAndSet(true, false)) return
    runCatching { context.unregisterReceiver(stateReceiver) }
      .onFailure { Log.w(TAG, "Failed to unregister adapter state receiver", it) }
  }

  /**
   * Builds the callback for one publication round.
   *
   * One instance per `openGattServer` rather than one shared across every server this manager opens, so
   * a callback can tell which round it belongs to. `onServiceAdded` carries no server or round of its
   * own, so a late acknowledgement from a server that an adapter power cycle already closed was
   * indistinguishable from one belonging to the round now running — and being indistinguishable, it was
   * acted on: a success popped an entry off the new round's queue out of turn, putting two `addService`
   * calls in flight against the platform's "do not add another service before this callback" rule, and a
   * failure tore down a healthy round.
   */
  private fun gattServerCallback(round: Int) = object : BluetoothGattServerCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      val id = device.address
      Log.d(TAG, "onConnectionStateChange: device=$id status=$status newState=$newState")
      when (newState) {
        BluetoothGattServer.STATE_CONNECTED -> {
          connectedDevices[id] = device
          listener?.onDeviceConnected(id, device.name)
        }
        BluetoothGattServer.STATE_DISCONNECTED -> {
          connectedDevices.remove(id)
          deviceMtu.remove(id)
          discardPendingRequests { it.deviceId == id }
          // Losing every ATT bearer for a client clears its prepare queue without executing any of it
          // (Core Spec Vol 3, Part F, §3.4.6.1).
          preparedWrites.remove(id)
          // Nothing will ever acknowledge these now, so fail them instead of leaking the queue.
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
      // An opted-in characteristic always reaches JS, however current the mirrored value looks.
      val delegated = delegationFor(characteristic).read

      if (!delegated && value != null) {
        // An offset past the end is answered with `GATT_INVALID_OFFSET`, 0x07 (Core Spec Vol 3, Part F,
        // §3.4.1.1), rather than handed to a listener the characteristic never opted in to, which left
        // the central waiting for its ATT transaction to time out. An offset equal to the length is in
        // range and answered with an empty value.
        val responseValue = readSliceAt(value, offset)
        if (responseValue == null) {
          Log.w(TAG, "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset past end of ${value.size}-byte value, rejecting")
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
          return
        }
        Log.d(TAG, "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset auto-respond valueLen=${responseValue.size}")
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseValue)
        return
      }

      Log.d(TAG, "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset delegating to JS")
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

      // A write without a response cannot be answered at all, so it is never delegated even when the
      // characteristic opted in — there is nothing for JavaScript to reply to.
      val delegatesWrite = delegationFor(characteristic).write
      val delegated = delegatesWrite && responseNeeded
      Log.d(TAG, "onCharacteristicWriteRequest: device=${device.address} char=${characteristic.uuid} offset=$offset responseNeeded=$responseNeeded delegated=$delegated")

      if (delegated) {
        registerPendingRequest(requestId, device.address, offset, isRead = false)
      } else {
        // Stored so a later read serves what was written, as it does on iOS. Replaced rather than
        // spliced at `offset`: an unqueued write carries no offset — `ATT_WRITE_REQ` has only a handle
        // and a value — and "the attribute value shall be truncated or lengthened to match the length
        // of the Attribute Value parameter" (Core Spec Vol 3, Part F, §3.4.5.1), so a shorter write
        // shortens the attribute. An opted-in characteristic keeps its value JavaScript's to commit
        // with updateCharacteristicValue, including for a write-without-response nothing can answer.
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
      Log.d(TAG, "onDescriptorWriteRequest: device=${device.address} desc=${descriptor.uuid} responseNeeded=$responseNeeded value=${value?.joinToString(",") { String.format("%02x", it) }}")

      if (preparedWrite) {
        queuePreparedWrite(
          device, requestId,
          PreparedWrite.ToDescriptor(descriptor, offset, value ?: ByteArray(0)),
          responseNeeded
        )
        return
      }

      if (descriptor.uuid == CCCD_UUID) {
        // The specification fixes the length at two octets, so anything else is malformed and rejected
        // rather than parsed into a guess at what the client meant.
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
      // A CCCD read "only shows the configuration for that client", so it is answered from this device's
      // own configuration rather than from the descriptor instance every client shares.
      // An address that cannot be named reads as the specified default of 0x0000, which is also what a
      // client that never configured this characteristic is entitled to see.
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
      // Sliced and bounds-checked exactly as a characteristic read is: a user description long enough to
      // need a Read Blob continuation would otherwise be answered with the whole value again at every
      // offset, and the central would reassemble a repeated prefix.
      val responseValue = readSliceAt(value, offset)
      if (responseValue == null) {
        Log.w(TAG, "onDescriptorReadRequest: device=${device.address} desc=${descriptor.uuid} offset=$offset past end of ${value.size}-byte value, rejecting")
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        return
      }
      Log.d(TAG, "onDescriptorReadRequest: device=${device.address} desc=${descriptor.uuid} offset=$offset value=${responseValue.joinToString(",") { String.format("%02x", it) }}")
      gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseValue)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
      val deviceId = device.address

      // The in-flight entry is the only thing that identifies which characteristic this callback belongs
      // to: `onNotificationSent` reports the device but not the characteristic, and the queue holds
      // exactly one send per device.
      val queue = notificationQueues[deviceId]
      val finished = queue?.let {
        synchronized(it) {
          val entry = it.inFlight
          it.inFlight = null
          entry
        }
      }
      if (finished != null) {
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
        // The queue was already torn down — by a disconnect or a stop racing this callback — so the
        // characteristic it belonged to is unknowable, and a guess would be worse than nothing.
        Log.w(TAG, "onNotificationSent: no in-flight notification for device=$deviceId status=$status")
      }
      pumpNotifications(deviceId)
    }

    /**
     * Applies or discards everything the device prepared, as the execute's flag directs.
     *
     * Either way the queue is cleared and a response must be sent, including when nothing was queued
     * (Core Spec Vol 3, Part F, §3.4.6.3) — Android surfaces the flag as [execute] and requires the
     * response like any other request.
     */
    @SuppressLint("MissingPermission")
    override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
      val queued = preparedWrites.remove(device.address) ?: emptyList<PreparedWrite>()
      Log.d(TAG, "onExecuteWrite: device=${device.address} execute=$execute queued=${queued.size}")

      if (!execute) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        return
      }
      applyPreparedWrites(device, requestId, queued)
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.e(TAG, "onServiceAdded: service=${service.uuid} failed with status=$status")
        pendingServices.clear()
        finishOpen(DatabasePublication.FAILED, GattServerException(
          "ERR_CREATE_SERVER", "Failed to add service ${service.uuid} (status $status)"
        ))
        return
      }
      Log.d(TAG, "onServiceAdded: service=${service.uuid} registered")
      addNextService()
    }

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
      device?.let {
        Log.d(TAG, "onMtuChanged: device=${it.address} mtu=$mtu")
        deviceMtu[it.address] = mtu
        listener?.onMtuChanged(it.address, DeviceMtu(mtu))
      }
    }
  }

  /**
   * Opens the GATT server and registers the services produced by [buildServices]. [onReady] is invoked
   * exactly once — with `null` once every service is confirmed registered, or with the first
   * registration failure — and may be called on a binder thread.
   *
   * [buildServices] is retained and called again whenever the server has to be rebuilt, such as after
   * the adapter is disabled and re-enabled, so it must return freshly constructed services.
   */
  fun open(
      // A registration belonging to a round that has since been discarded says nothing about the one
      // running now, and must not advance or fail it.
      if (publicationRound.get() != round) {
        Log.d(TAG, "onServiceAdded: ignoring service=${service.uuid} from discarded round $round")
        return
      }
    onReady: (error: GattServerException?) -> Unit,
    buildServices: () -> List<BluetoothGattService>,
  ): Unit = synchronized(serverLifecycleLock) {
    openCompletion.set(onReady)
    // Both installed before the adapter is checked, so a server created while Bluetooth happens to be
    // off still publishes itself when Bluetooth returns — which is what the module documents, and what
    // iOS does by instantiating CBPeripheralManager regardless and publishing on the next `poweredOn`.
    // Registering first also means the caller receives the state changes that tell it when to retry.
    serviceFactory.set(buildServices)
    registerStateReceiver()

    bluetoothUnavailable()?.let {
      // IDLE rather than FAILED, as the adapter going down uses: a registration round is still expected,
      // so a caller arriving between the STATE_ON broadcast and that round parks rather than being
      // turned away. Nobody parks while the adapter is unusable — `whenDatabasePublished` checks that
      // first — so a device with no adapter at all cannot be left waiting on a round that never comes.
      finishOpen(DatabasePublication.IDLE, it)
      return
    }

    if (!openServer()) {
      finishOpen(
        DatabasePublication.FAILED,
        GattServerException("ERR_CREATE_SERVER", "Unable to open GATT server")
      )
    }
  }

  @SuppressLint("MissingPermission")
  private fun openServer(): Boolean {
    val buildServices = serviceFactory.get() ?: return false
    // Taken before the rebuild. The factory returns freshly constructed services carrying the configured
    // initial values again, so without this every value a central or `updateCharacteristicValue` wrote
    // would silently revert whenever the adapter was power-cycled — while iOS, which re-adds the very
    // instances it built, keeps them. Nothing in the API says a power cycle empties the database.
    val retained = currentCharacteristicValues()
    synchronized(publicationLock) { publication = DatabasePublication.IN_PROGRESS }
    // Claimed before the server exists, so the callback it is handed can name the round it serves.
    val round = publicationRound.incrementAndGet()
    val server = bluetoothManager?.openGattServer(context, gattServerCallback(round)) ?: return false
    gattServer = server
    server.clearServices()

    val services = buildServices()
    restoreCharacteristicValues(services, retained)
    publishedServices.set(services)
    pendingServices.clear()
    pendingServices.addAll(services)
    Log.d(TAG, "Server opened, registering ${services.size} service(s)")
    armPublicationTimeout(round)
    addNextService()
    return true
  }

  /** Bounds the round that is about to start. See [PUBLICATION_TIMEOUT_MS]. */
  private fun armPublicationTimeout(round: Int) {
    cancelPublicationTimeout()
    // Stamped with the round, so a bound that fires just as an adapter power cycle starts a new round
    // cannot fail the round now running.
    val timeout = Runnable {
      if (publicationRound.get() != round) return@Runnable
      Log.e(TAG, "No onServiceAdded within $PUBLICATION_TIMEOUT_MS ms; reporting the round as failed")
      pendingServices.clear()
      finishOpen(
        DatabasePublication.FAILED,
        GattServerException(
          "ERR_CREATE_SERVER",
          "The Bluetooth stack did not acknowledge a service registration within " +
            "$PUBLICATION_TIMEOUT_MS ms, so the database was not published. Call createServer again " +
            "to retry."
        ),
        onlyIf = DatabasePublication.IN_PROGRESS,
      )
    }
    publicationTimeout.set(timeout)
    timeoutHandler.postDelayed(timeout, PUBLICATION_TIMEOUT_MS)
  }

  private fun cancelPublicationTimeout() {
    publicationTimeout.getAndSet(null)?.let { timeoutHandler.removeCallbacks(it) }
  }

  /**
   * What every characteristic of the latest registration round holds now. Read from the retained service
   * instances rather than through [gattServer], because the adapter going down closes that server long
   * before the round that replaces it starts.
   */
  private fun currentCharacteristicValues(): Map<CharacteristicAddress, ByteArray> {
    val services = publishedServices.get()
    if (services.isEmpty()) return emptyMap()
    val values = HashMap<CharacteristicAddress, ByteArray>()
    synchronized(attributeValueLock) {
      for (service in services) {
        for (characteristic in service.characteristics) {
      // `onlyIf` rather than a check up here: the registration can complete between the two, and
      // reading the state separately from writing it let a bound that had already lost the race
      // overwrite `PUBLISHED` with `FAILED` — leaving `isServerRunning` false and every later
      // `startAdvertising` rejecting `ERR_NO_SERVER` for a database that really was published.
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

  /**
   * Only ever called from [open] or from `onServiceAdded`, so at most one `addService` is ever in
   * flight — which is what the platform requires.
   */
  @SuppressLint("MissingPermission")
  private fun addNextService() {
    val next = pendingServices.poll()
    if (next == null) {
      Log.d(TAG, "All services registered")
      finishOpen(DatabasePublication.PUBLISHED, null)
      return
    }
    val server = gattServer
    if (server == null) {
      pendingServices.clear()
      finishOpen(DatabasePublication.FAILED, GattServerException(
        "ERR_NO_SERVER", "The GATT server was closed before service ${next.uuid} could be registered"
      ))
      return
    }
    // A false return means the registration was never initiated, so no callback will arrive.
    if (!server.addService(next)) {
      Log.e(TAG, "addService: could not initiate registration of ${next.uuid}")
      pendingServices.clear()
      finishOpen(DatabasePublication.FAILED, GattServerException(
        "ERR_CREATE_SERVER", "Could not initiate registration of service ${next.uuid}"
      ))
    }
  }

  /**
   * Ends the current registration round: [state] is what later readiness checks see, and both `open`'s
   * completion and everyone parked in [whenDatabasePublished] are settled with [error].
   */
  private fun finishOpen(
    state: DatabasePublication,
    error: GattServerException?,
    onlyIf: DatabasePublication? = null,
  ) {
    // The round is over however it ended, so its bound goes with it.
    cancelPublicationTimeout()
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
    openCompletion.getAndSet(null)?.invoke(error)
    if (parked.isEmpty()) return
    // Released on the main looper rather than on the binder thread that delivered the last
    // `onServiceAdded`: a released caller goes straight on to make binder calls of its own — the
    // advertising start, and possibly `setName` — and the GATT callback thread must not be held for
    // those, since every later request on every connection queues behind it.
    timeoutHandler.post { parked.forEach { it(error) } }
  }

  /**
   * Invokes [onReady] once the configured services are registered, rather than sampling the state
   * synchronously: `createServer` reopens the server and re-adds every service asynchronously, and the
   * `poweredOn` broadcast that starts a re-registration is delivered before it — so a synchronous check
   * refused calls that were only early, not wrong. Mirrors iOS, whose state is not even meaningful until
   * `CBPeripheralManager` reports it.
   *
   * A registration still running parks the caller; an adapter that cannot serve one, or a round that
   * already failed, settles it immediately. May be invoked on the caller's thread or on the main looper.
   */
  private fun whenDatabasePublished(onReady: (error: GattServerException?) -> Unit) {
    // Checked before the publication state, so a powered-off adapter is still reported as the Bluetooth
    // problem it is instead of parking a caller nothing is going to release.
    bluetoothUnavailable()?.let {
      onReady(it)
    // `onlyIf` makes the test and the transition one step under the monitor, for callers that may be
    // racing the round they are trying to end. Without it the publication bound could read
    // `IN_PROGRESS`, lose the race to a registration completing on a binder thread, and still write
    // `FAILED` over the `PUBLISHED` that had just been recorded.
    var applied = true
      return
    }
    // Settled outside the monitor, so no caller ever runs while it is held.
    val failure = synchronized(publicationLock) {
      when (publication) {
        DatabasePublication.PUBLISHED -> null
    if (!applied) return
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

  /**
   * The Bluetooth-level rejection that stops the server working at all, or `null` when it can work.
   * `BluetoothAdapter.isEnabled` is annotated `@RequiresNoPermission`, so this is safe to call before any
   * grant has been checked.
   */
  private fun bluetoothUnavailable(): GattServerException? {
    val adapter = bluetoothAdapter
      ?: return GattServerException("ERR_BLUETOOTH", BLUETOOTH_UNSUPPORTED_MESSAGE)
    if (!adapter.isEnabled) {
      return GattServerException("ERR_BLUETOOTH", BLUETOOTH_OFF_MESSAGE)
    }
    return null
  }

  /**
   * The rejection an absent [gattServer] warrants. Turning the adapter off closes the server, so that is
   * reported as the Bluetooth problem it is rather than as a server nobody created — which is both what
   * iOS reports for the same situation and what tells a consumer whether re-enabling Bluetooth will fix
   * it.
   */
  private fun serverUnavailable(): GattServerException =
    bluetoothUnavailable() ?: GattServerException("ERR_NO_SERVER", "The GATT server is not open")

  /**
   * Advertises once the database is registered, holding the call until then rather than refusing it, and
   * invokes [onResult] exactly once.
   *
   * A stop that lands while the call is held rejects it with the error a stop already gives a start in
   * flight, so one stop means one thing: proceeding would put the radio on the air after the application
   * explicitly asked for the opposite.
   */
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

  /**
   * Android has no per-advertisement local name: `AdvertiseData.Builder` offers only
   * `setIncludeDeviceName(boolean)`, and the name that includes is the adapter's own. So
   * [AdvertiseOptions.localName] is never advertised as given; only [AdvertiseOptions.setAdapterName]
   * makes the advertised name match it.
   */
  private fun beginAdvertising(
    options: AdvertiseOptions,
    generation: Int,
    onResult: (error: GattServerException?) -> Unit,
  ) {
    // Re-checked here as well as in `open`: the adapter can be turned off in between, and iOS reports the
    // same situation as ERR_BLUETOOTH from its own readiness check.
    val adapter = bluetoothAdapter
      ?: throw GattServerException("ERR_BLUETOOTH", BLUETOOTH_UNSUPPORTED_MESSAGE)
    if (!adapter.isEnabled) {
      throw GattServerException("ERR_BLUETOOTH", BLUETOOTH_OFF_MESSAGE)
    }

    // A half-built or empty database is still a database scanners can connect to and discover, and a
    // registration that failed leaves exactly that behind. Checked after the adapter, so a powered-off
    // one is still reported as the Bluetooth problem it is — the order iOS uses too.
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

    // Null only for an adapter with no multi-advertisement support, the enabled check above having ruled
    // out the other cause. No amount of retrying makes it work, so it is reported as unsupported rather
    // than as a failed advertisement.
    val leAdvertiser = adapter.bluetoothLeAdvertiser
      ?: throw GattServerException(
        "ERR_UNSUPPORTED", "BLE advertising is not supported on this device"
      )
    advertiser.set(leAdvertiser)

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(options.mode)
      .setTxPowerLevel(options.txPowerLevel)
      .setConnectable(options.connectable)
      .setTimeout(options.timeoutMs)
      .build()

    // Anything a passive scanner has to see goes in the advertisement and shares its 31-byte budget; the
    // name and transmit power go in the scan response so they do not compete for it.
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
      /**
       * Whether this callback is still the manager's. One that is not belongs to an advertisement already
       * stopped or superseded, and must not touch the shared advertising state or the completion the call
       * that displaced it installed.
       */
      private fun current(): Boolean = advertiseCallback.get() === this

      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        Log.d(TAG, "Advertising started successfully")
        if (!current()) return
        advertising.set(true)
        scheduleAdvertisingTimeout(options.timeoutMs)
        finishAdvertise(null)
      }
      override fun onStartFailure(errorCode: Int) {
        if (!current()) return
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
        finishAdvertise(GattServerException("ERR_ADVERTISE", msg))
      }
    }

    // Installed only once every rejection above is out of the way, so a start that threw cannot leave a
    // completion behind for the next stop to settle a second time. The callback is swapped first,
    // because it is what `current()` tests: swapping the completion first leaves a window in which a
    // failure belonging to the displaced advertisement settles the completion this call just installed.
    val displaced = advertiseCallback.getAndSet(callback)
    pendingAdvertiseResult.getAndSet(onResult)
      ?.invoke(GattServerException("ERR_ADVERTISE", "Advertising restarted"))
    // `BluetoothLeAdvertiser` keys its advertising sets on callback identity — `mLegacyAdvertisers` is a
    // map from the `AdvertiseCallback` to the set it started — so a start with a fresh callback adds a
    // second advertisement rather than replacing the first, and the displaced one keeps broadcasting
    // with nothing left able to stop it. Stopped before the new start, so it also frees the controller
    // slot rather than counting towards ADVERTISE_FAILED_TOO_MANY_ADVERTISERS.
    displaced?.let { leAdvertiser.stopAdvertising(it) }
    cancelAdvertisingTimeout()
    try {
      leAdvertiser.startAdvertising(settings, advData.build(), scanResponse, callback)
    } catch (e: Exception) {
      // `startAdvertising` rechecks the adapter state itself and throws if it went off. The caller reports
      // that throw, so neither the completion nor the callback may be left installed for a later stop to
      // settle and stop a second time. compareAndSet, so a concurrent restart's own state is left alone.
      val ours = pendingAdvertiseResult.compareAndSet(onResult, null)
      if (advertiseCallback.compareAndSet(callback, null)) {
        // The superseded set was stopped just above, so nothing is on the air and no AdvertiseCallback
        // is coming to say so. Left set, `isAdvertising` would report an advertisement that is not
        // running until the adapter-state receiver happened to clear it.
        advertising.set(false)
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
      Log.d(TAG, "Advertising was stopped while starting — stopping the new advertisement")
      leAdvertiser.stopAdvertising(callback)
      // Taking the callback back is what stops a late onStartSuccess reporting this advertisement as
      // running; a stop that took it first has already cleared the flag.
      if (advertiseCallback.compareAndSet(callback, null)) {
        advertising.set(false)
      }
      // That stop may have settled the *previous* completion, if it landed before this one was
      // installed, so this call is settled here rather than left pending for good.
      if (pendingAdvertiseResult.compareAndSet(onResult, null)) {
        onResult(advertisingStopped())
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun stopAdvertising() {
    // Bumped before anything else, so a start released from `whenDatabasePublished` in the meantime still
    // sees this stop rather than reaching the radio behind it.
    advertisingGeneration.incrementAndGet()
    cancelAdvertisingTimeout()
    advertising.set(false)
    // Taken rather than read: only the caller that claims the callback hands it to the platform, and a start
    // still in flight learns from its absence that it has to stop the advertisement it just created.
    advertiseCallback.getAndSet(null)?.let { advertiser.get()?.stopAdvertising(it) }
    finishAdvertise(advertisingStopped())
    restoreAdapterName()
  }

  private fun advertisingStopped() = GattServerException("ERR_ADVERTISE", "Advertising stopped")

  /**
   * Settles the outstanding start exactly once, whichever thread gets there first. The outcome carries a
   * code as well as a message, so a start abandoned by something other than the advertiser — Bluetooth
   * going down, the server being stopped — is not reported as an advertising failure.
   */
  private fun finishAdvertise(error: GattServerException?) {
    pendingAdvertiseResult.getAndSet(null)?.invoke(error)
  }

  /**
   * `AdvertiseSettings.setTimeout` stops advertising at the limit without invoking `AdvertiseCallback`,
   * so [advertising] would otherwise stay set for the rest of the process. Only the flag is cleared —
   * the platform has already stopped the advertisement itself.
   */
  private fun scheduleAdvertisingTimeout(timeoutMs: Int) {
    if (timeoutMs <= 0) return
    val expiry = Runnable { advertising.set(false) }
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
    val serviceId = UUID.fromString(serviceUuid)
    val characteristicId = UUID.fromString(characteristicUuid)
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
    val queue = notificationQueues.getOrPut(deviceId) { NotificationQueue() }
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
    // The device may have gone away between the check above and the queue being registered, in which
    // case nothing would ever drain it.
    if (!connectedDevices.containsKey(deviceId)) {
      val error = GattServerException("ERR_DEVICE_DISCONNECTED", "Device $deviceId disconnected")
      failNotifications(deviceId, error)
      // Also completed straight from this entry, because every teardown removes the device before it
      // drains the queues: the queue this entry landed in may already have been detached from the map, and
      // the drain above would then find nothing and leave the awaiting promise pending for good.
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
    Log.d(TAG, "Cancelling connection to $deviceId")
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

    val wasEnabled = isSubscribed(deviceId, address)
    // Both branches go through `compute`, which holds the bin lock for the key, so the whole
    // read-modify-write is one step on the outer map. Android 13+ gives one connection several concurrent
    // ATT bearers, so two CCCD writes from the same central really do arrive on two binder threads.
    //
    // `getOrPut` is `get() ?: put()`: both threads saw no inner map, both built one, and the second
    // replaced the first — stranding whatever the loser had recorded, so the central looked unsubscribed
    // to every later send. The removal had the matching hazard: `remove(deviceId, forDevice)` matches on
    // the instance, so a subscription added between the emptiness check and the removal went with it.
    subscriptions.compute(deviceId) { _, forDevice ->
      if (bits == 0) {
        forDevice?.remove(address)
        if (forDevice.isNullOrEmpty()) null else forDevice
      } else {
        (forDevice ?: ConcurrentHashMap()).also { it[address] = bits }
      }
    }

    val serviceUuid = address.service.toString()
    Log.d(TAG, "CCCD: device=$deviceId service=$serviceUuid char=$characteristicUuid bits=$bits subscribed=$enabled")
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
      val error = dispatchNotification(deviceId, next) ?: return
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

  /** Returns `null` when the stack accepted the send and a callback is now expected. */
  private fun dispatchNotification(deviceId: String, entry: QueuedNotification): GattServerException? {
    val server = gattServer ?: return serverUnavailable()
    // Re-checked as well as at enqueue time: the MTU can change while an entry waits its turn, and the
    // payload must never reach the stack if it cannot be carried intact.
    mtuErrorFor(deviceId, entry.value.size)?.let { return it }
    return notifyValue(server, entry.device, entry.characteristic, entry.confirm, entry.value)
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
        clientConfigurations = assembled.clientConfigurations
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
    assembled.characteristicValues.forEach { (characteristic, value) ->
      listener?.onCharacteristicWriteRequest(
        device.address, requestId, characteristic.service?.uuid?.toString() ?: "",
        characteristic.uuid.toString(), 0, value, characteristic in assembled.delegated
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

      val clientConfigurations = ArrayList<Pair<BluetoothGattDescriptor, Int>>()
      for ((descriptor, value) in descriptorValues) {
        if (descriptor.uuid == CCCD_UUID) {
          clientConfigurations.add(descriptor to cccdBits(value))
        } else {
          @Suppress("DEPRECATION")
          descriptor.value = value
        }
      }

      // Decided per characteristic, as the direct write path already does: one that never opted in must
      // still have its value applied, even when a sibling in the same execute delegates.
      val delegated = characteristicValues.keys.filterTo(LinkedHashSet()) { delegationFor(it).write }
      val automatic = characteristicValues.filterKeys { it !in delegated }
      // Applied straight away only when nothing in the execute is delegated. Otherwise the execute is
      // one atomic operation that JavaScript may still reject, so these wait for its answer too.
      if (delegated.isEmpty()) {
        for ((characteristic, value) in automatic) {
          @Suppress("DEPRECATION")
          characteristic.value = value
        }
      }

      AssembledExecute(
        characteristicValues = characteristicValues,
        clientConfigurations = clientConfigurations,
        delegated = delegated,
        deferredValues = if (delegated.isEmpty()) {
          emptyMap()
        } else {
          automatic.mapValues { (characteristic, value) ->
            DeferredWrite(value, baselines[characteristic])
          }
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
  ) {
    val key = RequestKey(deviceId, requestId)
    val pending = PendingRequest(offset, isRead, deferredValues, clientConfigurations)
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

    // The offset handed to the stack is the request's own, so it always describes where `payload` sits
    // within the attribute regardless of what the caller passed.
    if (!server.sendResponse(device, requestId, status, pending.offset, payload)) {
      // The central never received the response, so the execute did not complete for it either.
      revertDeferredValues(committed)
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
          Log.d(TAG, "Deferred write to ${characteristic.uuid} was superseded, keeping the newer value")
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
    val characteristic = server.getService(UUID.fromString(serviceUuid))
      ?.getCharacteristic(UUID.fromString(characteristicUuid))
      ?: throw GattServerException(
        "ERR_CHARACTERISTIC_NOT_FOUND",
        "Characteristic $characteristicUuid was not found in service $serviceUuid"
      )
    storeCharacteristicValue(characteristic, value)
  }

  @SuppressLint("MissingPermission")
  fun stop(): Unit = synchronized(serverLifecycleLock) {
    unregisterStateReceiver()
    onStateChange = null
    serviceFactory.set(null)
    // Dropped only here, not when the adapter goes down: a power cycle rebuilds the services from the
    // factory and carries these values across, whereas a stop ends the database for good.
    publishedServices.set(emptyList())
    stopAdvertising()
    pendingServices.clear()
    finishOpen(
      DatabasePublication.FAILED,
      GattServerException("ERR_NO_SERVER", "Server was stopped before it finished opening")
    )
    gattServer?.close()
    gattServer = null
    connectedDevices.clear()
    deviceMtu.clear()
    preparedWrites.clear()
    failAllNotifications(GattServerException("ERR_NO_SERVER", "Server stopped"))
    subscriptions.clear()
    delegations.clear()
    delegationsByCharacteristic.clear()
  }
}
    // Answered rather than dropped, and before `close()` takes the server and the device handles the
    // response needs with it. `stop` disconnects nobody, so these transactions are still live.
    answerAndDiscardPendingRequests(ATT_ERROR_UNLIKELY_ERROR) { true }
