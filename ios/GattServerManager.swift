import CoreBluetooth

private let defaultAttMtuPayload = 20 // ATT_MTU 23 - 3 header bytes
private let mtuSmallData = "MTU_SMALL".data(using: .utf8)!

enum GattServerError: Error {
  case mtuSmall(maxPayload: Int, payloadSize: Int)
  case payloadExceedsMtu(maxPayload: Int, payloadSize: Int, responded: Bool)
  case requestNotFound(requestId: Int)

  var code: String {
    switch self {
    case .mtuSmall: return "MTU_SMALL"
    case .payloadExceedsMtu: return "PAYLOAD_EXCEEDS_MTU"
    case .requestNotFound: return "REQUEST_NOT_FOUND"
    }
  }

  var message: String {
    switch self {
    case .mtuSmall(let maxPayload, let payloadSize):
      return "Payload size \(payloadSize) exceeds default MTU payload capacity of \(maxPayload) bytes. Client has not negotiated a larger MTU. A fallback value was sent."
    case .payloadExceedsMtu(let maxPayload, let payloadSize, let responded):
      let suffix = responded ? " An error response was sent." : ""
      return "Payload size \(payloadSize) exceeds negotiated MTU payload capacity of \(maxPayload) bytes.\(suffix)"
    case .requestNotFound(let requestId):
      return "Request \(requestId) not found or already responded"
    }
  }
}

protocol GattServerManagerDelegate: AnyObject {
  func onDeviceConnected(deviceId: String, name: String?)
  func onDeviceDisconnected(deviceId: String)
  func onCharacteristicReadRequest(
    deviceId: String, requestId: Int, serviceUuid: String,
    characteristicUuid: String, offset: Int
  )
  func onCharacteristicWriteRequest(
    deviceId: String, requestId: Int, serviceUuid: String,
    characteristicUuid: String, offset: Int, value: Data, responseNeeded: Bool
  )
  func onNotificationSent(deviceId: String, characteristicUuid: String, status: Int)
}

class GattServerManager: NSObject {
  weak var delegate: GattServerManagerDelegate?

  private var peripheralManager: CBPeripheralManager?
  private var pendingServices: [CBMutableService] = []
  private var advertisingCompletion: ((Error?) -> Void)?
  private var addedServices: [CBUUID: CBMutableService] = [:]
  private var subscribedCentrals: [String: [CBUUID: CBCentral]] = [:]
  private var characteristicValues: [CBUUID: Data] = [:]
  private var pendingRequests: [Int: CBATTRequest] = [:]
  private var requestCounter = 0

  func open(services: [CBMutableService]) {
    pendingServices = services
    peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
  }

  var bluetoothState: CBManagerState {
    peripheralManager?.state ?? .unknown
  }

  func startAdvertising(localName: String?, serviceUuids: [CBUUID]?, completion: @escaping (Error?) -> Void) {
    if let pending = advertisingCompletion {
      advertisingCompletion = nil
      pending(NSError(domain: "ExpoGattServer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Advertising restarted"]))
    }
    advertisingCompletion = completion
    var advertisementData: [String: Any] = [:]
    if let name = localName {
      advertisementData[CBAdvertisementDataLocalNameKey] = name
    }
    if let uuids = serviceUuids, !uuids.isEmpty {
      advertisementData[CBAdvertisementDataServiceUUIDsKey] = uuids
    }
    peripheralManager?.startAdvertising(advertisementData)
  }

  func stopAdvertising() {
    peripheralManager?.stopAdvertising()
    if let pending = advertisingCompletion {
      advertisingCompletion = nil
      pending(NSError(domain: "ExpoGattServer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Advertising stopped"]))
    }
  }

  func sendNotification(
    deviceId: String, serviceUuid: String,
    characteristicUuid: String, value: Data
  ) throws -> Bool {
    let charUUID = CBUUID(string: characteristicUuid)

    guard let characteristic = findCharacteristic(
      serviceUuid: CBUUID(string: serviceUuid),
      characteristicUuid: charUUID
    ) else { return false }

    guard let centrals = subscribedCentrals[deviceId],
          let central = centrals[charUUID] else {
      characteristicValues[charUUID] = value
      return true
    }

    let maxPayload = central.maximumUpdateValueLength
    if value.count > maxPayload {
      if maxPayload <= defaultAttMtuPayload {
        characteristicValues[charUUID] = mtuSmallData
        peripheralManager?.updateValue(
          mtuSmallData, for: characteristic, onSubscribedCentrals: [central]
        )
        throw GattServerError.mtuSmall(maxPayload: maxPayload, payloadSize: value.count)
      } else {
        throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count, responded: false)
      }
    }

    characteristicValues[charUUID] = value

    return peripheralManager?.updateValue(
      value, for: characteristic, onSubscribedCentrals: [central]
    ) ?? false
  }

  func sendResponse(
    deviceId: String, requestId: Int, status: Int,
    offset: Int, value: Data
  ) throws {
    guard let request = pendingRequests.removeValue(forKey: requestId) else {
      throw GattServerError.requestNotFound(requestId: requestId)
    }

    let maxPayload = request.central.maximumUpdateValueLength
    if value.count > maxPayload {
      if maxPayload <= defaultAttMtuPayload {
        request.value = mtuSmallData
        peripheralManager?.respond(to: request, withResult: .success)
        throw GattServerError.mtuSmall(maxPayload: maxPayload, payloadSize: value.count)
      } else {
        peripheralManager?.respond(to: request, withResult: .invalidAttributeValueLength)
        throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count, responded: true)
      }
    }

    let result: CBATTError.Code = status == 0 ? .success : .requestNotSupported
    request.value = value
    peripheralManager?.respond(to: request, withResult: result)
  }

  func updateCharacteristicValue(
    serviceUuid: String, characteristicUuid: String, value: Data
  ) {
    let charUUID = CBUUID(string: characteristicUuid)
    characteristicValues[charUUID] = value
  }

  func stop() {
    stopAdvertising()
    for (_, service) in addedServices {
      peripheralManager?.remove(service)
    }
    addedServices.removeAll()
    subscribedCentrals.removeAll()
    characteristicValues.removeAll()
    pendingRequests.removeAll()
    peripheralManager = nil
  }

  private func findCharacteristic(
    serviceUuid: CBUUID, characteristicUuid: CBUUID
  ) -> CBMutableCharacteristic? {
    guard let service = addedServices[serviceUuid] else { return nil }
    return service.characteristics?.first {
      $0.uuid == characteristicUuid
    } as? CBMutableCharacteristic
  }

  private func nextRequestId() -> Int {
    requestCounter += 1
    return requestCounter
  }
}

extension GattServerManager: CBPeripheralManagerDelegate {
  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    guard peripheral.state == .poweredOn else { return }
    for service in pendingServices {
      peripheral.add(service)
    }
    pendingServices.removeAll()
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didStartAdvertising error: Error?) {
    advertisingCompletion?(error)
    advertisingCompletion = nil
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didAdd service: CBService, error: Error?
  ) {
    if error == nil {
      addedServices[service.uuid] = service as? CBMutableService
        ?? CBMutableService(type: service.uuid, primary: service.isPrimary)
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didSubscribeTo characteristic: CBCharacteristic
  ) {
    let deviceId = central.identifier.uuidString
    var subs = subscribedCentrals[deviceId] ?? [:]
    subs[characteristic.uuid] = central
    subscribedCentrals[deviceId] = subs
    delegate?.onDeviceConnected(deviceId: deviceId, name: nil)
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didUnsubscribeFrom characteristic: CBCharacteristic
  ) {
    let deviceId = central.identifier.uuidString
    subscribedCentrals[deviceId]?.removeValue(forKey: characteristic.uuid)
    if subscribedCentrals[deviceId]?.isEmpty == true {
      subscribedCentrals.removeValue(forKey: deviceId)
      pendingRequests = pendingRequests.filter { $0.value.central.identifier.uuidString != deviceId }
      delegate?.onDeviceDisconnected(deviceId: deviceId)
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveRead request: CBATTRequest
  ) {
    let reqId = nextRequestId()
    pendingRequests[reqId] = request

    let serviceUuid = request.characteristic.service?.uuid.uuidString ?? ""

    if let value = characteristicValues[request.characteristic.uuid] {
      let offset = request.offset
      if offset <= value.count {
        request.value = offset < value.count ? value.subdata(in: offset..<value.count) : Data()
        peripheral.respond(to: request, withResult: .success)
        pendingRequests.removeValue(forKey: reqId)
        return
      }
    }

    delegate?.onCharacteristicReadRequest(
      deviceId: request.central.identifier.uuidString,
      requestId: reqId,
      serviceUuid: serviceUuid,
      characteristicUuid: request.characteristic.uuid.uuidString,
      offset: request.offset
    )
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveWrite requests: [CBATTRequest]
  ) {
    // iOS requires exactly one response per didReceiveWrite — auto-respond before notifying JS
    if let first = requests.first {
      peripheral.respond(to: first, withResult: .success)
    }

    for request in requests {
      let serviceUuid = request.characteristic.service?.uuid.uuidString ?? ""
      let value = request.value ?? Data()

      if let charUUID = addedServices.values
        .flatMap({ $0.characteristics ?? [] })
        .first(where: { $0.uuid == request.characteristic.uuid })?.uuid {
        characteristicValues[charUUID] = value
      }

      delegate?.onCharacteristicWriteRequest(
        deviceId: request.central.identifier.uuidString,
        requestId: 0,
        serviceUuid: serviceUuid,
        characteristicUuid: request.characteristic.uuid.uuidString,
        offset: request.offset,
        value: value,
        responseNeeded: false
      )
    }
  }

  func peripheralManagerIsReady(_ peripheral: CBPeripheralManager) {
    for (deviceId, subs) in subscribedCentrals {
      for (charUUID, central) in subs {
        guard let value = characteristicValues[charUUID],
              let service = addedServices.values.first(where: {
                $0.characteristics?.contains(where: { $0.uuid == charUUID }) ?? false
              }),
              let characteristic = service.characteristics?.first(where: {
                $0.uuid == charUUID
              }) as? CBMutableCharacteristic else { continue }

        let maxPayload = central.maximumUpdateValueLength
        if value.count > maxPayload { continue }

        let sent = peripheral.updateValue(
          value, for: characteristic, onSubscribedCentrals: [central]
        )
        if sent {
          delegate?.onNotificationSent(
            deviceId: deviceId,
            characteristicUuid: charUUID.uuidString,
            status: 0
          )
        }
      }
    }
  }
}
