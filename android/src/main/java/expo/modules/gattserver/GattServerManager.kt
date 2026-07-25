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
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ExpoGattServer"
private const val DEFAULT_ATT_MTU = 23
private const val ATT_HEADER_SIZE = 3

/**
 * Upper bound on notifications waiting behind the one the platform is still delivering. Only one
 * notification may be outstanding per the `onNotificationSent` contract, so without a bound a
 * producer that outruns the link would grow the queue forever. Exceeding it fails the call rather
 * than dropping a payload silently.
 */
private const val MAX_QUEUED_NOTIFICATIONS_PER_DEVICE = 64

open class GattServerException(val code: String, message: String) : Exception(message)
class MtuException(code: String, message: String) : GattServerException(code, message)

/** Identifies a characteristic within the configured GATT database. */
data class CharacteristicAddress(val service: UUID, val characteristic: UUID)

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

@SuppressLint("MissingPermission")
class GattServerManager(
  private val context: Context,
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
  }

  var listener: Listener? = null

  private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
  private var gattServer: BluetoothGattServer? = null
  private var advertiser: BluetoothLeAdvertiser? = null
  private var advertiseCallback: AdvertiseCallback? = null
  private var pendingAdvertiseResult: ((String?) -> Unit)? = null
  private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
  private val deviceMtu = ConcurrentHashMap<String, Int>()
  private val pendingRequests = ConcurrentHashMap<Int, String>()
  // Android delivers one notification at a time: "When multiple notifications are to be sent, an
  // application must wait for this callback to be received before sending additional
  // notifications" (BluetoothGattServerCallback.onNotificationSent). Sends are therefore queued
  // per device and handed to the stack one at a time, each waiting for its own callback. The map
  // is touched from the caller's thread and from the binder thread that delivers the callback.
  private val notificationQueues = ConcurrentHashMap<String, NotificationQueue>()

  /**
   * One notification waiting for, or occupying, the single outstanding slot a device has.
   *
   * [deferredError] carries a failure that must not stop the payload going out — the MTU checks
   * report a payload the link will truncate, and the caller is told about it once the send
   * completes rather than instead of the send happening.
   */
  private class QueuedNotification(
    val device: BluetoothDevice,
    val characteristic: BluetoothGattCharacteristic,
    val characteristicUuid: String,
    val confirm: Boolean,
    val value: ByteArray,
    val onResult: (GattServerException?) -> Unit,
  ) {
    var deferredError: GattServerException? = null
  }

  /** Per-device send queue. Every field is read and written under the instance's own monitor. */
  private class NotificationQueue {
    val waiting = ArrayDeque<QueuedNotification>()
    var inFlight: QueuedNotification? = null
  }

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
    pendingRequests.clear()
    failAllNotifications(GattServerException("ERR_BLUETOOTH", "Bluetooth was turned off"))
    // The server is gone, so no onConnectionStateChange callbacks will arrive for these.
    disconnected.forEach { listener?.onDeviceDisconnected(it) }
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
          pendingRequests.entries.removeIf { it.value == id }
          // Nothing will ever acknowledge these now, so fail them instead of leaking the queue.
          failNotifications(id, GattServerException("ERR_DEVICE_DISCONNECTED", "Device $id disconnected"))
          listener?.onDeviceDisconnected(id)
        }
      }
    }

    override fun onCharacteristicReadRequest(
      device: BluetoothDevice, requestId: Int, offset: Int,
      characteristic: BluetoothGattCharacteristic
    ) {
      @Suppress("DEPRECATION")
      val value = characteristic.value
      // An opted-in characteristic always reaches JS, however current the mirrored value looks.
      val delegated = delegationFor(characteristic).read

      if (!delegated && value != null && offset <= value.size) {
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
      pendingRequests[requestId] = device.address

      val serviceUuid = characteristic.service?.uuid?.toString() ?: ""
      listener?.onCharacteristicReadRequest(
        device.address, requestId, serviceUuid,
        characteristic.uuid.toString(), offset
      )
    }

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
        // Registered before the event is emitted so a listener that responds synchronously still
        // finds the request.
        pendingRequests[requestId] = device.address
      } else if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
      }

      listener?.onCharacteristicWriteRequest(
        device.address, requestId, serviceUuid,
        characteristic.uuid.toString(), offset, data, delegated
      )
    }

    override fun onDescriptorWriteRequest(
      device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
    ) {
      Log.d(TAG, "onDescriptorWriteRequest: device=${device.address} desc=${descriptor.uuid} responseNeeded=$responseNeeded value=${value?.joinToString(",") { String.format("%02x", it) }}")
      if (value != null) {
        @Suppress("DEPRECATION")
        descriptor.value = value
      }
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
      }
    }

    override fun onDescriptorReadRequest(
      device: BluetoothDevice, requestId: Int, offset: Int,
      descriptor: BluetoothGattDescriptor
    ) {
      @Suppress("DEPRECATION")
      val value = descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
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
          finished.deferredError
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

  fun startAdvertising(
    localName: String?,
    serviceUuids: List<String>?,
    includeTxPower: Boolean,
    connectable: Boolean,
    onResult: (error: String?) -> Unit,
  ) {
    pendingAdvertiseResult?.invoke("Advertising restarted")
    pendingAdvertiseResult = onResult

    val adapter = bluetoothAdapter
      ?: throw IllegalStateException("Bluetooth not available")

    if (localName != null) {
      adapter.name = localName
    }

    advertiser = adapter.bluetoothLeAdvertiser
      ?: throw IllegalStateException("BLE advertising not supported on this device")

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
      .setConnectable(connectable)
      .setTimeout(0)
      .build()

    val advData = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .setIncludeTxPowerLevel(false)

    serviceUuids?.forEach { uuid ->
      advData.addServiceUuid(ParcelUuid(UUID.fromString(uuid)))
    }

    val scanResponse = AdvertiseData.Builder()
      .setIncludeDeviceName(localName != null)
      .setIncludeTxPowerLevel(includeTxPower)
      .build()

    val callback = object : AdvertiseCallback() {
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        Log.d(TAG, "Advertising started successfully")
        pendingAdvertiseResult?.invoke(null)
        pendingAdvertiseResult = null
      }
      override fun onStartFailure(errorCode: Int) {
        val msg = when (errorCode) {
          ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertise data too large"
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

  fun stopAdvertising() {
    advertiseCallback?.let { advertiser?.stopAdvertising(it) }
    advertiseCallback = null
    pendingAdvertiseResult?.invoke("Advertising stopped")
    pendingAdvertiseResult = null
  }

  /**
   * Queues a notification for [deviceId] and reports the outcome through [onResult] — with `null`
   * once the platform confirms delivery through `onNotificationSent`, or with the failure that
   * stopped it. Throws only for problems detectable before the send is accepted into the queue.
   *
   * The call no longer completes as soon as the payload is handed to the stack: a device may have
   * one notification outstanding at a time, so anything sent while an earlier notification is
   * still in flight waits its turn instead of being discarded by the stack.
   */
  fun sendNotification(
    deviceId: String,
    serviceUuid: String,
    characteristicUuid: String,
    value: ByteArray,
    confirm: Boolean,
    onResult: (GattServerException?) -> Unit,
  ) {
    val server = gattServer ?: throw IllegalStateException("Server not open")
    val device = connectedDevices[deviceId]
      ?: throw IllegalArgumentException("Device $deviceId not connected")

    val service = server.getService(UUID.fromString(serviceUuid))
      ?: throw IllegalArgumentException("Service $serviceUuid not found")
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid))
      ?: throw IllegalArgumentException("Characteristic $characteristicUuid not found")

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
    entry.deferredError = mtuErrorFor(deviceId, entry.value.size, "Payload")
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
   * Reports a payload the link cannot carry intact. The value is still transmitted — the platform
   * truncates it — so this describes what went out rather than replacing it.
   */
  private fun mtuErrorFor(deviceId: String, size: Int, subject: String): MtuException? {
    val negotiatedMtu = deviceMtu[deviceId]
    val mtu = negotiatedMtu ?: DEFAULT_ATT_MTU
    val maxPayload = mtu - ATT_HEADER_SIZE
    if (size <= maxPayload) return null
    return if (negotiatedMtu == null) {
      MtuException("MTU_SMALL", "$subject size $size exceeds default MTU payload capacity of $maxPayload bytes. Client has not negotiated a larger MTU.")
    } else {
      MtuException("PAYLOAD_EXCEEDS_MTU", "$subject size $size exceeds negotiated MTU payload capacity of $maxPayload bytes (MTU: $mtu).")
    }
  }

  fun sendResponse(deviceId: String, requestId: Int, status: Int, offset: Int, value: ByteArray) {
    val server = gattServer ?: throw IllegalStateException("Server not open")
    val device = connectedDevices[deviceId]
      ?: throw IllegalArgumentException("Device $deviceId not connected")
    // Everything that could reject the call is checked before the pending entry is touched, so a
    // failed attempt leaves the request answerable instead of stranding the central until its ATT
    // transaction times out.
    val owner = pendingRequests[requestId]
      ?: throw GattServerException("REQUEST_NOT_FOUND", "Request $requestId not found or already responded")
    if (owner != deviceId) {
      throw GattServerException(
        "REQUEST_DEVICE_MISMATCH",
        "Request $requestId belongs to device $owner, not $deviceId"
      )
    }

    if (!server.sendResponse(device, requestId, status, offset, value)) {
      throw GattServerException(
        "ERR_RESPONSE",
        "The Bluetooth stack did not accept the response for request $requestId"
      )
    }
    // Two-argument remove so a request id the framework has already reissued to another device is
    // not consumed by this call.
    pendingRequests.remove(requestId, deviceId)

    mtuErrorFor(deviceId, value.size, "Response")?.let { throw it }
  }

  /**
   * Hands one notification to the stack. Returns `null` when it was accepted — and only then will
   * `onNotificationSent` arrive — or the failure that stopped it.
   *
   * The API 33 overload returns a `BluetoothStatusCodes` value and the older one a plain boolean
   * ("true, if the notification has been triggered successfully"); both are checked, because a
   * refused call produces no callback at all.
   */
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
    pendingRequests.clear()
    failAllNotifications(GattServerException("ERR_NO_SERVER", "Server stopped"))
    delegations.clear()
    delegationsByCharacteristic.clear()
  }
}
