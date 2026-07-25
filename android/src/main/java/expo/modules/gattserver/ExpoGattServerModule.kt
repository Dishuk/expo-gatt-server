package expo.modules.gattserver

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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

  /**
   * The rejection [permission] warrants, as a code and message, or `null` when it is held.
   *
   * A missing React context is an error rather than a pass: with nothing to check the grant against,
   * assuming it was granted only defers the failure to a `SecurityException` from the Bluetooth stack,
   * which surfaces as an unrelated crash rather than as a permission problem.
   */
  private fun permissionError(permission: String, requiredBy: String? = null): Pair<String, String>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val suffix = if (requiredBy != null) ", which $requiredBy requires" else ""
    val context = appContext.reactContext
      ?: return "ERR_NO_CONTEXT" to
        "React context not available, so the $permission permission$suffix could not be checked"
    if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
      return "ERR_PERMISSION" to "$permission permission not granted$suffix"
    }
    return null
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

    AsyncFunction("getConnectedDevices") { promise: Promise ->
      val mgr = manager ?: run {
        promise.resolve(emptyList<Any>())
        return@AsyncFunction
      }
      promise.resolve(mgr.connectedDeviceList().map { (deviceId, name) ->
        bundleOf("deviceId" to deviceId, "name" to (name ?: ""))
      })
    }

    AsyncFunction("disconnectDevice") { deviceId: String, promise: Promise ->
      permissionError(android.Manifest.permission.BLUETOOTH_CONNECT, "disconnectDevice")
        ?.let { (code, message) ->
          promise.reject(code, message, null)
          return@AsyncFunction
        }
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        mgr.disconnect(deviceId)
        promise.resolve(null)
      } catch (e: GattServerException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("ERR_DISCONNECT", e.message, e)
      }
    }

    AsyncFunction("isServerRunning") { promise: Promise ->
      promise.resolve(manager?.isServerRunning() ?: false)
    }

    AsyncFunction("isAdvertising") { promise: Promise ->
      promise.resolve(manager?.isAdvertising() ?: false)
    }

    AsyncFunction("getBluetoothState") { promise: Promise ->
      val context = appContext.reactContext
      if (context == null) {
        promise.resolve("unknown")
      } else {
        promise.resolve(currentBluetoothState(context))
      }
    }

    AsyncFunction("createServer") {
      services: List<Map<String, Any?>>,
      options: Map<String, Any?>,
      promise: Promise ->
      permissionError(android.Manifest.permission.BLUETOOTH_CONNECT)?.let { (code, message) ->
        promise.reject(code, message, null)
        return@AsyncFunction
      }

      val context = appContext.reactContext ?: run {
        promise.reject("ERR_NO_CONTEXT", "React context not available", null)
        return@AsyncFunction
      }

      try {
        val requestTimeoutMs = parseRequestTimeout(options["requestTimeoutMs"])
        manager?.stop()
        val mgr = GattServerManager(context, requestTimeoutMs)
        mgr.listener = createListener()
        mgr.onStateChange = { state ->
          sendEvent("onBluetoothStateChanged", bundleOf("state" to state))
        }
        // Parse once up front so malformed configuration rejects synchronously, then hand the
        // manager a factory it can call again to rebuild the services after a power cycle.
        parseServices(services)
        mgr.setDelegations(parseDelegations(services))
        manager = mgr
        // Resolves only once every service is confirmed registered — until then the server has no
        // attributes to expose and advertising it would be meaningless.
        mgr.open({ error ->
          if (error != null) {
            promise.reject(error.code, error.message, error)
          } else {
            promise.resolve(null)
          }
        }) {
          parseServices(services)
        }
      } catch (e: GattServerException) {
        // Keeps a specific code such as ERR_BLUETOOTH, which the generic catch below would flatten into
        // ERR_CREATE_SERVER.
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("ERR_CREATE_SERVER", e.message, e)
      }
    }

    AsyncFunction("startAdvertising") { config: Map<String, Any?>, promise: Promise ->
      permissionError(android.Manifest.permission.BLUETOOTH_ADVERTISE)?.let { (code, message) ->
        promise.reject(code, message, null)
        return@AsyncFunction
      }

      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.", null)
        return@AsyncFunction
      }
      val localName = config["localName"] as? String
      val androidOptions = config["android"] as? Map<*, *>
      val setAdapterName = androidOptions?.get("setAdapterName") as? Boolean ?: false
      // A config that asks for a name still gets one advertised — the device's own, since Android
      // has nowhere to put the requested string.
      val includeDeviceName =
        androidOptions?.get("includeDeviceName") as? Boolean ?: (localName != null)

      // `BluetoothAdapter.setName` enforces BLUETOOTH_CONNECT on API 31+, so checking here makes the
      // opt-in fail with a permission error rather than a SecurityException from the Bluetooth stack.
      if (setAdapterName) {
        permissionError(
          android.Manifest.permission.BLUETOOTH_CONNECT, "android.setAdapterName"
        )?.let { (code, message) ->
          promise.reject(code, message, null)
          return@AsyncFunction
        }
      }

      try {
        val options = AdvertiseOptions(
          localName = localName,
          serviceUuids = (config["serviceUuids"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?.map { UUID.fromString(it) }
            ?: emptyList(),
          includeTxPower = config["includeTxPowerLevel"] as? Boolean ?: false,
          connectable = config["connectable"] as? Boolean ?: true,
          includeDeviceName = includeDeviceName,
          setAdapterName = setAdapterName,
          mode = advertiseModeFor(config["mode"] as? String),
          txPowerLevel = advertiseTxPowerFor(config["txPowerLevel"] as? String),
          timeoutMs = parseAdvertisingTimeout(config["timeoutMs"]),
          manufacturerData = parseManufacturerData(config["manufacturerData"]),
          serviceData = parseServiceData(config["serviceData"]),
        )
        mgr.startAdvertising(options) { error ->
          if (error != null) {
            promise.reject("ERR_ADVERTISE", error, null)
          } else {
            promise.resolve(null)
          }
        }
      } catch (e: GattServerException) {
        promise.reject(e.code, e.message, e)
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
        // Resolves once the platform confirms delivery, so a caller that awaits it paces itself against
        // the link instead of overrunning it.
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

    AsyncFunction("updateCharacteristicValue") {
      serviceUuid: String,
      characteristicUuid: String,
      value: List<Int>,
      promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        mgr.updateCharacteristicValue(
          serviceUuid, characteristicUuid, toByteArray(value, "characteristic")
        )
        promise.resolve(null)
      } catch (e: GattServerException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("ERR_UPDATE_VALUE", e.message, e)
      }
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

  /** Anything outside 0..255 would be silently truncated by [Int.toByte]. */
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
   * Checked before `AdvertiseSettings.Builder.setTimeout` sees it, whose own message ("timeoutMillis
   * invalid") does not say which option was wrong.
   */
  private fun parseAdvertisingTimeout(value: Any?): Int {
    if (value == null) return 0
    val number = value as? Number
    val millis = number?.toInt()
    if (number == null || millis == null || number.toDouble() != millis.toDouble() ||
      millis < 0 || millis > MAX_ADVERTISING_TIMEOUT_MS
    ) {
      throw IllegalArgumentException(
        "Invalid advertising timeout $value. Expected an integer between 0 and " +
          "$MAX_ADVERTISING_TIMEOUT_MS milliseconds, where 0 means no time limit."
      )
    }
    return millis
  }

  private fun parseRequestTimeout(value: Any?): Int {
    if (value == null) return DEFAULT_REQUEST_TIMEOUT_MS
    val number = value as? Number
    val millis = number?.toInt()
    if (number == null || millis == null || number.toDouble() != millis.toDouble() ||
      millis < 0 || millis >= ATT_TRANSACTION_TIMEOUT_MS
    ) {
      throw IllegalArgumentException(
        "Invalid request timeout $value. Expected an integer between 0 and " +
          "${ATT_TRANSACTION_TIMEOUT_MS - 1} milliseconds — below the ATT transaction timeout of " +
          "$ATT_TRANSACTION_TIMEOUT_MS ms, past which the central has already given up — where 0 " +
          "disables the timeout."
      )
    }
    return millis
  }

  private fun parseManufacturerData(value: Any?): List<ManufacturerData> {
    val list = value as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      val number = map["companyId"] as? Number
      val companyId = number?.toInt()
      // 16-bit field, so a wider value is not transmissible; `addManufacturerData` only rejects
      // negative ids.
      if (number == null || companyId == null || number.toDouble() != companyId.toDouble() ||
        companyId !in 0..0xFFFF
      ) {
        throw IllegalArgumentException(
          "Invalid manufacturer company id ${map["companyId"]}. A Bluetooth SIG Company " +
            "Identifier is a 16-bit value, so it must be an integer between 0 and 65535."
        )
      }
      val data = (map["data"] as? List<*>) ?: emptyList<Any>()
      ManufacturerData(companyId, toByteArray(data, "manufacturer"))
    }
  }

  private fun parseServiceData(value: Any?): List<ServiceData> {
    val list = value as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      val uuid = UUID.fromString(map["uuid"] as String)
      val data = (map["data"] as? List<*>) ?: emptyList<Any>()
      ServiceData(uuid, toByteArray(data, "service data"))
    }
  }

  /** Absent or empty `delegate` configuration produces no entry, so the default stays fully automatic. */
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

  /**
   * `BluetoothGattServer.getService` and `BluetoothGattService.getCharacteristic` both return the first
   * match, so a repeated UUID leaves one attribute unreachable and the other addressed by both
   * spellings. Rejected in JavaScript too; repeated here because the native module is reachable
   * directly. The same characteristic UUID in *different* services stays legal, as GATT permits.
   */
  private fun parseServices(services: List<Map<String, Any?>>): List<BluetoothGattService> {
    val seen = mutableSetOf<UUID>()
    return services.map { config ->
      val service = parseServiceConfig(config)
      if (!seen.add(service.uuid)) {
        throw IllegalArgumentException(
          "Duplicate service UUID ${service.uuid}. Give each service its own UUID, or merge their " +
            "characteristics into one service."
        )
      }
      service
    }
  }

  private fun parseServiceConfig(map: Map<String, Any?>): BluetoothGattService {
    val uuid = UUID.fromString(map["uuid"] as String)
    val service = BluetoothGattService(uuid, parseServiceType(map["type"] as? String))

    val characteristics = (map["characteristics"] as? List<*>) ?: emptyList<Any>()
    val seen = mutableSetOf<UUID>()
    for (item in characteristics) {
      val charMap = item as? Map<*, *> ?: continue
      val characteristic = parseCharacteristicConfig(charMap)
      if (!seen.add(characteristic.uuid)) {
        throw IllegalArgumentException(
          "Duplicate characteristic UUID ${characteristic.uuid} in service $uuid. The same " +
            "characteristic UUID in a different service is fine."
        )
      }
      service.addCharacteristic(characteristic)
    }
    return service
  }

  private fun parseServiceType(name: String?): Int = when (name) {
    null, "primary" -> BluetoothGattService.SERVICE_TYPE_PRIMARY
    "secondary" -> BluetoothGattService.SERVICE_TYPE_SECONDARY
    else -> throw IllegalArgumentException(
      "Invalid service type \"$name\". Expected \"primary\" or \"secondary\"."
    )
  }

  private fun parseCharacteristicConfig(map: Map<*, *>): BluetoothGattCharacteristic {
    val uuid = UUID.fromString(map["uuid"] as String)
    val properties = parseProperties(map["properties"] as? List<*>)
    val permissions = parsePermissions(map["permissions"] as? List<*>)
    val characteristic = BluetoothGattCharacteristic(uuid, properties, permissions)

    if (properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
      val cccd = BluetoothGattDescriptor(
        CCCD_UUID,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
      )
      characteristic.addDescriptor(cccd)
    }

    for (item in (map["descriptors"] as? List<*>) ?: emptyList<Any>()) {
      val descriptorMap = item as? Map<*, *> ?: continue
      characteristic.addDescriptor(parseDescriptorConfig(descriptorMap))
    }

    val initialValue = (map["value"] as? List<*>)?.let { toByteArray(it, "characteristic") }
    if (initialValue != null) {
      @Suppress("DEPRECATION")
      characteristic.value = initialValue
    }

    return characteristic
  }

  /**
   * The CCCD is rejected here as well as in JavaScript, because a second instance would be published
   * alongside the module's own and shadow the per-client subscription tracking that answers it.
   */
  private fun parseDescriptorConfig(map: Map<*, *>): BluetoothGattDescriptor {
    val uuid = UUID.fromString(map["uuid"] as String)
    if (uuid == CCCD_UUID) {
      throw IllegalArgumentException(
        "Descriptor $uuid is the Client Characteristic Configuration descriptor, which the module " +
          "publishes itself for every characteristic declaring \"notify\" or \"indicate\"."
      )
    }
    // `BluetoothGattDescriptor.PERMISSION_*` and `BluetoothGattCharacteristic.PERMISSION_*` are
    // declared with identical values, so one parser serves both attribute kinds.
    val permissions = (map["permissions"] as? List<*>)
      ?.let { parsePermissions(it) }
      ?: BluetoothGattDescriptor.PERMISSION_READ
    val descriptor = BluetoothGattDescriptor(uuid, permissions)
    @Suppress("DEPRECATION")
    descriptor.value = toByteArray((map["value"] as? List<*>) ?: emptyList<Any>(), "descriptor")
    return descriptor
  }

  private fun parseProperties(list: List<*>?): Int {
    var props = 0
    list?.forEach {
      props = props or when (it as? String) {
        "read" -> BluetoothGattCharacteristic.PROPERTY_READ
        "write" -> BluetoothGattCharacteristic.PROPERTY_WRITE
        "writeNoResponse" -> BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        "notify" -> BluetoothGattCharacteristic.PROPERTY_NOTIFY
        "indicate" -> BluetoothGattCharacteristic.PROPERTY_INDICATE
        "broadcast" -> BluetoothGattCharacteristic.PROPERTY_BROADCAST
        "signedWrite" -> BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE
        "extendedProperties" -> BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS
        else -> throw IllegalArgumentException("Invalid characteristic property \"$it\".")
      }
    }
    return props
  }

  /**
   * An unrecognised name throws rather than being skipped: dropping a permission silently publishes an
   * attribute less protected than the configuration asked for.
   */
  private fun parsePermissions(list: List<*>?): Int {
    var perms = 0
    list?.forEach {
      perms = perms or when (it as? String) {
        "readable" -> BluetoothGattCharacteristic.PERMISSION_READ
        "writeable" -> BluetoothGattCharacteristic.PERMISSION_WRITE
        "readEncrypted" -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        "readEncryptedMitm" -> BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        "writeEncrypted" -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        "writeEncryptedMitm" -> BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
        "writeSigned" -> BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED
        "writeSignedMitm" -> BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM
        else -> throw IllegalArgumentException("Invalid permission \"$it\".")
      }
    }
    return perms
  }
}
