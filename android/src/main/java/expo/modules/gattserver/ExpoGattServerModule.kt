package expo.modules.gattserver

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.concurrent.Executors

class ExpoGattServerModule : Module() {
  /**
   * Written on `AsyncFunctionQueue`, read on JavaScript thread. Volatile publishes across threads
   * to prevent a stale `null` from leaking the server and broadcast receiver.
   */
  @Volatile
  private var manager: GattServerManager? = null

  /**
   * Where a `stopServer` teardown runs, so it does not hold the JavaScript thread across the binder
   * calls `stop` makes under `serverLifecycleLock`. Single-threaded: `createServer` drains it before
   * opening, so a deferred stop can never close the server that replaced it.
   */
  private val teardownExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ExpoGattServerTeardown")
  }

  /** Blocks until any deferred teardown has finished. Never called from the JavaScript thread. */
  private fun awaitPendingTeardown() {
    // Explicit Runnable: `submit {}` is ambiguous between the Runnable and Callable overloads.
    runCatching { teardownExecutor.submit(Runnable {}).get() }
  }

  /**
   * Returns rejection code and message if [permission] is not granted, or null. Checks React context
   * first: missing context is an error (not a pass) to avoid deferring the failure to a Bluetooth SecurityException.
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
      "onMtuChanged",
      "onServerPublicationFailed"
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
        parseServices(services)
        val delegations = parseDelegations(services)

        manager?.stop()
        // A stopServer already in flight would otherwise close the server opened below.
        awaitPendingTeardown()
        val mgr = GattServerManager(context, requestTimeoutMs)
        mgr.listener = createListener()
        mgr.onStateChange = { state ->
          sendEvent("onBluetoothStateChanged", bundleOf("state" to state))
        }
        mgr.setDelegations(delegations)
        manager = mgr
        // Resolves after services are confirmed registered.
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
        // Preserve GattServerException codes (e.g. ERR_BLUETOOTH) instead of flattening to ERR_CREATE_SERVER.
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
          // Parse every entry; silently dropping non-strings would lose service UUIDs and make discovery fail silently.
          serviceUuids = (config["serviceUuids"] as? List<*>)
            ?.map { parseUuid(it, "service") }
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
        // Waits for services to be registered rather than sampling state; createServer and power-on reregistration are asynchronous.
        mgr.startAdvertising(options) { error ->
          if (error != null) {
            promise.reject(error.code, error.message, error)
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
      value: List<Double>,
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

    // Declared as Double and narrowed by parseIntArgument to match iOS.
    // If declared as Int, expo-modules-core would use asDouble().toInt(), which loses NaN and truncates fractions differently per platform.
    AsyncFunction("sendResponse") {
      deviceId: String,
      rawRequestId: Double,
      rawStatus: Double,
      rawOffset: Double,
      value: List<Double>,
      promise: Promise ->
      val (requestId, status, offset) = try {
        Triple(
          parseIntArgument(
            rawRequestId, "response request id", 0, Int.MAX_VALUE,
            "A request id is the whole number the matching request event carried."
          ),
          parseIntArgument(
            rawStatus, "response status", 0, 0xFF, "An ATT error code is a single byte."
          ),
          parseIntArgument(
            rawOffset, "response offset", 0, 0xFFFF, "An ATT offset is an unsigned 16-bit value."
          ),
        )
      } catch (e: Exception) {
        promise.reject("ERR_RESPONSE", e.message, e)
        return@AsyncFunction
      }
      // REQUEST_NOT_FOUND (not ERR_NO_SERVER) so unmount-race handlers get one code to branch on across platforms.
      val mgr = manager ?: run {
        promise.reject(
          "REQUEST_NOT_FOUND", "Request $requestId not found or already responded", null
        )
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
      value: List<Double>,
      promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        // Respects the same attribute value bound as configuration.
        mgr.updateCharacteristicValue(
          serviceUuid, characteristicUuid, parseAttributeValue(value, "characteristic")
        )
        promise.resolve(null)
      } catch (e: GattServerException) {
        promise.reject(e.code, e.message, e)
      } catch (e: Exception) {
        promise.reject("ERR_UPDATE_VALUE", e.message, e)
      }
    }

    // Synchronous to preserve ordering against createServer (see serverStopEpoch in src/index.ts):
    // the manager is dropped here, on the JavaScript thread, so every later call already sees no
    // server. Only the teardown itself is deferred, since it blocks on the Bluetooth process.
    Function("stopServer") {
      val stopping = manager
      manager = null
      stopping?.let { teardownExecutor.execute { it.stop() } }
    }

    OnDestroy {
      // Synchronous: the module is going away, so the teardown has to finish before it does.
      manager?.stop()
      manager = null
      awaitPendingTeardown()
      teardownExecutor.shutdown()
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

    override fun onServerPublicationFailed(code: String, message: String) {
      sendEvent("onServerPublicationFailed", bundleOf(
        "code" to code,
        "message" to message
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
}
