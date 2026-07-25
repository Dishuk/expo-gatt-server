import CoreBluetooth

private let defaultAttMtuPayload = 20 // ATT_MTU 23 - 3 header bytes

enum GattServerError: Error {
  case mtuSmall(maxPayload: Int, payloadSize: Int)
  case payloadExceedsMtu(maxPayload: Int, payloadSize: Int, responded: Bool)
  case requestNotFound(requestId: Int)
  case bluetoothUnavailable(state: CBManagerState)
  case serviceRegistrationFailed(uuid: String, reason: String)
  case serverStopped

  var code: String {
    switch self {
    case .mtuSmall: return "MTU_SMALL"
    case .payloadExceedsMtu: return "PAYLOAD_EXCEEDS_MTU"
    case .requestNotFound: return "REQUEST_NOT_FOUND"
    case .bluetoothUnavailable(let state):
      return state == .unauthorized ? "ERR_PERMISSION" : "ERR_BLUETOOTH"
    case .serviceRegistrationFailed: return "ERR_CREATE_SERVER"
    case .serverStopped: return "ERR_NO_SERVER"
    }
  }

  var message: String {
    switch self {
    case .mtuSmall(let maxPayload, let payloadSize):
      return "Payload size \(payloadSize) exceeds default MTU payload capacity of \(maxPayload) bytes. Client has not negotiated a larger MTU."
    case .payloadExceedsMtu(let maxPayload, let payloadSize, let responded):
      let suffix = responded ? " An error response was sent." : ""
      return "Payload size \(payloadSize) exceeds negotiated MTU payload capacity of \(maxPayload) bytes.\(suffix)"
    case .requestNotFound(let requestId):
      return "Request \(requestId) not found or already responded"
    case .bluetoothUnavailable(let state):
      switch state {
      case .poweredOff: return "Bluetooth is turned off"
      case .unauthorized: return "Bluetooth permission not granted"
      case .unsupported: return "BLE not supported on this device"
      default: return "Bluetooth not ready"
      }
    case .serviceRegistrationFailed(let uuid, let reason):
      return "Failed to publish service \(uuid): \(reason)"
    case .serverStopped:
      return "Server was stopped before it finished opening"
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

/// Maps `CBManagerState` onto the platform-neutral state union shared with Android.
func normalizedBluetoothState(_ state: CBManagerState) -> String {
  switch state {
  case .poweredOn: return "poweredOn"
  case .poweredOff: return "poweredOff"
  case .resetting: return "resetting"
  case .unsupported: return "unsupported"
  case .unauthorized: return "unauthorized"
  default: return "unknown"
  }
}

class GattServerManager: NSObject {
  weak var delegate: GattServerManagerDelegate?

  /// Invoked on the main queue for every `peripheralManagerDidUpdateState` callback.
  var onStateChange: ((CBManagerState) -> Void)?

  private var peripheralManager: CBPeripheralManager?
  private var serviceConfiguration: [CBMutableService] = []
  private var servicesAwaitingRegistration: Set<CBUUID> = []
  private var openCompletion: ((Error?) -> Void)?
  private var readinessWaiters: [(Error?) -> Void] = []
  private var advertisingCompletion: ((Error?) -> Void)?
  private var addedServices: [CBUUID: CBMutableService] = [:]
  private var subscribedCentrals: [String: [CBUUID: CBCentral]] = [:]
  private var characteristicValues: [CBUUID: Data] = [:]
  private var pendingRequests: [Int: CBATTRequest] = [:]
  private var requestCounter = 0

  /// Opens the peripheral manager and publishes `services`. `completion` runs exactly once on the
  /// main queue — with `nil` only after every service is confirmed published, or with an error if
  /// publishing fails or Bluetooth is unavailable.
  ///
  /// `CBPeripheralManager.state` is `.unknown` until `peripheralManagerDidUpdateState` fires, and
  /// services can only be added while powered on, so the completion is necessarily deferred.
  func open(
    services: [CBMutableService],
    initialValues: [CBUUID: Data] = [:],
    completion: @escaping (Error?) -> Void
  ) {
    serviceConfiguration = services
    characteristicValues = initialValues
    openCompletion = completion
    peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
  }

  var bluetoothState: CBManagerState {
    peripheralManager?.state ?? .unknown
  }

  /// Invokes `completion` once Bluetooth is known to be powered on, rather than sampling
  /// `state` synchronously — a synchronous read right after `open` still returns `.unknown`,
  /// because the state only becomes meaningful when `peripheralManagerDidUpdateState` fires.
  /// Transient states (`.unknown`, `.resetting`) park the caller until the next definitive update;
  /// terminal states fail it immediately.
  func whenPoweredOn(_ completion: @escaping (Error?) -> Void) {
    guard let peripheral = peripheralManager else {
      completion(GattServerError.serverStopped)
      return
    }
    switch peripheral.state {
    case .poweredOn:
      completion(nil)
    case .unknown, .resetting:
      readinessWaiters.append(completion)
    default:
      completion(GattServerError.bluetoothUnavailable(state: peripheral.state))
    }
  }

  private func completeOpen(_ error: Error?) {
    guard let completion = openCompletion else { return }
    openCompletion = nil
    completion(error)
  }

  private func flushReadinessWaiters(_ error: Error?) {
    let waiters = readinessWaiters
    readinessWaiters.removeAll()
    for waiter in waiters {
      waiter(error)
    }
  }

  /// Publishes every configured service and completes the pending open once CoreBluetooth has
  /// acknowledged all of them via `peripheralManager(_:didAdd:error:)`.
  private func publishConfiguredServices(on peripheral: CBPeripheralManager) {
    servicesAwaitingRegistration = Set(serviceConfiguration.map { $0.uuid })
    guard !servicesAwaitingRegistration.isEmpty else {
      completeOpen(nil)
      return
    }
    for service in serviceConfiguration {
      peripheral.add(service)
    }
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

    characteristicValues[charUUID] = value

    let sent = peripheralManager?.updateValue(
      value, for: characteristic, onSubscribedCentrals: [central]
    ) ?? false

    let maxPayload = central.maximumUpdateValueLength
    if value.count > maxPayload {
      if maxPayload <= defaultAttMtuPayload {
        throw GattServerError.mtuSmall(maxPayload: maxPayload, payloadSize: value.count)
      } else {
        throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count, responded: false)
      }
    }

    return sent
  }

  func sendResponse(
    deviceId: String, requestId: Int, status: Int,
    offset: Int, value: Data
  ) throws {
    guard let request = pendingRequests.removeValue(forKey: requestId) else {
      throw GattServerError.requestNotFound(requestId: requestId)
    }

    let result: CBATTError.Code = status == 0 ? .success : .requestNotSupported
    request.value = value
    peripheralManager?.respond(to: request, withResult: result)

    let maxPayload = request.central.maximumUpdateValueLength
    if value.count > maxPayload {
      if maxPayload <= defaultAttMtuPayload {
        throw GattServerError.mtuSmall(maxPayload: maxPayload, payloadSize: value.count)
      } else {
        throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count, responded: true)
      }
    }
  }

  func updateCharacteristicValue(
    serviceUuid: String, characteristicUuid: String, value: Data
  ) {
    let charUUID = CBUUID(string: characteristicUuid)
    characteristicValues[charUUID] = value
  }

  func stop() {
    stopAdvertising()
    completeOpen(GattServerError.serverStopped)
    flushReadinessWaiters(GattServerError.serverStopped)
    for (_, service) in addedServices {
      peripheralManager?.remove(service)
    }
    serviceConfiguration.removeAll()
    servicesAwaitingRegistration.removeAll()
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
    onStateChange?(peripheral.state)

    switch peripheral.state {
    case .poweredOn:
      publishConfiguredServices(on: peripheral)
      flushReadinessWaiters(nil)
    case .unknown, .resetting:
      // Transient — a further state update is coming, so neither fail nor publish yet.
      break
    default:
      let error = GattServerError.bluetoothUnavailable(state: peripheral.state)
      servicesAwaitingRegistration.removeAll()
      completeOpen(error)
      flushReadinessWaiters(error)
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didStartAdvertising error: Error?) {
    advertisingCompletion?(error)
    advertisingCompletion = nil
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didAdd service: CBService, error: Error?
  ) {
    servicesAwaitingRegistration.remove(service.uuid)

    if let error = error {
      servicesAwaitingRegistration.removeAll()
      completeOpen(GattServerError.serviceRegistrationFailed(
        uuid: service.uuid.uuidString,
        reason: error.localizedDescription
      ))
      return
    }

    addedServices[service.uuid] = service as? CBMutableService
      ?? CBMutableService(type: service.uuid, primary: service.isPrimary)

    if servicesAwaitingRegistration.isEmpty {
      completeOpen(nil)
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
