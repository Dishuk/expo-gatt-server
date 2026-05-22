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

private const val TAG = "ExpoGattServer"

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
  private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
  private var lastNotifiedCharacteristicUuid: String = ""

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
          listener?.onDeviceDisconnected(id)
        }
      }
    }

    override fun onCharacteristicReadRequest(
      device: BluetoothDevice, requestId: Int, offset: Int,
      characteristic: BluetoothGattCharacteristic
    ) {
      @Suppress("DEPRECATION")
      val value = characteristic.value ?: ByteArray(0)
      val responseValue = if (offset > 0 && offset < value.size) {
        value.copyOfRange(offset, value.size)
      } else {
        value
      }
      Log.d(TAG, "onCharacteristicReadRequest: device=${device.address} char=${characteristic.uuid} offset=$offset valueLen=${responseValue.size}")
      gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseValue)

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
        device.address, requestId, serviceUuid,
        characteristic.uuid.toString(), offset, data, responseNeeded
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

    override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
      // Available for future use
    }
  }

  fun open(services: List<BluetoothGattService>) {
    gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
      ?: throw IllegalStateException("Unable to open GATT server")

    gattServer?.clearServices()

    for (service in services) {
      gattServer?.addService(service)
    }
    Log.d(TAG, "Server opened with ${services.size} service(s)")
  }

  fun startAdvertising(
    localName: String?,
    serviceUuids: List<String>?,
    includeTxPower: Boolean,
    connectable: Boolean,
  ) {
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
      }
      override fun onStartFailure(errorCode: Int) {
        Log.e(TAG, "Advertising failed: errorCode=$errorCode")
      }
    }
    advertiseCallback = callback
    advertiser?.startAdvertising(settings, advData.build(), scanResponse, callback)
  }

  fun stopAdvertising() {
    advertiseCallback?.let { advertiser?.stopAdvertising(it) }
    advertiseCallback = null
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

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      server.notifyCharacteristicChanged(device, characteristic, confirm, value)
    } else {
      @Suppress("DEPRECATION")
      characteristic.value = value
      @Suppress("DEPRECATION")
      server.notifyCharacteristicChanged(device, characteristic, confirm)
    }
  }

  fun sendResponse(deviceId: String, requestId: Int, status: Int, offset: Int, value: ByteArray) {
    val device = connectedDevices[deviceId] ?: return
    gattServer?.sendResponse(device, requestId, status, offset, value)
  }

  fun updateCharacteristicValue(serviceUuid: String, characteristicUuid: String, value: ByteArray) {
    val service = gattServer?.getService(UUID.fromString(serviceUuid)) ?: return
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid)) ?: return
    @Suppress("DEPRECATION")
    characteristic.value = value
  }

  fun stop() {
    stopAdvertising()
    gattServer?.close()
    gattServer = null
    connectedDevices.clear()
  }
}
