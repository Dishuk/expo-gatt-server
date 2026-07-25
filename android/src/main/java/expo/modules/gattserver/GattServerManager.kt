package expo.modules.gattserver

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ExpoGattServer"
private const val DEFAULT_ATT_MTU = 23
private const val ATT_HEADER_SIZE = 3

open class GattServerException(val code: String, message: String) : Exception(message)
class MtuException(code: String, message: String) : GattServerException(code, message)

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
  private var lastNotifiedCharacteristicUuid: String = ""

  // `BluetoothGattServer.addService` is asynchronous and documents "Do not add another service
  // before this callback", so services are queued and added strictly one at a time as
  // `onServiceAdded` acknowledges each one. Both fields are touched from the caller's thread and
  // from the binder thread that delivers `onServiceAdded`.
  private val pendingServices = ConcurrentLinkedQueue<BluetoothGattService>()
  private val openCompletion = AtomicReference<((String?) -> Unit)?>(null)

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

      if (value != null && offset <= value.size) {
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
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, data)
      }
      listener?.onCharacteristicWriteRequest(
        device.address, 0, serviceUuid,
        characteristic.uuid.toString(), offset, data, false
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
      listener?.onNotificationSent(device.address, lastNotifiedCharacteristicUuid, status)
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
   * Opens the GATT server and registers [services]. [onReady] is invoked exactly once — with
   * `null` once every service is confirmed registered, or with a message describing the first
   * registration failure. It may be called on a binder thread.
   */
  fun open(services: List<BluetoothGattService>, onReady: (error: String?) -> Unit) {
    openCompletion.set(onReady)

    val server = bluetoothManager.openGattServer(context, gattServerCallback)
    if (server == null) {
      finishOpen("Unable to open GATT server")
      return
    }
    gattServer = server
    server.clearServices()

    pendingServices.clear()
    pendingServices.addAll(services)
    Log.d(TAG, "Server opened, registering ${services.size} service(s)")
    addNextService()
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

  fun sendNotification(
    deviceId: String,
    serviceUuid: String,
    characteristicUuid: String,
    value: ByteArray,
    confirm: Boolean,
  ) {
    val server = gattServer ?: throw IllegalStateException("Server not open")
    val device = connectedDevices[deviceId]
      ?: throw IllegalArgumentException("Device $deviceId not connected")

    val service = server.getService(UUID.fromString(serviceUuid))
      ?: throw IllegalArgumentException("Service $serviceUuid not found")
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid))
      ?: throw IllegalArgumentException("Characteristic $characteristicUuid not found")

    lastNotifiedCharacteristicUuid = characteristicUuid

    notifyValue(server, device, characteristic, confirm, value)

    val negotiatedMtu = deviceMtu[deviceId]
    val mtu = negotiatedMtu ?: DEFAULT_ATT_MTU
    val maxPayload = mtu - ATT_HEADER_SIZE
    if (value.size > maxPayload) {
      if (negotiatedMtu == null) {
        throw MtuException("MTU_SMALL", "Payload size ${value.size} exceeds default MTU payload capacity of $maxPayload bytes. Client has not negotiated a larger MTU.")
      } else {
        throw MtuException("PAYLOAD_EXCEEDS_MTU", "Payload size ${value.size} exceeds negotiated MTU payload capacity of $maxPayload bytes (MTU: $mtu).")
      }
    }
  }

  fun sendResponse(deviceId: String, requestId: Int, status: Int, offset: Int, value: ByteArray) {
    val server = gattServer ?: throw IllegalStateException("Server not open")
    pendingRequests.remove(requestId)
      ?: throw GattServerException("REQUEST_NOT_FOUND", "Request $requestId not found or already responded")
    val device = connectedDevices[deviceId]
      ?: throw IllegalArgumentException("Device $deviceId not connected")
    server.sendResponse(device, requestId, status, offset, value)

    val negotiatedMtu = deviceMtu[deviceId]
    val mtu = negotiatedMtu ?: DEFAULT_ATT_MTU
    val maxPayload = mtu - ATT_HEADER_SIZE
    if (value.size > maxPayload) {
      if (negotiatedMtu == null) {
        throw MtuException("MTU_SMALL", "Response size ${value.size} exceeds default MTU payload capacity of $maxPayload bytes. Client has not negotiated a larger MTU.")
      } else {
        throw MtuException("PAYLOAD_EXCEEDS_MTU", "Response size ${value.size} exceeds negotiated MTU payload capacity of $maxPayload bytes (MTU: $mtu).")
      }
    }
  }

  private fun notifyValue(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    confirm: Boolean,
    payload: ByteArray,
  ) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      server.notifyCharacteristicChanged(device, characteristic, confirm, payload)
    } else {
      @Suppress("DEPRECATION")
      characteristic.value = payload
      @Suppress("DEPRECATION")
      server.notifyCharacteristicChanged(device, characteristic, confirm)
    }
  }

  fun updateCharacteristicValue(serviceUuid: String, characteristicUuid: String, value: ByteArray) {
    val service = gattServer?.getService(UUID.fromString(serviceUuid)) ?: return
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid)) ?: return
    @Suppress("DEPRECATION")
    characteristic.value = value
  }

  fun stop() {
    stopAdvertising()
    pendingServices.clear()
    finishOpen("Server stopped before it finished opening")
    gattServer?.close()
    gattServer = null
    connectedDevices.clear()
    deviceMtu.clear()
    pendingRequests.clear()
  }
}
