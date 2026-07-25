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
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ExpoGattServer"

/** Default ATT_MTU, in octets — Bluetooth Core Specification, Vol 3, Part G, Section 5.2.1. */
const val DEFAULT_ATT_MTU = 23

/**
 * Octets an `ATT_HANDLE_VALUE_NTF` / `ATT_HANDLE_VALUE_IND` PDU spends before the value: a
 * one-octet Attribute Opcode plus a two-octet Attribute Handle (Core Specification, Vol 3, Part F,
 * Sections 3.4.7.1 and 3.4.7.2). The value it carries is therefore at most `ATT_MTU - 3` octets.
 */
const val ATT_NOTIFICATION_HEADER_SIZE = 3

/**
 * Upper bound on notifications waiting behind the one the platform is still delivering. Only one
 * notification may be outstanding per the `onNotificationSent` contract, so without a bound a
 * producer that outruns the link would grow the queue forever. Exceeding it fails the call rather
 * than dropping a payload silently.
 */
private const val MAX_QUEUED_NOTIFICATIONS_PER_DEVICE = 64

/**
 * Client Characteristic Configuration descriptor — Bluetooth Core Specification, Vol 3, Part G,
 * Section 3.3.3.3. Its value "shall be two octets in length"; bit 0 enables notifications and bit 1
 * enables indications (Table 3.11), which is the little-endian encoding Android exposes as
 * `BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE` = `{0x01, 0x00}` and
 * `ENABLE_INDICATION_VALUE` = `{0x02, 0x00}`. The default is 0x0000.
 */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val CCCD_VALUE_LENGTH = 2
private const val CCCD_NOTIFY_BIT = 0x0001
private const val CCCD_INDICATE_BIT = 0x0002

/**
 * Longest duration `AdvertiseSettings.Builder.setTimeout` accepts — "May not exceed 180000
 * milliseconds" — the Bluetooth SIG limit the platform names `LIMITED_ADVERTISING_MAX_MILLIS`.
 */
const val MAX_ADVERTISING_TIMEOUT_MS = 180_000

/** ATT "Invalid Attribute Value Length" — Core Specification, Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0D

/** ATT "Unlikely Error" — Core Specification, Vol 3, Part F, Table 3.4. */
private const val ATT_ERROR_UNLIKELY_ERROR = 0x0E

/**
 * The ATT transaction timeout. "A transaction not completed within 30 seconds shall time out. Such a
 * transaction shall be considered to have failed [...] No more Attribute Protocol requests,
 * commands, indications or notifications shall be sent to the target device on this ATT bearer" —
 * recovering then costs a whole new bearer (Core Specification, Vol 3, Part F, Section 3.3.3). A
 * module timeout at or above it could never answer before the peer gives up, so it is the exclusive
 * upper bound on [DEFAULT_REQUEST_TIMEOUT_MS] and on the configured value.
 */
const val ATT_TRANSACTION_TIMEOUT_MS = 30_000

/**
 * How long a request delegated to JavaScript may go unanswered before the module answers it itself.
 *
 * Chosen to sit well inside [ATT_TRANSACTION_TIMEOUT_MS] — the peer is left 20 s of margin, so it
 * receives a real ATT error response and its bearer stays usable, instead of the transaction failing
 * and taking every subsequent notification and indication with it. It is still long enough for a
 * handler doing genuine asynchronous work.
 */
const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000

open class GattServerException(val code: String, message: String) : Exception(message)
class MtuException(code: String, message: String) : GattServerException(code, message)

/** Identifies a characteristic within the configured GATT database. */
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
 * The link budget for one device, expressed in the units the public API uses.
 *
 * Android reports the ATT_MTU directly through `onMtuChanged`, so [mtu] is exact and the payload
 * capacity is derived from it.
 */
data class DeviceMtu(val mtu: Int) {
  /** Octets that fit in one notification or indication: `ATT_MTU - 3`. */
  val maxNotificationPayload: Int = mtu - ATT_NOTIFICATION_HEADER_SIZE
}

/**
 * Per-characteristic opt-in delegation of ATT request handling to JavaScript. Every flag defaults
 * to `false`, which keeps the module answering the request itself.
 */
data class CharacteristicDelegation(
  val read: Boolean = false,
  val write: Boolean = false,
) {
  companion object {
    val none = CharacteristicDelegation()
  }
}

/**
 * Maps a [BluetoothAdapter] state constant onto the platform-neutral state union shared with iOS.
 * The two transitional states are reported as `resetting` because the platform documents both as
 * not yet usable, which is exactly what `resetting` means to a consumer.
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
 * `MissingPermission` is suppressed per function rather than for the whole class. The permissions
 * are checked in [ExpoGattServerModule] before anything here is reachable, but a class-level
 * suppression also hid every *new* violation — including the module's own broken check — so each
 * function that genuinely calls a guarded API opts out by name instead.
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

  private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
  private var gattServer: BluetoothGattServer? = null
  private var advertiser: BluetoothLeAdvertiser? = null
  private var advertiseCallback: AdvertiseCallback? = null
  private var pendingAdvertiseResult: ((String?) -> Unit)? = null
  // Set from the caller's thread, read again during a teardown that may be on another.
  private val originalAdapterName = AtomicReference<String?>(null)
  private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
  private val deviceMtu = ConcurrentHashMap<String, Int>()
  private val pendingRequests = ConcurrentHashMap<Int, PendingRequest>()
  // Expiry tasks are posted here from the binder threads that register the requests, and run on the
  // main looper, which always exists for the lifetime of the process.
  private val timeoutHandler = Handler(Looper.getMainLooper())
  // Android delivers one notification at a time: "When multiple notifications are to be sent, an
  // application must wait for this callback to be received before sending additional
  // notifications" (BluetoothGattServerCallback.onNotificationSent). Sends are therefore queued
  // per device and handed to the stack one at a time, each waiting for its own callback. The map
  // is touched from the caller's thread and from the binder thread that delivers the callback.
  private val notificationQueues = ConcurrentHashMap<String, NotificationQueue>()

  /**
   * A request awaiting `sendResponse`.
   *
   * [offset] is the offset the central asked for. It is retained so a response can be rebased onto
   * it, and so the offset handed back to the stack is the one the request carried rather than
   * whatever the caller happened to pass.
   *
   * [timeout] is the armed expiry task, kept so answering or discarding the request can cancel it.
   */
  private data class PendingRequest(
    val deviceId: String,
    val offset: Int,
    val isRead: Boolean,
    val timeout: Runnable? = null,
  )

  /** One notification waiting for, or occupying, the single outstanding slot a device has. */
  private class QueuedNotification(
    val device: BluetoothDevice,
    val characteristic: BluetoothGattCharacteristic,
    val characteristicUuid: String,
    val confirm: Boolean,
    val value: ByteArray,
    val onResult: (GattServerException?) -> Unit,
  )

  /** Per-device send queue. Every field is read and written under the instance's own monitor. */
  private class NotificationQueue {
    val waiting = ArrayDeque<QueuedNotification>()
    var inFlight: QueuedNotification? = null
  }

  // "Each client has its own instantiation of the Client Characteristic Configuration. Reads of
  // the Client Characteristic Configuration only shows the configuration for that client and
  // writes only affect the configuration of that client" (Core Specification, Vol 3, Part G,
  // Section 3.3.3.3). The framework hands out one shared BluetoothGattDescriptor per
  // characteristic, so the per-client configuration is kept here instead: device address, then
  // characteristic UUID, then the raw two-octet configuration bits.
  private val subscriptions = ConcurrentHashMap<String, ConcurrentHashMap<UUID, Int>>()

  // The pre-33 `notifyCharacteristicChanged` overload reads the payload from the shared
  // `characteristic.value` field, so the write and the call have to be atomic with respect to a
  // send for another device that targets the same characteristic.
  private val legacyNotifyLock = Any()

  // Delegation is fixed for the lifetime of a server but is read from the binder threads that
  // deliver the GATT callbacks, so both maps are concurrent.
  private val delegations = ConcurrentHashMap<CharacteristicAddress, CharacteristicDelegation>()
  // Fallback for a characteristic whose owning service cannot be identified. Only populated for
  // characteristic UUIDs that occur exactly once in the configuration, so a hit is unambiguous.
  private val delegationsByCharacteristic = ConcurrentHashMap<UUID, CharacteristicDelegation>()

  // `BluetoothGattServer.addService` is asynchronous and documents "Do not add another service
  // before this callback", so services are queued and added strictly one at a time as
  // `onServiceAdded` acknowledges each one. Both fields are touched from the caller's thread and
  // from the binder thread that delivers `onServiceAdded`.
  private val pendingServices = ConcurrentLinkedQueue<BluetoothGattService>()
  private val openCompletion = AtomicReference<((String?) -> Unit)?>(null)

  // The adapter being disabled invalidates the whole server, so the service configuration is
  // retained as a factory and fresh BluetoothGattService instances are built for every
  // registration pass. Re-adding the previously registered instances would reuse the instance IDs
  // the framework assigned them, and the platform does not document that as supported.
  private val serviceFactory = AtomicReference<(() -> List<BluetoothGattService>)?>(null)

  /**
   * Records which characteristics hand their ATT requests to JavaScript. Call before [open]; the
   * configuration is retained across the server rebuilds that an adapter power cycle triggers.
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

  private fun delegationFor(characteristic: BluetoothGattCharacteristic): CharacteristicDelegation {
    characteristic.service?.uuid?.let { serviceUuid ->
      delegations[CharacteristicAddress(serviceUuid, characteristic.uuid)]?.let { return it }
    }
    return delegationsByCharacteristic[characteristic.uuid] ?: CharacteristicDelegation.none
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
   * Disabling the adapter tears down the Bluetooth stack, which invalidates the server interface
   * this process registered. Close it explicitly so the next power-on starts from a clean server
   * rather than relying on undocumented survival of the old one.
   */
  @SuppressLint("MissingPermission")
  private fun handleAdapterOff() {
    Log.d(TAG, "Adapter off — closing GATT server")
    pendingServices.clear()
    finishOpen("Bluetooth was turned off before the server finished opening")

    advertiseCallback = null
    advertiser = null
    pendingAdvertiseResult?.invoke("Bluetooth was turned off")
    pendingAdvertiseResult = null

    gattServer?.close()
    gattServer = null

    val disconnected = connectedDevices.keys.toList()
    connectedDevices.clear()
    deviceMtu.clear()
    discardPendingRequests { true }
    failAllNotifications(GattServerException("ERR_BLUETOOTH", "Bluetooth was turned off"))
    // The server is gone, so no onConnectionStateChange callbacks will arrive for these.
    disconnected.forEach {
      clearSubscriptions(it)
      listener?.onDeviceDisconnected(it)
    }
    subscriptions.clear()
  }

  /** Re-opens the server and re-registers the retained configuration. */
  private fun handleAdapterOn() {
    if (serviceFactory.get() == null) return
    Log.d(TAG, "Adapter on — reopening GATT server and re-registering services")
    if (!openServer()) {
      Log.e(TAG, "Failed to reopen GATT server after the adapter was re-enabled")
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

  private val gattServerCallback = object : BluetoothGattServerCallback() {
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
      @Suppress("DEPRECATION")
      val value = characteristic.value
      // An opted-in characteristic always reaches JS, however current the mirrored value looks.
      val delegated = delegationFor(characteristic).read

      if (!delegated && value != null) {
        // An offset past the end of the value is answered with the error the specification
        // requires — 0x07 "Invalid Offset", `BluetoothGatt.GATT_INVALID_OFFSET` (Core
        // Specification, Vol 3, Part F, Section 3.4.1.1) — rather than handed to a listener the
        // characteristic never opted in to, which left the central waiting for its ATT transaction
        // to time out. An offset equal to the length is in range and answered with an empty value.
        if (offset > value.size) {
          Log.w(TAG, "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset past end of ${value.size}-byte value, rejecting")
          gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
          return
        }
        val responseValue = if (offset < value.size) {
          value.copyOfRange(offset, value.size)
        } else {
          ByteArray(0)
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
      // A write without a response cannot be answered at all, so it is never delegated even when
      // the characteristic opted in — there is nothing for JavaScript to reply to.
      val delegated = delegationFor(characteristic).write && responseNeeded
      Log.d(TAG, "onCharacteristicWriteRequest: device=${device.address} char=${characteristic.uuid} offset=$offset responseNeeded=$responseNeeded delegated=$delegated")

      if (delegated) {
        registerPendingRequest(requestId, device.address, offset, isRead = false)
      } else if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
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

      if (descriptor.uuid == CCCD_UUID) {
        // The specification fixes the length at two octets, so anything else is malformed and is
        // rejected rather than parsed into a guess at what the client meant.
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
        @Suppress("DEPRECATION")
        descriptor.value = value
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
      // A CCCD read "only shows the configuration for that client", so it is answered from this
      // device's own configuration rather than from the descriptor instance every client shares.
      val value = if (descriptor.uuid == CCCD_UUID) {
        cccdValue(clientConfiguration(device.address, descriptor.characteristic.uuid))
      } else {
        @Suppress("DEPRECATION")
        descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
      }
      Log.d(TAG, "onDescriptorReadRequest: device=${device.address} desc=${descriptor.uuid} value=${value.joinToString(",") { String.format("%02x", it) }}")
      gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
      val deviceId = device.address

      // The outstanding slot is free again as soon as this callback arrives, so release the
      // in-flight entry before letting the queue move on. That entry is also the only thing that
      // identifies which characteristic this callback belongs to — `onNotificationSent` reports
      // the device but not the characteristic, and the queue holds exactly one send per device.
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
        // The queue was already torn down — by a disconnect or a stop racing this callback — so
        // the characteristic it belonged to is unknowable. Reporting a guess would be worse than
        // reporting nothing.
        Log.w(TAG, "onNotificationSent: no in-flight notification for device=$deviceId status=$status")
      }
      pumpNotifications(deviceId)
    }

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        Log.e(TAG, "onServiceAdded: service=${service.uuid} failed with status=$status")
        pendingServices.clear()
        finishOpen("Failed to add service ${service.uuid} (status $status)")
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
   * Opens the GATT server and registers the services produced by [buildServices]. [onReady] is
   * invoked exactly once — with `null` once every service is confirmed registered, or with a
   * message describing the first registration failure. It may be called on a binder thread.
   *
   * [buildServices] is retained and called again whenever the server has to be rebuilt, such as
   * after the adapter is disabled and re-enabled, so it must return freshly constructed services.
   */
  fun open(onReady: (error: String?) -> Unit, buildServices: () -> List<BluetoothGattService>) {
    val adapter = bluetoothAdapter
    if (adapter == null) {
      onReady("Bluetooth not available on this device")
      return
    }
    if (!adapter.isEnabled) {
      onReady("Bluetooth is turned off")
      return
    }

    openCompletion.set(onReady)
    serviceFactory.set(buildServices)
    registerStateReceiver()

    if (!openServer()) {
      finishOpen("Unable to open GATT server")
    }
  }

  /** Opens a fresh [BluetoothGattServer] and starts registering the configured services. */
  @SuppressLint("MissingPermission")
  private fun openServer(): Boolean {
    val buildServices = serviceFactory.get() ?: return false
    val server = bluetoothManager.openGattServer(context, gattServerCallback) ?: return false
    gattServer = server
    server.clearServices()

    val services = buildServices()
    pendingServices.clear()
    pendingServices.addAll(services)
    Log.d(TAG, "Server opened, registering ${services.size} service(s)")
    addNextService()
    return true
  }

  /**
   * Adds the next queued service, or completes the open once the queue drains. Only ever called
   * from [open] or from `onServiceAdded`, so at most one `addService` is ever in flight.
   */
  @SuppressLint("MissingPermission")
  private fun addNextService() {
    val next = pendingServices.poll()
    if (next == null) {
      Log.d(TAG, "All services registered")
      finishOpen(null)
      return
    }
    val server = gattServer
    if (server == null) {
      pendingServices.clear()
      finishOpen("GATT server closed before service ${next.uuid} could be registered")
      return
    }
    // A false return means the request was never even initiated, so no callback will arrive.
    if (!server.addService(next)) {
      Log.e(TAG, "addService: could not initiate registration of ${next.uuid}")
      pendingServices.clear()
      finishOpen("Could not initiate registration of service ${next.uuid}")
    }
  }

  private fun finishOpen(error: String?) {
    openCompletion.getAndSet(null)?.invoke(error)
  }

  /**
   * Android has no per-advertisement local name: `AdvertiseData.Builder` offers only
   * `setIncludeDeviceName(boolean)`, and the name that includes is the adapter's own — the platform
   * sizes the field from `BluetoothAdapter.getNameLengthForAdvertise()`. So
   * [AdvertiseOptions.localName] is never advertised as given; only
   * [AdvertiseOptions.setAdapterName] makes the advertised name match it.
   */
  fun startAdvertising(options: AdvertiseOptions, onResult: (error: String?) -> Unit) {
    val adapter = bluetoothAdapter
      ?: throw IllegalStateException("Bluetooth not available")

    if (options.setAdapterName && options.localName == null) {
      throw IllegalArgumentException(
        "android.setAdapterName was requested without a localName for the adapter to be renamed to."
      )
    }

    pendingAdvertiseResult?.invoke("Advertising restarted")
    pendingAdvertiseResult = onResult

    if (options.setAdapterName && options.localName != null) {
      applyAdapterName(adapter, options.localName)
    }

    advertiser = adapter.bluetoothLeAdvertiser
      ?: throw IllegalStateException("BLE advertising not supported on this device")

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(options.mode)
      .setTxPowerLevel(options.txPowerLevel)
      .setConnectable(options.connectable)
      .setTimeout(options.timeoutMs)
      .build()

    // Anything a passive scanner has to see goes in the advertisement and shares its 31-byte budget;
    // the name and transmit power go in the scan response so they do not compete for it.
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
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        Log.d(TAG, "Advertising started successfully")
        pendingAdvertiseResult?.invoke(null)
        pendingAdvertiseResult = null
      }
      override fun onStartFailure(errorCode: Int) {
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
        pendingAdvertiseResult?.invoke(msg)
        pendingAdvertiseResult = null
      }
    }
    advertiseCallback = callback
    advertiser?.startAdvertising(settings, advData.build(), scanResponse, callback)
  }

  @SuppressLint("MissingPermission")
  fun stopAdvertising() {
    advertiseCallback?.let { advertiser?.stopAdvertising(it) }
    advertiseCallback = null
    pendingAdvertiseResult?.invoke("Advertising stopped")
    pendingAdvertiseResult = null
    restoreAdapterName()
  }

  /**
   * `BluetoothAdapter.setName` changes the device's system-wide Bluetooth name, not this
   * advertisement's — it is visible in the phone's own Bluetooth settings and to every peer, over
   * Classic as well as LE. Only ever called when the consumer explicitly asked for it, and undone by
   * [restoreAdapterName].
   */
  @SuppressLint("MissingPermission")
  private fun applyAdapterName(adapter: BluetoothAdapter, name: String) {
    // compareAndSet, so repeatedly restarting advertising still restores the device's own name
    // rather than the previous advertisement's.
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
      // Retained for a later attempt: `setName` fails while the adapter is off, which is exactly
      // when a teardown is most likely to run.
      Log.w(TAG, "Could not restore the adapter name to \"$previous\" yet")
    }
  }

  /**
   * Queues a notification for [deviceId] and reports the outcome through [onResult] — with `null`
   * once the platform confirms delivery through `onNotificationSent`, or with the failure that
   * stopped it. Throws only for problems detectable before the send is accepted into the queue.
   *
   * The call no longer completes as soon as the payload is handed to the stack: a device may have
   * one notification outstanding at a time, so anything sent while an earlier notification is
   * still in flight waits its turn instead of being discarded by the stack.
   *
   * [confirm] selects an indication over a notification, which the characteristic must declare the
   * matching property for; [requireSubscription] additionally refuses the send when the device has
   * not enabled that same transmission in its own CCCD. Clearing it sends anyway — the platform
   * does not consult the CCCD before transmitting, so a caller that knows better than the
   * descriptor keeps that option. The property check is not optional either way.
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
    val server = gattServer ?: throw IllegalStateException("Server not open")
    val device = connectedDevices[deviceId]
      ?: throw IllegalArgumentException("Device $deviceId not connected")

    val service = server.getService(UUID.fromString(serviceUuid))
      ?: throw IllegalArgumentException("Service $serviceUuid not found")
    val characteristicId = UUID.fromString(characteristicUuid)
    val characteristic = service.getCharacteristic(characteristicId)
      ?: throw IllegalArgumentException("Characteristic $characteristicUuid not found")

    confirmError(characteristic, confirm)?.let { throw it }

    if (requireSubscription && !hasEnabled(deviceId, characteristicId, confirm)) {
      val kind = if (confirm) "indications" else "notifications"
      throw GattServerException(
        "ERR_NO_SUBSCRIBER",
        "Device $deviceId has not enabled $kind on characteristic $characteristicUuid. Wait for " +
          "onCharacteristicSubscribed, or pass requireSubscription: false to send anyway."
      )
    }

    // Refused before the send is even queued, so an oversized payload never reaches the stack.
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
    // The device may have gone away between the check above and the queue being registered, in
    // which case nothing will ever drain it.
    if (!connectedDevices.containsKey(deviceId)) {
      failNotifications(deviceId, GattServerException("ERR_DEVICE_DISCONNECTED", "Device $deviceId disconnected"))
      return
    }
    pumpNotifications(deviceId)
  }

  /**
   * The current link budget for [deviceId], or `null` when the device is not connected.
   *
   * A device that has not negotiated an MTU is reported at the specification default rather than as
   * unknown — that default is what the link actually carries until a negotiation happens.
   */
  fun mtuFor(deviceId: String): DeviceMtu? {
    if (!connectedDevices.containsKey(deviceId)) return null
    return DeviceMtu(deviceMtu[deviceId] ?: DEFAULT_ATT_MTU)
  }

  /** Two octets, little endian, as the descriptor value is defined. */
  private fun cccdBits(value: ByteArray): Int =
    (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)

  private fun cccdValue(bits: Int): ByteArray =
    byteArrayOf((bits and 0xFF).toByte(), ((bits shr 8) and 0xFF).toByte())

  /** The two-octet configuration this client last wrote, or the specified default of 0x0000. */
  private fun clientConfiguration(deviceId: String, characteristicUuid: UUID): Int =
    subscriptions[deviceId]?.get(characteristicUuid) ?: 0

  /**
   * Reports whether [deviceId] has asked to receive updates for [characteristicUuid], by having
   * set either the notification or the indication bit of its own CCCD. This is the coarse question
   * the subscribe and unsubscribe events answer; a send asks [hasEnabled] about one specific bit.
   */
  private fun isSubscribed(deviceId: String, characteristicUuid: UUID): Boolean =
    clientConfiguration(deviceId, characteristicUuid) and
      (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) != 0

  /**
   * Reports whether [deviceId] enabled exactly the transmission [confirm] selects — the indication
   * bit for an indication, the notification bit for a notification.
   *
   * "When a bit is set, that action shall be enabled, otherwise it will not be used" (Core
   * Specification, Vol 3, Part G, Section 3.3.3.3), so a client that enabled only indications must
   * not be handed a notification, and vice versa. Gating on either bit sent whichever the caller
   * asked for regardless of what the client had actually configured.
   */
  private fun hasEnabled(deviceId: String, characteristicUuid: UUID, confirm: Boolean): Boolean {
    val required = if (confirm) CCCD_INDICATE_BIT else CCCD_NOTIFY_BIT
    return clientConfiguration(deviceId, characteristicUuid) and required != 0
  }

  /**
   * Refuses a transmission type the characteristic never declared.
   *
   * The Notify property "permits notifications of a Characteristic Value without acknowledgment"
   * and Indicate "permits indications [...] with acknowledgment" (Core Specification, Vol 3,
   * Part G, Table 3.5), and a client may set a CCCD bit "only [...] if the characteristic's
   * properties have the [matching] bit set" (Table 3.11). Android's `notifyCharacteristicChanged`
   * checks neither, so without this the stack would emit a PDU no client could legally have asked
   * for.
   */
  private fun confirmError(
    characteristic: BluetoothGattCharacteristic,
    confirm: Boolean,
  ): GattServerException? {
    val required = if (confirm) {
      BluetoothGattCharacteristic.PROPERTY_INDICATE
    } else {
      BluetoothGattCharacteristic.PROPERTY_NOTIFY
    }
    if (characteristic.properties and required != 0) return null
    val message = if (confirm) {
      "Characteristic ${characteristic.uuid} does not declare the \"indicate\" property, so it " +
        "cannot send the acknowledged indication confirm: true asks for. Declare \"indicate\" on " +
        "the characteristic, or send a notification with confirm: false."
    } else {
      "Characteristic ${characteristic.uuid} does not declare the \"notify\" property, so it " +
        "cannot send an unacknowledged notification. Declare \"notify\" on the characteristic, or " +
        "send an indication with confirm: true."
    }
    return GattServerException("ERR_CONFIRM_UNSUPPORTED", message)
  }

  /**
   * Records a client's new CCCD value and reports the transition. Only the change from "receiving
   * nothing" to "receiving something" and back is surfaced — switching between notifications and
   * indications leaves the client subscribed throughout.
   */
  private fun applyClientConfiguration(
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    bits: Int,
  ) {
    val deviceId = device.address
    val characteristicUuid = characteristic.uuid
    val serviceUuid = characteristic.service?.uuid?.toString() ?: ""
    val enabled = bits and (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) != 0
    val wasEnabled = isSubscribed(deviceId, characteristicUuid)

    if (bits == 0) {
      val forDevice = subscriptions[deviceId]
      forDevice?.remove(characteristicUuid)
      if (forDevice != null && forDevice.isEmpty()) {
        subscriptions.remove(deviceId, forDevice)
      }
    } else {
      subscriptions.getOrPut(deviceId) { ConcurrentHashMap() }[characteristicUuid] = bits
    }

    Log.d(TAG, "CCCD: device=$deviceId char=$characteristicUuid bits=$bits subscribed=$enabled")
    if (enabled && !wasEnabled) {
      listener?.onCharacteristicSubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    } else if (!enabled && wasEnabled) {
      listener?.onCharacteristicUnsubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    }
  }

  /** Forgets a device's subscriptions and reports each one it still held as ended. */
  private fun clearSubscriptions(deviceId: String) {
    val forDevice = subscriptions.remove(deviceId) ?: return
    val server = gattServer
    for ((characteristicUuid, bits) in forDevice) {
      if (bits and (CCCD_NOTIFY_BIT or CCCD_INDICATE_BIT) == 0) continue
      val serviceUuid = server?.services
        ?.firstOrNull { service -> service.getCharacteristic(characteristicUuid) != null }
        ?.uuid?.toString() ?: ""
      listener?.onCharacteristicUnsubscribed(deviceId, serviceUuid, characteristicUuid.toString())
    }
  }

  /**
   * Hands the next queued notification to the stack if the device's single outstanding slot is
   * free. Entries the stack refuses outright never produce a callback, so they are completed here
   * and the loop moves on to the next one.
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
      synchronized(queue) {
        if (queue.inFlight === next) queue.inFlight = null
      }
      next.onResult(error)
    }
  }

  /** Returns `null` when the stack accepted the send and a callback is now expected. */
  private fun dispatchNotification(deviceId: String, entry: QueuedNotification): GattServerException? {
    val server = gattServer
      ?: return GattServerException("ERR_NO_SERVER", "Server not open")
    // Re-checked as well as at enqueue time: the MTU can change while an entry waits its turn, and
    // the payload must never reach the stack if it cannot be carried intact.
    mtuErrorFor(deviceId, entry.value.size)?.let { return it }
    return notifyValue(server, entry.device, entry.characteristic, entry.confirm, entry.value)
  }

  /** Fails every queued and in-flight notification for [deviceId] and forgets the queue. */
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

  /**
   * Refuses a payload the link cannot carry in one notification, before anything is transmitted.
   *
   * The platform silently truncates an oversized notification rather than failing it — the stack
   * logs "attribute value too long, to be truncated to N" while building the
   * `ATT_HANDLE_VALUE_NTF` PDU — and a notification has no continuation mechanism, unlike a read
   * that the central can finish with a Read Blob request. Sending it would therefore lose the tail
   * with nothing to recover it.
   */
  private fun mtuErrorFor(deviceId: String, size: Int): MtuException? {
    val negotiatedMtu = deviceMtu[deviceId]
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
   * Records a request handed to JavaScript and arms the expiry that answers it if JavaScript never
   * does. Called before the event is emitted, so a listener that responds synchronously still finds
   * the request.
   */
  private fun registerPendingRequest(requestId: Int, deviceId: String, offset: Int, isRead: Boolean) {
    val timeout = if (requestTimeoutMs > 0) Runnable { expireRequest(requestId) } else null
    pendingRequests[requestId] = PendingRequest(deviceId, offset, isRead, timeout)
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
  private fun expireRequest(requestId: Int) {
    val pending = pendingRequests.remove(requestId) ?: return
    Log.w(TAG, "Request $requestId unanswered after ${requestTimeoutMs}ms, answering with an ATT error")
    val device = connectedDevices[pending.deviceId] ?: return
    gattServer?.sendResponse(device, requestId, ATT_ERROR_UNLIKELY_ERROR, pending.offset, null)
  }

  /** Forgets matching pending requests, cancelling the expiry each one armed. */
  private fun discardPendingRequests(predicate: (PendingRequest) -> Boolean) {
    val iterator = pendingRequests.entries.iterator()
    while (iterator.hasNext()) {
      val pending = iterator.next().value
      if (!predicate(pending)) continue
      pending.timeout?.let { timeoutHandler.removeCallbacks(it) }
      iterator.remove()
    }
  }

  /**
   * Answers a pending read or write request.
   *
   * A read response is deliberately not size-checked. An `ATT_READ_RSP` carries at most
   * `ATT_MTU - 1` octets and the central continues a longer value with `ATT_READ_BLOB_REQ`, which
   * arrives as another read request bearing an offset — so answering with more than fits is normal
   * ATT, not a failure. This module's own automatic read path already answers with the whole
   * remainder from the requested offset, so rejecting it here only ever penalised delegated reads
   * for behaving identically.
   *
   * [offset] states where [value] begins within the attribute, and the response is rebased onto the
   * offset the request actually asked for — so passing offset 0 with the whole value answers a Read
   * Blob continuation correctly, and passing the request's own offset with a pre-sliced value works
   * too. iOS honours the same contract.
   */
  @SuppressLint("MissingPermission")
  fun sendResponse(deviceId: String, requestId: Int, status: Int, offset: Int, value: ByteArray) {
    val server = gattServer ?: throw IllegalStateException("Server not open")
    val device = connectedDevices[deviceId]
      ?: throw IllegalArgumentException("Device $deviceId not connected")
    // Everything that could reject the call is checked before the pending entry is touched, so a
    // failed attempt leaves the request answerable instead of stranding the central until its ATT
    // transaction times out.
    val pending = pendingRequests[requestId]
      ?: throw GattServerException("REQUEST_NOT_FOUND", "Request $requestId not found or already responded")
    if (pending.deviceId != deviceId) {
      throw GattServerException(
        "REQUEST_DEVICE_MISMATCH",
        "Request $requestId belongs to device ${pending.deviceId}, not $deviceId"
      )
    }
    val payload = responsePayload(pending, requestId, offset, value)

    // The offset handed to the stack is the request's own, so it always describes where `payload`
    // sits within the attribute regardless of what the caller passed.
    if (!server.sendResponse(device, requestId, status, pending.offset, payload)) {
      throw GattServerException(
        "ERR_RESPONSE",
        "The Bluetooth stack did not accept the response for request $requestId"
      )
    }
    // Two-argument remove so a request id the framework has already reissued to another device is
    // not consumed by this call.
    if (pendingRequests.remove(requestId, pending)) {
      pending.timeout?.let { timeoutHandler.removeCallbacks(it) }
    }
  }

  /**
   * Rebases a supplied response value onto the offset the request asked for.
   *
   * The stack copies the value into the response PDU verbatim — it does not slice it by the offset,
   * which for a read response is never even transmitted — so the alignment has to happen here.
   */
  private fun responsePayload(
    pending: PendingRequest,
    requestId: Int,
    offset: Int,
    value: ByteArray,
  ): ByteArray {
    // A Write Response carries no value, so there is nothing to rebase.
    if (!pending.isRead) return value

    if (offset > pending.offset) {
      throw GattServerException(
        "ERR_RESPONSE_OFFSET",
        "Request $requestId asked for the attribute from offset ${pending.offset}, but the " +
          "response supplies it from offset $offset, which leaves the requested bytes missing. " +
          "Pass the value together with the offset it starts at — offset 0 with the whole value " +
          "always works."
      )
    }
    val skip = pending.offset - offset
    if (skip == 0) return value
    // The caller supplied nothing at or beyond the requested offset, which is the specification's
    // signal that the attribute ends there.
    if (skip >= value.size) return ByteArray(0)
    return value.copyOfRange(skip, value.size)
  }

  /**
   * Hands one notification to the stack. Returns `null` when it was accepted — and only then will
   * `onNotificationSent` arrive — or the failure that stopped it.
   *
   * The API 33 overload returns a `BluetoothStatusCodes` value and the older one a plain boolean
   * ("true, if the notification has been triggered successfully"); both are checked, because a
   * refused call produces no callback at all.
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
    val triggered = synchronized(legacyNotifyLock) {
      @Suppress("DEPRECATION")
      characteristic.value = payload
      @Suppress("DEPRECATION")
      server.notifyCharacteristicChanged(device, characteristic, confirm)
    }
    if (!triggered) {
      return GattServerException(
        "ERR_NOTIFY",
        "The Bluetooth stack could not trigger the notification for ${characteristic.uuid}"
      )
    }
    return null
  }

  fun updateCharacteristicValue(serviceUuid: String, characteristicUuid: String, value: ByteArray) {
    val service = gattServer?.getService(UUID.fromString(serviceUuid)) ?: return
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid)) ?: return
    @Suppress("DEPRECATION")
    characteristic.value = value
  }

  @SuppressLint("MissingPermission")
  fun stop() {
    unregisterStateReceiver()
    onStateChange = null
    serviceFactory.set(null)
    stopAdvertising()
    pendingServices.clear()
    finishOpen("Server stopped before it finished opening")
    gattServer?.close()
    gattServer = null
    connectedDevices.clear()
    deviceMtu.clear()
    discardPendingRequests { true }
    failAllNotifications(GattServerException("ERR_NO_SERVER", "Server stopped"))
    subscriptions.clear()
    delegations.clear()
    delegationsByCharacteristic.clear()
  }
}
