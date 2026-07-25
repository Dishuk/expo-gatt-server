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
      "onNotificationSent"
    )

    AsyncFunction("createServer") { (services: [[String: Any]], promise: Promise) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
        return
      }

      do {
        let cbServices = try services.map { try self.parseServiceConfig($0) }
        self.manager?.stop()
        let mgr = GattServerManager()
        mgr.delegate = self
        mgr.open(services: cbServices)
        self.manager = mgr
        promise.resolve(nil)
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

      switch mgr.bluetoothState {
      case .poweredOff:
        promise.reject("ERR_BLUETOOTH", "Bluetooth is turned off")
        return
      case .unauthorized:
        promise.reject("ERR_PERMISSION", "Bluetooth permission not granted")
        return
      case .unsupported:
        promise.reject("ERR_BLUETOOTH", "BLE not supported on this device")
        return
      case .poweredOn:
        break
      default:
        promise.reject("ERR_BLUETOOTH", "Bluetooth not ready")
        return
      }

      let localName = config["localName"] as? String
      let serviceUuids: [CBUUID]?
      do {
        serviceUuids = try (config["serviceUuids"] as? [String])?
          .map { try self.parseUuid($0, field: "service") }
      } catch {
        promise.reject("ERR_ADVERTISE", error.localizedDescription)
        return
      }
      mgr.startAdvertising(localName: localName, serviceUuids: serviceUuids) { error in
        if let error = error {
          promise.reject("ERR_ADVERTISE", error.localizedDescription)
        } else {
          promise.resolve(nil)
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
      promise: Promise
    ) in
      guard let mgr = self.manager else {
        promise.reject("ERR_NO_SERVER", "Server not created")
        return
      }
      do {
        try self.validateUuid(serviceUuid, field: "service")
        try self.validateUuid(characteristicUuid, field: "characteristic")
      } catch {
        promise.reject("ERR_NOTIFY", error.localizedDescription)
        return
      }
      let data = Data(value.map { UInt8(clamping: $0) })
      DispatchQueue.main.async {
        do {
          let success = try mgr.sendNotification(
            deviceId: deviceId,
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: data
          )
          if success {
            promise.resolve(nil)
          } else {
            promise.reject("ERR_NOTIFY", "Failed to send notification — transmit queue full, will retry on peripheralManagerIsReady")
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
      let data = Data(value.map { UInt8(clamping: $0) })
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
      let data = Data(value.map { UInt8(clamping: $0) })
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

  private func parseServiceConfig(_ map: [String: Any]) throws -> CBMutableService {
    let uuid = try parseUuid(map["uuid"], field: "service")
    let service = CBMutableService(type: uuid, primary: true)

    var characteristics: [CBMutableCharacteristic] = []
    if let charList = map["characteristics"] as? [[String: Any]] {
      for charMap in charList {
        characteristics.append(try parseCharacteristicConfig(charMap))
      }
    }
    service.characteristics = characteristics
    return service
  }

  private func parseCharacteristicConfig(_ map: [String: Any]) throws -> CBMutableCharacteristic {
    let uuid = try parseUuid(map["uuid"], field: "characteristic")
    let properties = parseProperties(map["properties"] as? [String])
    let permissions = parsePermissions(map["permissions"] as? [String])
    let initialValue: Data? = (map["value"] as? [Int])?.isEmpty == false
      ? Data((map["value"] as! [Int]).map { UInt8(clamping: $0) })
      : nil

    // For notify/indicate characteristics, value must be nil to allow dynamic updates
    let value: Data? = properties.contains(.notify) || properties.contains(.indicate)
      ? nil
      : initialValue

    return CBMutableCharacteristic(
      type: uuid,
      properties: properties,
      value: value,
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
}
