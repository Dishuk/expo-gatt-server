package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.core.os.bundleOf
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.UUID

class ExpoGattServerModule : Module() {
  private var manager: GattServerManager? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoGattServer")

    Events(
      "onDeviceConnected",
      "onDeviceDisconnected",
      "onCharacteristicReadRequest",
      "onCharacteristicWriteRequest",
      "onNotificationSent"
    )

    AsyncFunction("createServer") { services: List<Map<String, Any?>>, promise: Promise ->
      val context = appContext.reactContext ?: run {
        promise.reject("ERR_NO_CONTEXT", "React context not available", null)
        return@AsyncFunction
      }

      try {
        manager?.stop()
        val mgr = GattServerManager(context)
        mgr.listener = createListener()
        val gattServices = services.map { parseServiceConfig(it) }
        mgr.open(gattServices)
        manager = mgr
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("ERR_CREATE_SERVER", e.message, e)
      }
    }

    AsyncFunction("startAdvertising") { config: Map<String, Any?>, promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.", null)
        return@AsyncFunction
      }
      try {
        val localName = config["localName"] as? String
        val serviceUuids = (config["serviceUuids"] as? List<*>)?.mapNotNull { it as? String }
        val includeTxPower = config["includeTxPowerLevel"] as? Boolean ?: false
        val connectable = config["connectable"] as? Boolean ?: true
        mgr.startAdvertising(localName, serviceUuids, includeTxPower, connectable)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("ERR_ADVERTISE", e.message, e)
      }
    }

    Function("stopAdvertising") {
      manager?.stopAdvertising()
    }

    AsyncFunction("sendNotification") {
      deviceId: String,
      serviceUuid: String,
      characteristicUuid: String,
      value: List<Int>,
      confirm: Boolean,
      promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        val bytes = value.map { it.toByte() }.toByteArray()
        mgr.sendNotification(deviceId, serviceUuid, characteristicUuid, bytes, confirm)
        promise.resolve(null)
      } catch (e: Exception) {
        promise.reject("ERR_NOTIFY", e.message, e)
      }
    }

    Function("sendResponse") {
      deviceId: String,
      requestId: Int,
      status: Int,
      offset: Int,
      value: List<Int> ->
      val bytes = value.map { it.toByte() }.toByteArray()
      manager?.sendResponse(deviceId, requestId, status, offset, bytes)
    }

    Function("updateCharacteristicValue") {
      serviceUuid: String,
      characteristicUuid: String,
      value: List<Int> ->
      val bytes = value.map { it.toByte() }.toByteArray()
      manager?.updateCharacteristicValue(serviceUuid, characteristicUuid, bytes)
    }

    Function("stopServer") {
      manager?.stop()
      manager = null
    }

    OnDestroy {
      manager?.stop()
      manager = null
    }
  }

  private fun createListener() = object : GattServerManager.Listener {
    override fun onDeviceConnected(deviceId: String, name: String?) {
      sendEvent("onDeviceConnected", bundleOf(
        "deviceId" to deviceId,
        "name" to (name ?: "")
      ))
    }

    override fun onDeviceDisconnected(deviceId: String) {
      sendEvent("onDeviceDisconnected", bundleOf(
        "deviceId" to deviceId
      ))
    }

    override fun onCharacteristicReadRequest(
      deviceId: String, requestId: Int, serviceUuid: String,
      characteristicUuid: String, offset: Int
    ) {
      sendEvent("onCharacteristicReadRequest", bundleOf(
        "deviceId" to deviceId,
        "requestId" to requestId,
        "serviceUuid" to serviceUuid,
        "characteristicUuid" to characteristicUuid,
        "offset" to offset
      ))
    }

    override fun onCharacteristicWriteRequest(
      deviceId: String, requestId: Int, serviceUuid: String,
      characteristicUuid: String, offset: Int, value: ByteArray, responseNeeded: Boolean
    ) {
      sendEvent("onCharacteristicWriteRequest", bundleOf(
        "deviceId" to deviceId,
        "requestId" to requestId,
        "serviceUuid" to serviceUuid,
        "characteristicUuid" to characteristicUuid,
        "offset" to offset,
        "value" to value.map { it.toInt() and 0xFF }.toIntArray(),
        "responseNeeded" to responseNeeded
      ))
    }

    override fun onNotificationSent(deviceId: String, characteristicUuid: String, status: Int) {
      sendEvent("onNotificationSent", bundleOf(
        "deviceId" to deviceId,
        "characteristicUuid" to characteristicUuid,
        "status" to status
      ))
    }
  }

  private fun parseServiceConfig(map: Map<String, Any?>): BluetoothGattService {
    val uuid = UUID.fromString(map["uuid"] as String)
    val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

    val characteristics = (map["characteristics"] as? List<*>) ?: emptyList<Any>()
    for (item in characteristics) {
      val charMap = item as? Map<*, *> ?: continue
      service.addCharacteristic(parseCharacteristicConfig(charMap))
    }
    return service
  }

  private fun parseCharacteristicConfig(map: Map<*, *>): BluetoothGattCharacteristic {
    val uuid = UUID.fromString(map["uuid"] as String)
    val properties = parseProperties(map["properties"] as? List<*>)
    val permissions = parsePermissions(map["permissions"] as? List<*>)
    val characteristic = BluetoothGattCharacteristic(uuid, properties, permissions)

    if (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
      val cccd = android.bluetooth.BluetoothGattDescriptor(
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ or
          android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE
      )
      characteristic.addDescriptor(cccd)
    }

    val initialValue = (map["value"] as? List<*>)?.mapNotNull { (it as? Number)?.toByte() }?.toByteArray()
    if (initialValue != null) {
      @Suppress("DEPRECATION")
      characteristic.value = initialValue
    }

    return characteristic
  }

  private fun parseProperties(list: List<*>?): Int {
    var props = 0
    list?.forEach {
      when (it as? String) {
        "read" -> props = props or BluetoothGattCharacteristic.PROPERTY_READ
        "write" -> props = props or BluetoothGattCharacteristic.PROPERTY_WRITE
        "writeNoResponse" -> props = props or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        "notify" -> props = props or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        "indicate" -> props = props or BluetoothGattCharacteristic.PROPERTY_INDICATE
      }
    }
    return props
  }

  private fun parsePermissions(list: List<*>?): Int {
    var perms = 0
    list?.forEach {
      when (it as? String) {
        "readable" -> perms = perms or BluetoothGattCharacteristic.PERMISSION_READ
        "writeable" -> perms = perms or BluetoothGattCharacteristic.PERMISSION_WRITE
      }
    }
    return perms
  }
}
