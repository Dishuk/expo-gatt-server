import ExpoModulesCore
import CoreBluetooth

public class ExpoGattServerModule: Module {
  private var manager: GattServerManager?

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
      self.manager?.stop()
      let mgr = GattServerManager()
      mgr.delegate = self
      let cbServices = services.map { self.parseServiceConfig($0) }
      mgr.open(services: cbServices)
      self.manager = mgr
      promise.resolve(nil)
    }

    AsyncFunction("startAdvertising") { (config: [String: Any], promise: Promise) in
      guard let mgr = self.manager else {
        promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.")
        return
      }
      let localName = config["localName"] as? String
      let serviceUuids = (config["serviceUuids"] as? [String])?.map { CBUUID(string: $0) }
      mgr.startAdvertising(localName: localName, serviceUuids: serviceUuids)
      promise.resolve(nil)
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

  private func parseServiceConfig(_ map: [String: Any]) -> CBMutableService {
    let uuid = CBUUID(string: map["uuid"] as! String)
    let service = CBMutableService(type: uuid, primary: true)

    var characteristics: [CBMutableCharacteristic] = []
    if let charList = map["characteristics"] as? [[String: Any]] {
      for charMap in charList {
        characteristics.append(parseCharacteristicConfig(charMap))
      }
    }
    service.characteristics = characteristics
    return service
  }

  private func parseCharacteristicConfig(_ map: [String: Any]) -> CBMutableCharacteristic {
    let uuid = CBUUID(string: map["uuid"] as! String)
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
