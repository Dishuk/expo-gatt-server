import CoreBluetooth

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
  private var addedServices: [CBUUID: CBMutableService] = [:]
  private var subscribedCentrals: [String: [CBUUID: CBCentral]] = [:]
  private var characteristicValues: [CBUUID: Data] = [:]
  private var pendingRequests: [Int: CBATTRequest] = [:]
  private var requestCounter = 0

  func open(services: [CBMutableService]) {
    pendingServices = services
    peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
  }

  func startAdvertising(localName: String?, serviceUuids: [CBUUID]?) {
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
  }

  func sendNotification(
    deviceId: String, serviceUuid: String,
    characteristicUuid: String, value: Data
  ) -> Bool {
    let charUUID = CBUUID(string: characteristicUuid)

    guard let characteristic = findCharacteristic(
      serviceUuid: CBUUID(string: serviceUuid),
      characteristicUuid: charUUID
    ) else { return false }

    characteristicValues[charUUID] = value

    guard let centrals = subscribedCentrals[deviceId],
          let central = centrals[charUUID] else {
      // Not subscribed — update value only
      return true
    }

    return peripheralManager?.updateValue(
      value, for: characteristic, onSubscribedCentrals: [central]
    ) ?? false
  }

  func sendResponse(
    deviceId: String, requestId: Int, status: Int,
    offset: Int, value: Data
  ) {
    guard let request = pendingRequests.removeValue(forKey: requestId) else { return }

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

    // If we have a cached value, auto-respond
    if let value = characteristicValues[request.characteristic.uuid] {
      let offset = request.offset
      if offset < value.count {
        request.value = value.subdata(in: offset..<value.count)
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
    for request in requests {
      let reqId = nextRequestId()
      pendingRequests[reqId] = request

      let serviceUuid = request.characteristic.service?.uuid.uuidString ?? ""
      let value = request.value ?? Data()

      if let charUUID = addedServices.values
        .flatMap({ $0.characteristics ?? [] })
        .first(where: { $0.uuid == request.characteristic.uuid })?.uuid {
        characteristicValues[charUUID] = value
      }

      delegate?.onCharacteristicWriteRequest(
        deviceId: request.central.identifier.uuidString,
        requestId: reqId,
        serviceUuid: serviceUuid,
        characteristicUuid: request.characteristic.uuid.uuidString,
        offset: request.offset,
        value: value,
        responseNeeded: true
      )
    }

    // Auto-respond success for the first request (iOS requires exactly one response per didReceiveWrite)
    if let first = requests.first {
      peripheral.respond(to: first, withResult: .success)
    }
  }

  func peripheralManagerIsReadyToUpdateSubscribers(_ peripheral: CBPeripheralManager) {
    // Retry pending notifications when the transmit queue has space
    for (deviceId, subs) in subscribedCentrals {
      for (charUUID, central) in subs {
        guard let value = characteristicValues[charUUID],
              let service = addedServices.values.first(where: {
                $0.characteristics?.contains(where: { $0.uuid == charUUID }) ?? false
              }),
              let characteristic = service.characteristics?.first(where: {
                $0.uuid == charUUID
              }) as? CBMutableCharacteristic else { continue }

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
