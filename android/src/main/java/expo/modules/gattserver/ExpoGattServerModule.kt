package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.UUID

class ExpoGattServerModule : Module() {
  private var manager: GattServerManager? = null

  private fun missingPermission(permission: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = appContext.reactContext ?: return false
    return ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
  }

  override fun definition() = ModuleDefinition {
    Name("ExpoGattServer")

    Events(
      "onDeviceConnected",
      "onDeviceDisconnected",
      "onCharacteristicReadRequest",
      "onCharacteristicWriteRequest",
      "onNotificationSent",
      "onCharacteristicSubscribed",
      "onCharacteristicUnsubscribed",
      "onBluetoothStateChanged",
      "onMtuChanged"
    )

    AsyncFunction("getMtu") { deviceId: String, promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      val mtu = mgr.mtuFor(deviceId) ?: run {
        promise.reject("ERR_DEVICE_DISCONNECTED", "Device $deviceId is not connected", null)
        return@AsyncFunction
      }
      promise.resolve(bundleOf(
        "deviceId" to deviceId,
        "mtu" to mtu.mtu,
        "maxNotificationPayload" to mtu.maxNotificationPayload
      ))
    }

    AsyncFunction("getBluetoothState") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.resolve("unknown")
      } else {
        promise.resolve(currentBluetoothState(context))
      }
    }

    AsyncFunction("createServer") { services: List<Map<String, Any?>>, promise: Promise ->
      if (missingPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
        promise.reject("ERR_PERMISSION", "BLUETOOTH_CONNECT permission not granted", null)
        return@AsyncFunction
      }

      val context = appContext.reactContext ?: run {
        promise.reject("ERR_NO_CONTEXT", "React context not available", null)
        return@AsyncFunction
      }

      try {
        manager?.stop()
        val mgr = GattServerManager(context)
        mgr.listener = createListener()
        mgr.onStateChange = { state ->
          sendEvent("onBluetoothStateChanged", bundleOf("state" to state))
        }
        // Parse once up front so malformed configuration rejects synchronously, then hand the
        // manager a factory it can call again to rebuild the services after a power cycle.
        services.forEach { parseServiceConfig(it) }
        mgr.setDelegations(parseDelegations(services))
        manager = mgr
        // Resolves only once every service is confirmed registered — until then the server has
        // no attributes to expose and advertising it would be meaningless.
        mgr.open({ error ->
          if (error != null) {
            promise.reject("ERR_CREATE_SERVER", error, null)
          } else {
            promise.resolve(null)
          }
        }) {
          services.map { parseServiceConfig(it) }
        }
      } catch (e: Exception) {
        promise.reject("ERR_CREATE_SERVER", e.message, e)
      }
    }

    AsyncFunction("startAdvertising") { config: Map<String, Any?>, promise: Promise ->
      if (missingPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)) {
        promise.reject("ERR_PERMISSION", "BLUETOOTH_ADVERTISE permission not granted", null)
        return@AsyncFunction
      }

      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.", null)
        return@AsyncFunction
      }
      val localName = config["localName"] as? String
      val androidOptions = config["android"] as? Map<*, *>
      val setAdapterName = androidOptions?.get("setAdapterName") as? Boolean ?: false
      // A configuration that asks for a name still gets one advertised by default — the device's
      // own, since Android has no per-advertisement local name to put the requested string in.
      val includeDeviceName =
        androidOptions?.get("includeDeviceName") as? Boolean ?: (localName != null)

      // Renaming the adapter goes through `BluetoothAdapter.setName`, which enforces
      // BLUETOOTH_CONNECT on API 31+. Checked here so the opt-in fails with a permission error
      // rather than a SecurityException from the Bluetooth stack.
      if (setAdapterName && missingPermission(android.Manifest.permission.BLUETOOTH_CONNECT)) {
        promise.reject(
          "ERR_PERMISSION",
          "BLUETOOTH_CONNECT permission not granted, which android.setAdapterName requires",
          null
        )
        return@AsyncFunction
      }

      try {
        val serviceUuids = (config["serviceUuids"] as? List<*>)?.mapNotNull { it as? String }
        val includeTxPower = config["includeTxPowerLevel"] as? Boolean ?: false
        val connectable = config["connectable"] as? Boolean ?: true
        mgr.startAdvertising(
          localName, serviceUuids, includeTxPower, connectable,
          includeDeviceName, setAdapterName
        ) { error ->
          if (error != null) {
            promise.reject("ERR_ADVERTISE", error, null)
          } else {
            promise.resolve(null)
          }
        }
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
      requireSubscription: Boolean,
      promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        val bytes = toByteArray(value, "notification")
        // Resolves once the platform has confirmed the notification was delivered, so a caller
        // that awaits it can pace itself against the link instead of overrunning it.
        mgr.sendNotification(
          deviceId, serviceUuid, characteristicUuid, bytes, confirm, requireSubscription
        ) { error ->
          if (error != null) {
            promise.reject(error.code, error.message, error)
          } else {
            promise.resolve(null)
          }
        }
      } catch (e: GattServerException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("ERR_NOTIFY", e.message, e)
      }
    }

    AsyncFunction("sendResponse") {
      deviceId: String,
      requestId: Int,
      status: Int,
      offset: Int,
      value: List<Int>,
      promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        val bytes = toByteArray(value, "response")
        mgr.sendResponse(deviceId, requestId, status, offset, bytes)
        promise.resolve(null)
      } catch (e: GattServerException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("ERR_RESPONSE", e.message, e)
      }
    }

    Function("updateCharacteristicValue") {
      serviceUuid: String,
      characteristicUuid: String,
      value: List<Int> ->
      val bytes = toByteArray(value, "characteristic")
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

    override fun onMtuChanged(deviceId: String, mtu: DeviceMtu) {
      sendEvent("onMtuChanged", bundleOf(
        "deviceId" to deviceId,
        "mtu" to mtu.mtu,
        "maxNotificationPayload" to mtu.maxNotificationPayload
      ))
    }

    override fun onCharacteristicSubscribed(
      deviceId: String, serviceUuid: String, characteristicUuid: String
    ) {
      sendEvent("onCharacteristicSubscribed", bundleOf(
        "deviceId" to deviceId,
        "serviceUuid" to serviceUuid,
        "characteristicUuid" to characteristicUuid
      ))
    }

    override fun onCharacteristicUnsubscribed(
      deviceId: String, serviceUuid: String, characteristicUuid: String
    ) {
      sendEvent("onCharacteristicUnsubscribed", bundleOf(
        "deviceId" to deviceId,
        "serviceUuid" to serviceUuid,
        "characteristicUuid" to characteristicUuid
      ))
    }
  }

  /**
   * Byte arrays arrive from JS as numbers. Anything outside 0..255 would be silently
   * truncated by [Int.toByte], so reject it instead.
   */
  private fun toByteArray(value: List<*>, field: String): ByteArray {
    val bytes = ByteArray(value.size)
    value.forEachIndexed { index, element ->
      val number = element as? Number
      val intValue = number?.toInt()
      if (number == null || intValue == null ||
        number.toDouble() != intValue.toDouble() || intValue !in 0..255
      ) {
        throw IllegalArgumentException(
          "Invalid $field byte $element at index $index. " +
            "Every element must be an integer between 0 and 255."
        )
      }
      bytes[index] = intValue.toByte()
    }
    return bytes
  }

  /**
   * Collects the characteristics that opted out of the module's automatic responses. Absent or
   * empty `delegate` configuration produces no entry, so the default stays fully automatic.
   */
  private fun parseDelegations(
    services: List<Map<String, Any?>>
  ): Map<CharacteristicAddress, CharacteristicDelegation> {
    val result = mutableMapOf<CharacteristicAddress, CharacteristicDelegation>()
    for (service in services) {
      val serviceUuid = UUID.fromString(service["uuid"] as String)
      val characteristics = (service["characteristics"] as? List<*>) ?: emptyList<Any>()
      for (item in characteristics) {
        val charMap = item as? Map<*, *> ?: continue
        val delegate = charMap["delegate"] as? Map<*, *> ?: continue
        val delegation = CharacteristicDelegation(
          read = delegate["read"] as? Boolean ?: false,
          write = delegate["write"] as? Boolean ?: false,
        )
        if (delegation == CharacteristicDelegation.none) continue
        result[CharacteristicAddress(serviceUuid, UUID.fromString(charMap["uuid"] as String))] =
          delegation
      }
    }
    return result
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
        CCCD_UUID,
        android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ or
          android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE
      )
      characteristic.addDescriptor(cccd)
    }

    val initialValue = (map["value"] as? List<*>)?.let { toByteArray(it, "characteristic") }
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
