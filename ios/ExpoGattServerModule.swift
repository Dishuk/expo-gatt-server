import ExpoModulesCore
import CoreBluetooth

public class ExpoGattServerModule: Module {
  /// Read and written only on the main queue.
  private var manager: GattServerManager?

  /// Tracks `stopServer` calls to detect stops issued during `createServer` parsing.
  /// Guarded by lock: read from worker queue, written from JS thread.
  private let serverStopEpochLock = NSLock()
  private var serverStopEpochValue = 0

  private var serverStopEpoch: Int {
    serverStopEpochLock.lock()
    defer { serverStopEpochLock.unlock() }
    return serverStopEpochValue
  }

  /// Records stop on JS thread, so order matches application calls (not main-queue execution order).
  private func recordServerStop() {
    serverStopEpochLock.lock()
    defer { serverStopEpochLock.unlock() }
    serverStopEpochValue += 1
  }

  private func checkBluetoothAuthorization() -> String? {
    switch CBManager.authorization {
    case .denied:
      return "Bluetooth permission denied. Grant Bluetooth access in Settings."
    case .restricted:
      return "Bluetooth access restricted."
    default:
      return nil
    }
  }

  public func definition() -> ModuleDefinition {
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

    AsyncFunction("getMtu") { (deviceId: String, promise: Promise) in
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created")
          return
        }
        guard let mtu = mgr.mtu(for: deviceId) else {
          promise.reject(
            "ERR_DEVICE_DISCONNECTED",
            "Device \(deviceId) is not connected. iOS only knows a central once it has " +
              "subscribed, read or written, so wait for onDeviceConnected."
          )
          return
        }
        promise.resolve([
          "deviceId": deviceId,
          "mtu": mtu.mtu,
          "maxNotificationPayload": mtu.maxNotificationPayload
        ])
      }
    }

    AsyncFunction("getConnectedDevices") { (promise: Promise) in
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.resolve([])
          return
        }
        promise.resolve(mgr.connectedDeviceIds.map { ["deviceId": $0, "name": ""] })
      }
    }

    AsyncFunction("disconnectDevice") { (deviceId: String, promise: Promise) in
      let error = GattServerError.configurationUnsupported(
        option: "disconnectDevice",
        reason: "CBPeripheralManager declares no method that drops a connected central, and " +
          "CBCentralManager.cancelPeripheralConnection applies to a CBPeripheral in the central " +
          "role. A peripheral can stop advertising and unpublish its services, but neither is " +
          "documented as disconnecting anybody. Only the central can end the connection."
      )
      promise.reject(error.code, error.message)
    }

    AsyncFunction("isServerRunning") { (promise: Promise) in
      DispatchQueue.main.async { promise.resolve(self.manager?.isServerRunning ?? false) }
    }

    AsyncFunction("isAdvertising") { (promise: Promise) in
      DispatchQueue.main.async { promise.resolve(self.manager?.isAdvertising ?? false) }
    }

    AsyncFunction("getBluetoothState") { (promise: Promise) in
      DispatchQueue.main.async {
        // Instantiating CBPeripheralManager triggers permission prompt, so use CBManager.authorization if no manager exists.
        guard let mgr = self.manager else {
          switch CBManager.authorization {
          case .denied, .restricted:
            promise.resolve("unauthorized")
          default:
            promise.resolve("unknown")
          }
          return
        }
        promise.resolve(normalizedBluetoothState(mgr.bluetoothState))
      }
    }

    AsyncFunction("createServer") { (
      services: [[String: Any]],
      options: [String: Any],
      promise: Promise
    ) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
        return
      }

      // Read before any parsing, so a `stopServer` issued at any point from here on is seen below.
      let epoch = self.serverStopEpoch

      do {
        let requestTimeoutMs = try self.parseRequestTimeout(options["requestTimeoutMs"])
        var initialValues: [CharacteristicAddress: Data] = [:]
        var cbServices: [CBMutableService] = []
        // Validate unique service UUIDs: CoreBluetooth's addedServices key lookup + findCharacteristic first-match would leave one unreachable.
        var serviceUuids: Set<CBUUID> = []
        for serviceConfig in services {
          let service = try self.parseServiceConfig(serviceConfig, initialValues: &initialValues)
          guard serviceUuids.insert(service.uuid).inserted else {
            throw GattArgumentError(
              message: "Duplicate service UUID \(service.uuid.normalizedString). Give each service " +
                "its own UUID, or merge their characteristics into one service."
            )
          }
          cbServices.append(service)
        }
        let delegations = try self.parseDelegations(services)
        let parsedValues = initialValues
        DispatchQueue.main.async {
          // Stop was issued during parsing; skip publishing to avoid orphaned database.
          guard self.serverStopEpoch == epoch else {
            promise.reject("ERR_NO_SERVER", "Server was stopped before it finished opening")
            return
          }
          self.manager?.stop()
          let mgr = GattServerManager(requestTimeoutMs: requestTimeoutMs)
          mgr.setDelegations(delegations)
          mgr.delegate = self
          mgr.onStateChange = { [weak self] state in
            self?.sendEvent("onBluetoothStateChanged", [
              "state": normalizedBluetoothState(state)
            ])
          }
          self.manager = mgr
          // Resolves once CoreBluetooth is powered on and services are acknowledged.
          mgr.open(services: cbServices, initialValues: parsedValues) { error in
            if let error = error as? GattServerError {
              promise.reject(error.code, error.message)
            } else if let error = error {
              promise.reject("ERR_CREATE_SERVER", error.localizedDescription)
            } else {
              promise.resolve(nil)
            }
          }
        }
      } catch let error as GattServerError {
        // Preserve specific error codes (ERR_UNSUPPORTED, etc.); generic catch flattens to ERR_CREATE_SERVER.
        promise.reject(error.code, error.message)
      } catch {
        promise.reject("ERR_CREATE_SERVER", error.localizedDescription)
      }
    }

    AsyncFunction("startAdvertising") { (config: [String: Any], promise: Promise) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
        return
      }

      let localName = config["localName"] as? String
      let serviceUuids: [CBUUID]?
      do {
        try self.rejectUnsupportedAdvertisingOptions(config)
        // Parse serviceUuids with validation; as? [String] silently fails if any element is non-string.
        let serviceUuidStrings: [String]? = try parseTypedArray(
          config["serviceUuids"], field: "serviceUuids", elementDescription: "UUID strings"
        )
        serviceUuids = try serviceUuidStrings?.map { try parseUuid($0, field: "service") }
      } catch let error as GattServerError {
        promise.reject(error.code, error.message)
        return
      } catch {
        promise.reject("ERR_ADVERTISE", error.localizedDescription)
        return
      }
      let timeoutMs: Int
      do {
        timeoutMs = try parseTimeoutMs(
          config["timeoutMs"],
          field: "advertising timeout",
          bound: maxAdvertisingTimeoutMs,
          default: 0,
          boundDescription: "\(maxAdvertisingTimeoutMs) milliseconds, where 0 means no time limit"
        )
      } catch {
        promise.reject("ERR_ADVERTISE", error.localizedDescription)
        return
      }

      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.")
          return
        }
        // Manager waits for database publication; state is .unknown until peripheralManagerDidUpdateState + service acknowledgment.
        mgr.startAdvertising(
          localName: localName, serviceUuids: serviceUuids, timeoutMs: timeoutMs
        ) { error in
          if let error = error as? GattServerError {
            // Preserve specific codes; generic branch flattens to ERR_ADVERTISE.
            promise.reject(error.code, error.message)
          } else if let error = error {
            promise.reject("ERR_ADVERTISE", error.localizedDescription)
          } else {
            promise.resolve(nil)
          }
        }
      }
    }

    // Synchronous (matches Android void signature); teardown deferred to main queue to avoid JS thread deadlock.
    // Note: stopAdvertising can race with un-awaited startAdvertising; call order tracked in JavaScript.
    Function("stopAdvertising") {
      DispatchQueue.main.async { self.manager?.stopAdvertising() }
    }

    AsyncFunction("sendNotification") { (
      deviceId: String,
      serviceUuid: String,
      characteristicUuid: String,
      value: [Double],
      confirm: Bool,
      requireSubscription: Bool,
      promise: Promise
    ) in
      let data: Data
      do {
        try validateUuid(serviceUuid, field: "service")
        try validateUuid(characteristicUuid, field: "characteristic")
        data = try parseBytes(value, field: "notification")
      } catch {
        promise.reject("ERR_NOTIFY", error.localizedDescription)
        return
      }
      // CoreBluetooth only transmits to subscribed centrals; requireSubscription always true on iOS.
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created")
          return
        }
        do {
          try mgr.sendNotification(
            deviceId: deviceId,
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: data,
            confirm: confirm
          ) { error in
            if let error = error as? GattServerError {
              promise.reject(error.code, error.message)
            } else if let error = error {
              promise.reject("ERR_NOTIFY", error.localizedDescription)
            } else {
              promise.resolve(nil)
            }
          }
        } catch let error as GattServerError {
          promise.reject(error.code, error.message)
        } catch {
          promise.reject("ERR_NOTIFY", error.localizedDescription)
        }
      }
    }

    // Parameters are Double (not Int) because expo-modules-core's Int(double.rounded()) traps on NaN/infinity before code runs.
    AsyncFunction("sendResponse") { (
      deviceId: String,
      rawRequestId: Double,
      rawStatus: Double,
      rawOffset: Double,
      value: [Double],
      promise: Promise
    ) in
      let data: Data
      let requestId: Int
      let status: Int
      let offset: Int
      do {
        requestId = try parseIntArgument(
          rawRequestId, field: "response request id", min: 0, max: Int(Int32.max),
          explanation: "A request id is the whole number the matching request event carried."
        )
        status = try parseIntArgument(
          rawStatus, field: "response status", min: 0, max: 0xFF,
          explanation: "An ATT error code is a single byte."
        )
        offset = try parseIntArgument(
          rawOffset, field: "response offset", min: 0, max: 0xFFFF,
          explanation: "An ATT offset is an unsigned 16-bit value."
        )
        data = try parseBytes(value, field: "response")
      } catch {
        promise.reject("ERR_RESPONSE", error.localizedDescription)
        return
      }
      DispatchQueue.main.async {
        // REQUEST_NOT_FOUND not ERR_NO_SERVER: answering a request the module doesn't hold is a missing request. Android also uses this code.
        guard let mgr = self.manager else {
          promise.reject(
            "REQUEST_NOT_FOUND", "Request \(requestId) not found or already responded"
          )
          return
        }
        do {
          try mgr.sendResponse(
            deviceId: deviceId,
            requestId: requestId,
            status: status,
            offset: offset,
            value: data
          )
          promise.resolve(nil)
        } catch let error as GattServerError {
          promise.reject(error.code, error.message)
        } catch {
          promise.reject("ERR_RESPONSE", error.localizedDescription)
        }
      }
    }

    AsyncFunction("updateCharacteristicValue") { (
      serviceUuid: String,
      characteristicUuid: String,
      value: [Double],
      promise: Promise
    ) in
      let data: Data
      do {
        try validateUuid(serviceUuid, field: "service")
        try validateUuid(characteristicUuid, field: "characteristic")
        data = try parseBytes(value, field: "characteristic")
        // Same bound as configured values: spec bounds the attribute, not the update route.
        try assertAttributeValueLength(data, field: "characteristic")
      } catch {
        promise.reject("ERR_UPDATE_VALUE", error.localizedDescription)
        return
      }
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created")
          return
        }
        do {
          try mgr.updateCharacteristicValue(
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: data
          )
          promise.resolve(nil)
        } catch let error as GattServerError {
          promise.reject(error.code, error.message)
        } catch {
          promise.reject("ERR_UPDATE_VALUE", error.localizedDescription)
        }
      }
    }

    // Synchronous and deferred for same reasons as stopAdvertising.
    Function("stopServer") {
      // Record on JS thread to catch stops issued during createServer parsing on worker queue.
      self.recordServerStop()
      DispatchQueue.main.async {
        self.manager?.stop()
        self.manager = nil
      }
    }

    OnDestroy {
      // Block captures module strongly, so teardown always runs.
      self.recordServerStop()
      DispatchQueue.main.async {
        self.manager?.stop()
        self.manager = nil
      }
    }
  }

  /// CBPeripheralManager silently ignores most keys; only reject options that affect scanner visibility (manufacturerData, serviceData, connectable).
  private func rejectUnsupportedAdvertisingOptions(_ config: [String: Any]) throws {
    if let entries = config["manufacturerData"] as? [[String: Any]], !entries.isEmpty {
      throw GattServerError.advertisingOptionUnsupported(
        option: "manufacturerData",
        reason: "there is no peripheral-role key for Manufacturer Specific Data."
      )
    }
    if let entries = config["serviceData"] as? [[String: Any]], !entries.isEmpty {
      throw GattServerError.advertisingOptionUnsupported(
        option: "serviceData",
        reason: "there is no peripheral-role key for Service Data."
      )
    }
    if let connectable = config["connectable"] as? Bool, !connectable {
      throw GattServerError.advertisingOptionUnsupported(
        option: "connectable",
        reason: "CBPeripheralManager only ever advertises a connectable peripheral."
      )
    }
  }


  private func parseRequestTimeout(_ value: Any?) throws -> Int {
    try parseTimeoutMs(
      value,
      field: "request timeout",
      bound: attTransactionTimeoutMs - 1,
      default: defaultRequestTimeoutMs,
      boundDescription: "\(attTransactionTimeoutMs - 1) milliseconds — below the ATT transaction " +
        "timeout of \(attTransactionTimeoutMs) ms, past which the central has already given up — " +
        "where 0 disables the timeout"
    )
  }

  private func parseDelegations(
    _ services: [[String: Any]]
  ) throws -> [CharacteristicAddress: CharacteristicDelegation] {
    var result: [CharacteristicAddress: CharacteristicDelegation] = [:]
    for serviceConfig in services {
      let serviceUuid = try parseUuid(serviceConfig["uuid"], field: "service")
      let parsed: [[String: Any]]? = try parseTypedArray(
        serviceConfig["characteristics"],
        field: "characteristics",
        elementDescription: "characteristic objects"
      )
      guard let charList = parsed else { continue }
      for charMap in charList {
        guard let delegateMap = charMap["delegate"] as? [String: Any] else { continue }
        let delegation = CharacteristicDelegation(
          read: delegateMap["read"] as? Bool ?? false,
          write: delegateMap["write"] as? Bool ?? false
        )
        if delegation == CharacteristicDelegation.none { continue }
        let charUuid = try parseUuid(charMap["uuid"], field: "characteristic")
        result[CharacteristicAddress(service: serviceUuid, characteristic: charUuid)] = delegation
      }
    }
    return result
  }

  private func parseServiceConfig(
    _ map: [String: Any],
    initialValues: inout [CharacteristicAddress: Data]
  ) throws -> CBMutableService {
    let uuid = try parseUuid(map["uuid"], field: "service")
    let service = CBMutableService(type: uuid, primary: try parseIsPrimary(map["type"]))

    var characteristics: [CBMutableCharacteristic] = []
    var characteristicUuids: Set<CBUUID> = []
    let charList: [[String: Any]]? = try parseTypedArray(
      map["characteristics"], field: "characteristics", elementDescription: "characteristic objects"
    )
    if let charList = charList {
      for charMap in charList {
        let characteristic = try parseCharacteristicConfig(
          charMap, service: uuid, initialValues: &initialValues
        )
        guard characteristicUuids.insert(characteristic.uuid).inserted else {
          throw GattArgumentError(
            message: "Duplicate characteristic UUID \(characteristic.uuid.normalizedString) in " +
              "service \(uuid.normalizedString). The same characteristic UUID in a different " +
              "service is fine."
          )
        }
        characteristics.append(characteristic)
      }
    }
    service.characteristics = characteristics
    return service
  }

  private func parseIsPrimary(_ value: Any?) throws -> Bool {
    switch value as? String {
    case nil, "primary": return true
    case "secondary": return false
    default:
      throw GattArgumentError(
        message: "Invalid service type \"\(value ?? "nil")\". Expected \"primary\" or \"secondary\"."
      )
    }
  }

  private func parseCharacteristicConfig(
    _ map: [String: Any],
    service: CBUUID,
    initialValues: inout [CharacteristicAddress: Data]
  ) throws -> CBMutableCharacteristic {
    let uuid = try parseUuid(map["uuid"], field: "characteristic")
    let propertyNames: [String]? = try parseTypedArray(
      map["properties"], field: "properties", elementDescription: "property names"
    )
    let permissionNames: [String]? = try parseTypedArray(
      map["permissions"], field: "permissions", elementDescription: "permission names"
    )
    let properties = try parseProperties(propertyNames)
    let permissions = try parsePermissions(permissionNames)

    // CBMutableCharacteristic with non-nil value is forced read-only. Use nil value + cache to support full property/permission configs.
    if let bytes = try parseAttributeValue(map["value"], field: "characteristic") {
      initialValues[CharacteristicAddress(service: service, characteristic: uuid)] = bytes
    }

    let characteristic = CBMutableCharacteristic(
      type: uuid,
      properties: securedSubscription(properties, permissions),
      value: nil,
      permissions: permissions
    )

    let descriptorList: [[String: Any]]? = try parseTypedArray(
      map["descriptors"], field: "descriptors", elementDescription: "descriptor objects"
    )
    if let descriptorList = descriptorList, !descriptorList.isEmpty {
      // Setter raises uncatchable Objective-C exception on duplicate; validate before assignment via assertUniqueDescriptorUuids.
      let descriptors = try descriptorList.map { try parseDescriptorConfig($0) }
      try assertUniqueDescriptorUuids(descriptors.map(\.uuid), characteristic: uuid)
      characteristic.descriptors = descriptors
    }

    return characteristic
  }

  /// CBMutableDescriptor supports only Characteristic User Description (0x2901) and Presentation Format (0x2904); others rejected to avoid service publication failure.
  private func parseDescriptorConfig(_ map: [String: Any]) throws -> CBMutableDescriptor {
    let uuid = try parseUuid(map["uuid"], field: "descriptor")
    // An absent value publishes a zero-length descriptor, which is what Android's
    // `toByteArray(… ?: emptyList())` does.
    let bytes = try parseAttributeValue(map["value"], field: "descriptor") ?? Data()

    switch uuid {
    case CBUUID(string: CBUUIDCharacteristicUserDescriptionString):
      // User Description is NSString on iOS; value must be valid UTF-8.
      guard let text = String(data: bytes, encoding: .utf8) else {
        throw GattServerError.configurationUnsupported(
          option: "descriptor \(uuid.uuidString)",
          reason: "the Characteristic User Description value is an NSString on iOS, and these " +
            "bytes are not valid UTF-8."
        )
      }
      return CBMutableDescriptor(type: uuid, value: text)
    case CBUUID(string: CBUUIDCharacteristicFormatString):
      return CBMutableDescriptor(type: uuid, value: bytes)
    default:
      throw GattServerError.configurationUnsupported(
        option: "descriptor \(uuid.uuidString)",
        reason: "CBMutableDescriptor supports only the Characteristic User Description " +
          "(\(CBUUIDCharacteristicUserDescriptionString)) and Characteristic Presentation Format " +
          "(\(CBUUIDCharacteristicFormatString)) descriptors. Android publishes any descriptor, so " +
          "this one has to be declared for Android only."
      )
    }
  }


}

extension ExpoGattServerModule: GattServerManagerDelegate {
  func onDeviceConnected(deviceId: String, name: String?) {
    sendEvent("onDeviceConnected", [
      "deviceId": deviceId,
      "name": name ?? ""
    ])
  }

  func onDeviceDisconnected(deviceId: String) {
    sendEvent("onDeviceDisconnected", [
      "deviceId": deviceId
    ])
  }

  func onCharacteristicReadRequest(
    deviceId: String, requestId: Int, serviceUuid: String,
    characteristicUuid: String, offset: Int
  ) {
    sendEvent("onCharacteristicReadRequest", [
      "deviceId": deviceId,
      "requestId": requestId,
      "serviceUuid": serviceUuid,
      "characteristicUuid": characteristicUuid,
      "offset": offset
    ])
  }

  func onCharacteristicWriteRequest(
    deviceId: String, requestId: Int, serviceUuid: String,
    characteristicUuid: String, offset: Int, value: Data, responseNeeded: Bool
  ) {
    sendEvent("onCharacteristicWriteRequest", [
      "deviceId": deviceId,
      "requestId": requestId,
      "serviceUuid": serviceUuid,
      "characteristicUuid": characteristicUuid,
      "offset": offset,
      "value": Array(value).map { Int($0) },
      "responseNeeded": responseNeeded
    ])
  }

  func onNotificationSent(deviceId: String, characteristicUuid: String, status: Int) {
    sendEvent("onNotificationSent", [
      "deviceId": deviceId,
      "characteristicUuid": characteristicUuid,
      "status": status
    ])
  }

  func onMtuChanged(deviceId: String, mtu: DeviceMtu) {
    sendEvent("onMtuChanged", [
      "deviceId": deviceId,
      "mtu": mtu.mtu,
      "maxNotificationPayload": mtu.maxNotificationPayload
    ])
  }

  func onServerPublicationFailed(code: String, message: String) {
    sendEvent("onServerPublicationFailed", [
      "code": code,
      "message": message
    ])
  }

  func onCharacteristicSubscribed(
    deviceId: String, serviceUuid: String, characteristicUuid: String
  ) {
    sendEvent("onCharacteristicSubscribed", [
      "deviceId": deviceId,
      "serviceUuid": serviceUuid,
      "characteristicUuid": characteristicUuid
    ])
  }

  func onCharacteristicUnsubscribed(
    deviceId: String, serviceUuid: String, characteristicUuid: String
  ) {
    sendEvent("onCharacteristicUnsubscribed", [
      "deviceId": deviceId,
      "serviceUuid": serviceUuid,
      "characteristicUuid": characteristicUuid
    ])
  }
}
