package expo.modules.gattserver

import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoGattServerModule : Module() {
  /**
   * Written by `createServer` on Expo's `AsyncFunctionQueue` and read by the synchronous `stopServer`
   * and `stopAdvertising` on the JavaScript thread, so the write has to be published across them.
   * Without `@Volatile` a `stopServer()` issued shortly after `createServer` could read a stale `null`
   * and silently do nothing, leaking the `BluetoothGattServer`, its broadcast receiver and any live
   * advertisement.
   */
  @Volatile
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
        // Parsed before the running server is touched, so a malformed configuration rejects without
        // having torn down a working one. Stopping first also left `manager` referencing the stopped
        // instance — non-null, so no later call reported ERR_NO_SERVER, and every one of them addressed
        // a server that no longer existed. The same parse is handed over as a factory below, for the
        // rebuild that follows a power cycle.
        parseServices(services)
        val delegations = parseDelegations(services)

        manager?.stop()
        val mgr = GattServerManager(context, requestTimeoutMs)
        mgr.listener = createListener()
        mgr.onStateChange = { state ->
          sendEvent("onBluetoothStateChanged", bundleOf("state" to state))
        }
        mgr.setDelegations(delegations)
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
          // Every entry parsed rather than the non-strings dropped: silently advertising one fewer
          // service UUID than the caller asked for makes the peripheral undiscoverable to a central
          // filtering on it, with nothing anywhere reporting why.
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
        // Waits for the services to be registered rather than sampling the state: `createServer` and
        // the re-registration that follows a `poweredOn` event both finish asynchronously, which
        // rejected perfectly healthy calls made straight after either. iOS parks the same way, and on
        // both the manager does the waiting, because only it can tell a stop from a genuine release.
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

    // The three numbers are declared as `Double` and narrowed by [parseIntArgument], matching iOS.
    // Declared as `Int`, expo-modules-core produces them with `asDouble().toInt()`, which turns `NaN`
    // into request 0 and truncates a fraction the iOS converter rounds — so the same call answered a
    // different request on each platform, and neither reported it. (On iOS the same conversion *traps*,
    // which is what made this the argument to fix rather than to document.)
    AsyncFunction("sendResponse") {
      deviceId: String,
      rawRequestId: Double,
      rawStatus: Double,
      rawOffset: Double,
      value: List<Int>,
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
      // `REQUEST_NOT_FOUND` rather than `ERR_NO_SERVER`, matching iOS and what `docs/api.md` documents
      // for both. Answering a request the module no longer holds is a missing request either way: with no
      // server there are no pending requests at all — `stop` answered and discarded them — so the lookup
      // below could only have failed anyway. A handler that resolves after the server was stopped, which
      // is the ordinary unmount race, therefore gets one code to branch on rather than one per platform.
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
      value: List<Int>,
      promise: Promise ->
      val mgr = manager ?: run {
        promise.reject("ERR_NO_SERVER", "Server not created", null)
        return@AsyncFunction
      }
      try {
        // The same bound a configured value gets: this is the other way an application sets an
        // attribute's value, and the specification bounds the attribute rather than the route to it.
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

    // Synchronous, which is what makes the shared layer's ordering work: `stopServer` has to be the
    // `Function` whose body runs on the JavaScript thread the moment it is called, so a stop issued
    // after a `createServer` is known to have been issued after it. See `serverStopEpoch` in
    // `src/index.ts`.
    //
    // The cost is that [GattServerManager.stop] runs there too, and it takes `serverLifecycleLock` and
    // then makes binder calls — `setName`, `unregisterReceiver`, `close`. An adapter power cycle holding
    // that lock inside `openGattServer` will therefore block the JavaScript thread until the Bluetooth
    // process answers. Moving the teardown to the lifecycle looper would fix that and is not done here
    // because it would also stop `stopServer` from being ordered against anything, which is the property
    // the whole cancellation mechanism is built on.
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
