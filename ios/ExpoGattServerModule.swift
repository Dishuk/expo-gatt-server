import ExpoModulesCore
import CoreBluetooth

struct GattArgumentError: LocalizedError {
  let message: String
  var errorDescription: String? { message }
}

public class ExpoGattServerModule: Module {
  private var manager: GattServerManager?

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
      "onMtuChanged"
    )

    AsyncFunction("getMtu") { (deviceId: String, promise: Promise) in
      guard let mgr = self.manager else {
        promise.reject("ERR_NO_SERVER", "Server not created")
        return
      }
      // `connectedCentrals` is only touched on the main queue, which is also the queue the
      // peripheral manager dispatches its callbacks on.
      DispatchQueue.main.async {
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

    AsyncFunction("getBluetoothState") { (promise: Promise) in
      // `CBPeripheralManager.state` needs an instantiated manager, and instantiating one purely
      // to read state would trigger the Bluetooth permission prompt. Without a server, fall back
      // to the statically available authorization status.
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

    AsyncFunction("createServer") { (services: [[String: Any]], promise: Promise) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
        return
      }

      do {
        var initialValues: [CBUUID: Data] = [:]
        var cbServices: [CBMutableService] = []
        for serviceConfig in services {
          cbServices.append(try self.parseServiceConfig(serviceConfig, initialValues: &initialValues))
        }
        let delegations = try self.parseDelegations(services)
        self.manager?.stop()
        let mgr = GattServerManager()
        mgr.setDelegations(delegations)
        mgr.delegate = self
        mgr.onStateChange = { [weak self] state in
          self?.sendEvent("onBluetoothStateChanged", [
            "state": normalizedBluetoothState(state)
          ])
        }
        self.manager = mgr
        // Resolves only once CoreBluetooth is powered on and has acknowledged every service, so a
        // resolved promise means the server really is advertisable.
        mgr.open(services: cbServices, initialValues: initialValues) { error in
          if let error = error as? GattServerError {
            promise.reject(error.code, error.message)
          } else if let error = error {
            promise.reject("ERR_CREATE_SERVER", error.localizedDescription)
          } else {
            promise.resolve(nil)
          }
        }
      } catch {
        promise.reject("ERR_CREATE_SERVER", error.localizedDescription)
      }
    }

    AsyncFunction("startAdvertising") { (config: [String: Any], promise: Promise) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
        return
      }

      guard let mgr = self.manager else {
        promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.")
        return
      }

      let localName = config["localName"] as? String
      let serviceUuids: [CBUUID]?
      do {
        try self.rejectUnsupportedAdvertisingOptions(config)
        serviceUuids = try (config["serviceUuids"] as? [String])?
          .map { try self.parseUuid($0, field: "service") }
      } catch let error as GattServerError {
        promise.reject(error.code, error.message)
        return
      } catch {
        promise.reject("ERR_ADVERTISE", error.localizedDescription)
        return
      }
      // Already range-checked in JavaScript against the same bound Android enforces.
      let timeoutMs = (config["timeoutMs"] as? NSNumber)?.intValue ?? 0

      // Waits for a definitive powered-on state instead of sampling it. A synchronous read is
      // `.unknown` until peripheralManagerDidUpdateState fires, which used to reject perfectly
      // healthy calls made straight after createServer with "Bluetooth not ready".
      mgr.whenPoweredOn { readinessError in
        if let readinessError = readinessError as? GattServerError {
          promise.reject(readinessError.code, readinessError.message)
          return
        }
        if let readinessError = readinessError {
          promise.reject("ERR_BLUETOOTH", readinessError.localizedDescription)
          return
        }
        mgr.startAdvertising(
          localName: localName, serviceUuids: serviceUuids, timeoutMs: timeoutMs
        ) { error in
          if let error = error {
            promise.reject("ERR_ADVERTISE", error.localizedDescription)
          } else {
            promise.resolve(nil)
          }
        }
      }
    }

    Function("stopAdvertising") {
      self.manager?.stopAdvertising()
    }

    AsyncFunction("sendNotification") { (
      deviceId: String,
      serviceUuid: String,
      characteristicUuid: String,
      value: [Int],
      confirm: Bool,
      requireSubscription: Bool,
      promise: Promise
    ) in
      guard let mgr = self.manager else {
        promise.reject("ERR_NO_SERVER", "Server not created")
        return
      }
      let data: Data
      do {
        try self.validateUuid(serviceUuid, field: "service")
        try self.validateUuid(characteristicUuid, field: "characteristic")
        data = try self.parseBytes(value, field: "notification")
      } catch {
        promise.reject("ERR_NOTIFY", error.localizedDescription)
        return
      }
      // `confirm` and `requireSubscription` have no iOS counterpart: CoreBluetooth picks
      // notification or indication from the characteristic's declared properties, and it only ever
      // transmits to subscribed centrals, so an unsubscribed send cannot be forced through.
      DispatchQueue.main.async {
        do {
          // Resolves once CoreBluetooth has accepted the payload for transmission. A payload the
          // transmit queue could not take stays queued and resolves when it is resent, so a caller
          // that awaits it paces itself against the link instead of overrunning it.
          try mgr.sendNotification(
            deviceId: deviceId,
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: data
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

    AsyncFunction("sendResponse") { (
      deviceId: String,
      requestId: Int,
      status: Int,
      offset: Int,
      value: [Int],
      promise: Promise
    ) in
      guard let mgr = self.manager else {
        promise.reject("ERR_NO_SERVER", "Server not created")
        return
      }
      let data: Data
      do {
        data = try self.parseBytes(value, field: "response")
      } catch {
        promise.reject("ERR_RESPONSE", error.localizedDescription)
        return
      }
      DispatchQueue.main.async {
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

    Function("updateCharacteristicValue") { (
      serviceUuid: String,
      characteristicUuid: String,
      value: [Int]
    ) in
      try self.validateUuid(serviceUuid, field: "service")
      try self.validateUuid(characteristicUuid, field: "characteristic")
      let data = try self.parseBytes(value, field: "characteristic")
      self.manager?.updateCharacteristicValue(
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        value: data
      )
    }

    Function("stopServer") {
      self.manager?.stop()
      self.manager = nil
    }

    OnDestroy {
      self.manager?.stop()
      self.manager = nil
    }
  }

  /// `CBPeripheralManager.startAdvertising` silently ignores every key but the local name and
  /// service UUIDs. Only the options that change what a scanner *observes* are rejected here, since
  /// dropping those yields a peripheral that appears to advertise yet can never be found by a
  /// central filtering on them. `mode`, `txPowerLevel` and `includeTxPowerLevel` are merely radio
  /// hints, so they are warned about in JavaScript instead — rejecting them would force every
  /// cross-platform caller to branch on the platform just to tune Android's battery use.
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

  /// `CBUUID(string:)` raises an uncatchable Objective-C exception for anything other than a
  /// 16-bit (4 hex digits), 32-bit (8 hex digits) or hyphenated 128-bit (8-4-4-4-12) string,
  /// so every string has to be checked before it reaches CoreBluetooth.
  private func isValidUuid(_ string: String) -> Bool {
    func isHex(_ characters: Substring) -> Bool {
      !characters.isEmpty && characters.allSatisfy { $0.isASCII && $0.isHexDigit }
    }

    switch string.count {
    case 4, 8:
      return isHex(string[...])
    case 36:
      let groups = string.split(separator: "-", omittingEmptySubsequences: false)
      let expectedLengths = [8, 4, 4, 4, 12]
      guard groups.count == expectedLengths.count else { return false }
      return zip(groups, expectedLengths).allSatisfy { $0.count == $1 && isHex($0) }
    default:
      return false
    }
  }

  private func validateUuid(_ string: String, field: String) throws {
    guard isValidUuid(string) else {
      throw GattArgumentError(
        message: "Invalid \(field) UUID \"\(string)\". Expected 4 hex digits (16-bit), " +
          "8 hex digits (32-bit) or the hyphenated 8-4-4-4-12 form (128-bit)."
      )
    }
  }

  private func parseUuid(_ value: Any?, field: String) throws -> CBUUID {
    guard let string = value as? String else {
      throw GattArgumentError(message: "Missing or non-string \(field) UUID")
    }
    try validateUuid(string, field: field)
    return CBUUID(string: string)
  }

  /// Byte arrays arrive from JS as `[Int]`. Anything outside 0...255 would be silently
  /// corrupted by a clamping or truncating conversion, so reject it instead.
  private func parseBytes(_ value: [Int], field: String) throws -> Data {
    var bytes: [UInt8] = []
    bytes.reserveCapacity(value.count)
    for (index, element) in value.enumerated() {
      guard let byte = UInt8(exactly: element) else {
        throw GattArgumentError(
          message: "Invalid \(field) byte \(element) at index \(index). " +
            "Every element must be an integer between 0 and 255."
        )
      }
      bytes.append(byte)
    }
    return Data(bytes)
  }

  /// Collects the characteristics that opted out of the module's automatic responses. Absent or
  /// empty `delegate` configuration produces no entry, so the default stays fully automatic.
  private func parseDelegations(
    _ services: [[String: Any]]
  ) throws -> [CharacteristicAddress: CharacteristicDelegation] {
    var result: [CharacteristicAddress: CharacteristicDelegation] = [:]
    for serviceConfig in services {
      let serviceUuid = try parseUuid(serviceConfig["uuid"], field: "service")
      guard let charList = serviceConfig["characteristics"] as? [[String: Any]] else { continue }
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
    initialValues: inout [CBUUID: Data]
  ) throws -> CBMutableService {
    let uuid = try parseUuid(map["uuid"], field: "service")
    let service = CBMutableService(type: uuid, primary: true)

    var characteristics: [CBMutableCharacteristic] = []
    if let charList = map["characteristics"] as? [[String: Any]] {
      for charMap in charList {
        characteristics.append(
          try parseCharacteristicConfig(charMap, initialValues: &initialValues)
        )
      }
    }
    service.characteristics = characteristics
    return service
  }

  private func parseCharacteristicConfig(
    _ map: [String: Any],
    initialValues: inout [CBUUID: Data]
  ) throws -> CBMutableCharacteristic {
    let uuid = try parseUuid(map["uuid"], field: "characteristic")
    let properties = parseProperties(map["properties"] as? [String])
    let permissions = parsePermissions(map["permissions"] as? [String])

    // A CBMutableCharacteristic created with a non-nil value is forced read-only by
    // CoreBluetooth, and adding it with any other properties/permissions raises
    // "Characteristics with cached values must be read-only". Always publish the
    // characteristic with a dynamic (nil) value and serve the initial value from our own
    // cache instead, so that any configuration Android accepts also works here.
    if let bytes = map["value"] as? [Int], !bytes.isEmpty {
      initialValues[uuid] = try parseBytes(bytes, field: "characteristic")
    }

    return CBMutableCharacteristic(
      type: uuid,
      properties: properties,
      value: nil,
      permissions: permissions
    )
  }

  private func parseProperties(_ list: [String]?) -> CBCharacteristicProperties {
    var props: CBCharacteristicProperties = []
    list?.forEach { str in
      switch str {
      case "read": props.insert(.read)
      case "write": props.insert(.write)
      case "writeNoResponse": props.insert(.writeWithoutResponse)
      case "notify": props.insert(.notify)
      case "indicate": props.insert(.indicate)
      default: break
      }
    }
    return props
  }

  private func parsePermissions(_ list: [String]?) -> CBAttributePermissions {
    var perms: CBAttributePermissions = []
    list?.forEach { str in
      switch str {
      case "readable": perms.insert(.readable)
      case "writeable": perms.insert(.writeable)
      default: break
      }
    }
    return perms
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
