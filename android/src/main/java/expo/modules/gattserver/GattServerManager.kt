package expo.modules.gattserver

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
 * Client Characteristic Configuration descriptor — Core Spec Vol 3, Part G, §3.3.3.3. Its value is two
 * octets, little endian: bit 0 enables notifications and bit 1 indications (Table 3.11), defaulting to
 * 0x0000.
 */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
internal const val CCCD_VALUE_LENGTH = 2
internal const val CCCD_NOTIFY_BIT = 0x0001
internal const val CCCD_INDICATE_BIT = 0x0002

/** ATT "Invalid Attribute Value Length" — Core Spec Vol 3, Part F, Table 3.4. */
internal const val ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0D

/** ATT "Prepare Queue Full" — Core Spec Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_PREPARE_QUEUE_FULL = 0x09

/** ATT "Unlikely Error" — Core Spec Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_UNLIKELY_ERROR = 0x0E

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

open class GattServerException(val code: String, message: String) : Exception(message)
class MtuException(code: String, message: String) : GattServerException(code, message)

/** ERROR_GATT_WRITE_REQUEST_BUSY: stack still sending previous notification. Transient; retry without settling as failure. */
class NotifyBusyException(message: String) : GattServerException("ERR_NOTIFY", message)

data class CharacteristicAddress(val service: UUID, val characteristic: UUID)

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
 * The peripheral's lifecycle: opening the server, publishing the database, and routing every ATT
 * callback either to an automatic answer or to JavaScript.
 *
 * The concerns that have state of their own live beside it — [AttributeStore] owns the mirrored values,
 * [SubscriptionRegistry] the per-client CCCDs, [NotificationDispatcher] the send queues,
 * [PreparedWriteQueue] the queued-write procedure and [AdvertisingController] the radio.
 *
 * `MissingPermission` is suppressed per function rather than for the whole class, so a new violation
 * still surfaces. [ExpoGattServerModule] checks the permissions before anything here is reachable.
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

  private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
  private val deviceMtu = ConcurrentHashMap<String, Int>()
  private val pendingRequests = ConcurrentHashMap<RequestKey, PendingRequest>()
  // Expiry tasks are posted here from the binder threads that register the requests, and run on the main
  // looper, which always exists for the lifetime of the process.
  private val timeoutHandler = Handler(Looper.getMainLooper())

  private val values = AttributeStore()
  private val subscriptions = SubscriptionRegistry()
  private val preparedWrites = PreparedWriteQueue(values)
  private val notifications =
    NotificationDispatcher(::dispatchNotification) { lifecycleHandler() }
  private val advertisingController = AdvertisingController(
    adapter = bluetoothAdapter,
    timeoutHandler = timeoutHandler,
    awaitDatabasePublished = ::whenDatabasePublished,
    isServerRunning = ::isServerRunning,
    databaseNotPublished = ::databaseNotPublished,
  )

  /**
   * The looper the server's own lifecycle work runs on: the adapter-state broadcasts, and the release of
   * everyone parked in [whenDatabasePublished].
   *
   * Both do binder work — `openGattServer`, `addService`, `close`, `setName`, `startAdvertising` — behind
   * [serverLifecycleLock], which `open` and `stop` hold across binder calls of their own. Keeping that off
   * the main thread is what stops a slow Bluetooth process turning an adapter toggle into an ANR, since a
   * `BroadcastReceiver` has around ten seconds. Created with the receiver and quit with it.
   */
  @Volatile
  private var lifecycleThread: HandlerThread? = null

  /**
   * Identifies one pending request. The device is part of the key because Android's `requestId` is the raw
   * ATT transaction id, counted per connection, so two connected centrals both produce 1, 2, 3.
   */
  private data class RequestKey(val deviceId: String, val requestId: Int)

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
   * `getService()` is set for every characteristic reached through a registered service, so the identity
   * search below is only a fallback for that field being framework-owned mutable state. It matches the
   * instance the database holds rather than guessing an owner from the UUID.
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

    advertisingController.handleAdapterOff()

    gattServer?.close()
    gattServer = null

    val disconnected = connectedDevices.keys.toList()
    connectedDevices.clear()
    deviceMtu.clear()
    discardPendingRequests { true }
    preparedWrites.discardAll()
    notifications.failAll(GattServerException("ERR_BLUETOOTH", "Bluetooth was turned off"))
    // The server is gone, so no onConnectionStateChange callback will arrive for any of these.
    disconnected.forEach {
      clearSubscriptions(it)
      listener?.onDeviceDisconnected(it)
    }
    subscriptions.clearAll()
  }

  private fun handleAdapterOn(): Unit = synchronized(serverLifecycleLock) {
    // Exceptions here are fatal (no uncaught handler); guard before arming publication timeout.
    try {
      // Undo name change before reopening; setName fails while adapter is off.
      advertisingController.restoreAdapterName()
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
          preparedWrites.discard(id)
          // Fail queued notifications; no callback will arrive.
          notifications.failFor(id, GattServerException("ERR_DEVICE_DISCONNECTED", "Device $id disconnected"))
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
      val value = values.valueOf(characteristic)
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
          values.store(characteristic, data)
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
        values.store(descriptor, value)
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
          ?.let { subscriptions.configurationOf(device.address, it) } ?: 0
        cccdValue(bits)
      } else {
        values.valueOf(descriptor) ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
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
      notifications.onSent(deviceId, status) { characteristicUuid, reported ->
        listener?.onNotificationSent(deviceId, characteristicUuid, reported)
      }
    }

    /** Applies or discards prepared writes per execute flag. Always responds, even if queue was empty (Core Spec Vol 3, Part F, §3.4.6.3). */
    @SuppressLint("MissingPermission")
    override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
      val queued = preparedWrites.take(device.address)
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

      bluetoothUnavailable(bluetoothAdapter)?.let {
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
      // Reported through the completion rather than rethrown, so the caller's promise settles once.
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
    val retained = values.snapshot(publishedServices.get())
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
    values.restore(services, retained)
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
    bluetoothUnavailable(bluetoothAdapter)?.let {
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

  /** Adapter off closes server; report as Bluetooth problem (not ERR_NO_SERVER) for user to enable Bluetooth. */
  private fun serverUnavailable(): GattServerException =
    bluetoothUnavailable(bluetoothAdapter)
      ?: GattServerException("ERR_NO_SERVER", "The GATT server is not open")

  /** See [AdvertisingController.start]. */
  fun startAdvertising(options: AdvertiseOptions, onResult: (error: GattServerException?) -> Unit) {
    advertisingController.start(options, onResult)
  }

  fun stopAdvertising() {
    advertisingController.stop()
  }

  /**
   * Queues a notification for [deviceId] and reports the outcome through [onResult] — with `null` once
   * the platform confirms delivery through `onNotificationSent`, not when the payload is handed over.
   * A device may have one notification outstanding at a time, so later sends wait their turn. Throws only
   * for problems detectable before the send is accepted into the queue.
   *
   * [confirm] selects an indication over a notification, which the characteristic must declare the
   * matching property for. [requireSubscription] additionally refuses the send when the device has not
   * enabled that transmission in its own CCCD; clearing it sends anyway, since the platform does not
   * consult the CCCD. The property check applies either way.
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

    // Unknown service and unknown characteristic collapse into one code, and the address is checked
    // before the connection — iOS does both the same way, so a call carrying a stale deviceId *and* a
    // mistyped UUID reports the same code on either platform.
    val serviceId = parseUuid(serviceUuid, "service")
    val characteristicId = parseUuid(characteristicUuid, "characteristic")
    val characteristic = server.getService(serviceId)
      ?.getCharacteristic(characteristicId)
      ?: throw GattServerException(
        "ERR_CHARACTERISTIC_NOT_FOUND",
        "Characteristic $characteristicUuid was not found in service $serviceUuid"
      )

    confirmError(characteristic.properties, characteristic.uuid, confirm)?.let { throw it }

    val device = connectedDevices[deviceId]
      ?: throw GattServerException(
        "ERR_DEVICE_DISCONNECTED", "Device $deviceId is not connected"
      )

    val address = CharacteristicAddress(serviceId, characteristicId)
    if (requireSubscription && !subscriptions.hasEnabled(deviceId, address, confirm)) {
      val kind = if (confirm) "indications" else "notifications"
      throw GattServerException(
        "ERR_NO_SUBSCRIBER",
        "Device $deviceId has not enabled $kind on characteristic $characteristicUuid. Wait for " +
          "onCharacteristicSubscribed, or pass requireSubscription: false to send anyway."
      )
    }

    // Refused before the send is queued, so an oversized payload never reaches the stack.
    mtuErrorFor(deviceId, value.size)?.let { throw it }

    val entry =
      QueuedNotification(device, characteristic, characteristicUuid, confirm, value, onResult)
    notifications.enqueue(deviceId, entry) { connectedDevices.containsKey(deviceId) }
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

  fun isAdvertising(): Boolean = advertisingController.isAdvertising()

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
    val enabled = cccdSubscribed(bits)

    // Reported but not recorded, as iOS does: filed under the wrong service it would make a send to
    // another service's same-named characteristic look deliverable, and the stack transmits whatever it
    // is handed without consulting the CCCD.
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

    val wasEnabled = subscriptions.record(deviceId, address, bits)

    val serviceUuid = address.service.toString()
    logDebug { "CCCD: device=$deviceId service=$serviceUuid char=$characteristicUuid bits=$bits subscribed=$enabled" }
    if (enabled && !wasEnabled) {
      listener?.onCharacteristicSubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    } else if (!enabled && wasEnabled) {
      listener?.onCharacteristicUnsubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    }
  }

  private fun clearSubscriptions(deviceId: String) {
    for (address in subscriptions.clear(deviceId)) {
      listener?.onCharacteristicUnsubscribed(
        deviceId, address.service.toString(), address.characteristic.toString()
      )
    }
  }

  /** See [expo.modules.gattserver.mtuErrorFor], which this supplies the link's negotiated MTU to. */
  private fun mtuErrorFor(deviceId: String, size: Int): MtuException? =
    mtuErrorFor(deviceMtu[deviceId], size)

  /** Returns `null` when the stack accepted the send and a callback is now expected. */
  private fun dispatchNotification(deviceId: String, entry: QueuedNotification): GattServerException? {
    val server = gattServer ?: return serverUnavailable()
    // Re-checked as well as at enqueue time: the MTU can change while an entry waits its turn, and the
    // payload must never reach the stack if it cannot be carried intact.
    mtuErrorFor(deviceId, entry.value.size)?.let { return it }
    // `notifyCharacteristicChanged` throws for arguments it will not carry, and two of the three callers
    // are the binder thread and the main looper, where nothing catches — so a throw would take the
    // process down rather than fail the one send.
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
   * Holds one part of a long or reliable write until the execute arrives, and echoes it back: the
   * response "shall be set to the same value as in the corresponding ATT_PREPARE_WRITE_REQ PDU" (Core
   * Spec Vol 3, Part F, §3.4.6.2), which a Reliable Write client compares and cancels over.
   */
  @SuppressLint("MissingPermission")
  private fun queuePreparedWrite(
    device: BluetoothDevice,
    requestId: Int,
    write: PreparedWrite,
    responseNeeded: Boolean,
  ) {
    if (!preparedWrites.offer(device.address, write)) {
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
   * Executes [queued] as one atomic operation, in the order the parts were received.
   * [PreparedWriteQueue.assemble] does the whole read-modify-write in one critical section; the response
   * and the events follow it, because a listener may re-enter the module.
   */
  @SuppressLint("MissingPermission")
  private fun applyPreparedWrites(
    device: BluetoothDevice,
    requestId: Int,
    queued: List<PreparedWrite>,
  ) {
    val assembled = preparedWrites.assemble(queued) { delegationFor(it).write }

    assembled.attError?.let { attError ->
      gattServer?.sendResponse(device, requestId, attError, 0, null)
      return
    }

    // A delegated write is JavaScript's to accept or reject, so the assembled values are withheld until it
    // answers and one pending request stands for the whole atomic execute. Configuration changes ride
    // along: a subscription the same execute asked for must not survive a rejection of it.
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

    // Reported once per attribute from offset 0, rather than replaying the fragments the client split the
    // value into.
    //
    // Exactly one attribute is marked `responseNeeded`: the execute is a single request, and one pending
    // request stands for the whole batch, so one answer covers every attribute in it. The others still
    // receive their event and can commit with `updateCharacteristicValue`, but a second `sendResponse`
    // would reject with `REQUEST_NOT_FOUND`. iOS reports the same shape.
    val responder = assembled.characteristicValues.keys.firstOrNull { it in assembled.delegated }
    assembled.characteristicValues.forEach { (characteristic, value) ->
      listener?.onCharacteristicWriteRequest(
        device.address, requestId, characteristic.service?.uuid?.toString() ?: "",
        characteristic.uuid.toString(), 0, value, characteristic === responder
      )
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
   * "Unlikely Error" is the closest the specification offers for a valid request the server simply
   * failed to answer.
   */
  @SuppressLint("MissingPermission")
  private fun expireRequest(key: RequestKey, pending: PendingRequest) {
    if (!pendingRequests.remove(key, pending)) return
    Log.w(TAG, "Request ${key.requestId} unanswered after ${requestTimeoutMs}ms, answering with an ATT error")
    val device = connectedDevices[key.deviceId] ?: return
    gattServer?.sendResponse(device, key.requestId, ATT_ERROR_UNLIKELY_ERROR, pending.offset, null)
  }

  /**
   * Answers every matching request with [status] and then forgets it. Use this wherever the central can
   * still hear the response; [discardPendingRequests] is for the paths where the link is already gone.
   *
   * `stop` disconnects nobody, so dropping a request silently would stall the central's ATT bearer until
   * the 30 s transaction timeout retires it — after which nothing more may be sent on it at all (Core
   * Spec Vol 3, Part F, §3.3.3). Must therefore run while `gattServer` and `connectedDevices` are still
   * populated, before `close()`.
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
   * A read response is not size-checked: the central continues a value longer than one `ATT_READ_RSP`
   * with `ATT_READ_BLOB_REQ`, so answering with more than fits is normal ATT.
   *
   * [offset] states where [value] begins within the attribute, and the response is rebased onto the offset
   * the request asked for — so offset 0 with the whole value answers a Read Blob continuation correctly,
   * and the request's own offset with a pre-sliced value works too. iOS honours the same contract.
   */
  @SuppressLint("MissingPermission")
  fun sendResponse(deviceId: String, requestId: Int, status: Int, offset: Int, value: ByteArray) {
    // Every caller-caused rejection is checked before the pending entry is touched, so a rejected attempt
    // leaves the request answerable rather than stranding the central until its ATT transaction times out.
    //
    // Range-checked natively as well as in JavaScript, since the module is reachable directly. The
    // framework narrows `status` to a byte, so a wider value would go out as an unrelated ATT error —
    // 257 becoming 0x01 "Invalid Handle", say.
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
    val payload = rebasedResponseValue(
      value, pending.isRead, suppliedOffset = offset, requestedOffset = pending.offset,
      requestId = requestId
    )

    // Claimed before the response goes out: `removeCallbacks` cannot recall an expiry that has already
    // left the main looper's queue, and it would answer the transaction a second time. A refused send is
    // then left unanswerable, which is the lesser fault.
    if (!pendingRequests.remove(key, pending)) throw unknownRequest(deviceId, requestId)
    pending.timeout?.let { timeoutHandler.removeCallbacks(it) }

    // Committed before the response goes out, so a central that reads straight after its write sees what
    // it wrote. Only on success: any ATT error rejects the whole execute, which is one atomic operation.
    val succeeded = status == BluetoothGatt.GATT_SUCCESS
    val committed =
      if (succeeded) values.commitDeferredValues(pending.deferredValues) else emptyMap()
    val committedDescriptors =
      if (succeeded) values.commitDeferredDescriptors(pending.deferredDescriptors) else emptyMap()

    // The offset handed to the stack is the request's own, so it always describes where `payload` sits
    // within the attribute regardless of what the caller passed.
    if (!server.sendResponse(device, requestId, status, pending.offset, payload)) {
      // The central never received the response, so the execute did not complete for it either.
      values.revertDeferredValues(committed)
      values.revertDeferredDescriptors(committedDescriptors)
      throw GattServerException(
        "ERR_RESPONSE",
        "The Bluetooth stack did not accept the response for request $requestId"
      )
    }

    // Applied only once the acceptance has reached the central, and only on success: a rejected execute
    // leaves the client's configuration as the central believes it to be. After the response because each
    // transition reports to a listener, and a refused send leaves nothing to undo.
    if (succeeded) {
      for ((descriptor, bits) in pending.clientConfigurations) {
        applyClientConfiguration(device, descriptor.characteristic, bits)
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
    // No pre-33 overload takes the payload: it is read from `characteristic.getValue()`. So the payload is
    // parked there for the duration of the call and the stored value put back, keeping a send from
    // changing what a read returns as it does on 33+. Restoring cannot truncate the notification — the
    // framework hands the array over binder before returning.
    val triggered = values.transaction {
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
    values.store(characteristic, value)
  }

  @SuppressLint("MissingPermission")
  fun stop(): Unit = synchronized(serverLifecycleLock) {
    stopped = true
    serviceFactory.set(null)
    // Clear only on stop (not adapter off); power cycles preserve values via factory.
    publishedServices.set(emptyList())
    // Stop advertising before unregistering receiver; receiver retries restoreAdapterName.
    advertisingController.stop()
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
    preparedWrites.discardAll()
    notifications.failAll(GattServerException("ERR_NO_SERVER", "Server stopped"))
    subscriptions.clearAll()
    delegations.clear()
    delegationsByCharacteristic.clear()
    // Cleared last: close() does not disconnect, so STATE_DISCONNECTED or onNotificationSent may still
    // arrive. The timeoutHandler queue is left alone — finishOpen posts to it.
    listener = null
  }
}
