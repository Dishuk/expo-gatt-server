import CoreBluetooth

/// Default ATT_MTU, in octets — Bluetooth Core Specification, Vol 3, Part G, Section 5.2.1.
let defaultAttMtu = 23

/// Octets an `ATT_HANDLE_VALUE_NTF` / `ATT_HANDLE_VALUE_IND` PDU spends before the value: a
/// one-octet Attribute Opcode plus a two-octet Attribute Handle (Core Specification, Vol 3, Part F,
/// Sections 3.4.7.1 and 3.4.7.2). The value it carries is therefore at most `ATT_MTU - 3` octets.
let attNotificationHeaderSize = 3

private let defaultAttMtuPayload = defaultAttMtu - attNotificationHeaderSize

/// Upper bound on notifications parked while the CoreBluetooth transmit queue is full. That queue
/// belongs to the peripheral manager rather than to any one central, so the bound is shared too.
/// Without it a producer that outruns the link would grow the queue forever; exceeding it fails the
/// call rather than dropping a payload silently.
private let maxQueuedNotifications = 64

/// The ATT transaction timeout. "A transaction not completed within 30 seconds shall time out. Such a
/// transaction shall be considered to have failed [...] No more Attribute Protocol requests,
/// commands, indications or notifications shall be sent to the target device on this ATT bearer" —
/// recovering then costs a whole new bearer (Core Specification, Vol 3, Part F, Section 3.3.3). A
/// module timeout at or above it could never answer before the peer gives up, so it is the exclusive
/// upper bound on `defaultRequestTimeoutMs` and on the configured value.
let attTransactionTimeoutMs = 30_000

/// How long a request delegated to JavaScript may go unanswered before the module answers it itself.
///
/// Chosen to sit well inside `attTransactionTimeoutMs` — the peer is left 20 s of margin, so it
/// receives a real ATT error response and its bearer stays usable, instead of the transaction failing
/// and taking every subsequent notification and indication with it. It is still long enough for a
/// handler doing genuine asynchronous work.
let defaultRequestTimeoutMs = 10_000

enum GattServerError: Error {
  case payloadExceedsMtu(maxPayload: Int, payloadSize: Int)
  case requestNotFound(requestId: Int)
  case requestDeviceMismatch(requestId: Int, owner: String, supplied: String)
  case responseOffsetAfterRequest(requestId: Int, requested: Int, supplied: Int)
  case bluetoothUnavailable(state: CBManagerState)
  case serviceRegistrationFailed(uuid: String, reason: String)
  case serverStopped
  case characteristicNotFound(service: String, characteristic: String)
  case notifyQueueFull(limit: Int)
  case deviceDisconnected(deviceId: String)
  case noSubscriber(deviceId: String, characteristic: String)
  case advertisingOptionUnsupported(option: String, reason: String)

  var code: String {
    switch self {
    case .payloadExceedsMtu: return "PAYLOAD_EXCEEDS_MTU"
    case .requestNotFound: return "REQUEST_NOT_FOUND"
    case .requestDeviceMismatch: return "REQUEST_DEVICE_MISMATCH"
    case .responseOffsetAfterRequest: return "ERR_RESPONSE_OFFSET"
    case .bluetoothUnavailable(let state):
      return state == .unauthorized ? "ERR_PERMISSION" : "ERR_BLUETOOTH"
    case .serviceRegistrationFailed: return "ERR_CREATE_SERVER"
    case .serverStopped: return "ERR_NO_SERVER"
    case .characteristicNotFound: return "ERR_CHARACTERISTIC_NOT_FOUND"
    case .notifyQueueFull: return "ERR_NOTIFY_QUEUE_FULL"
    case .deviceDisconnected: return "ERR_DEVICE_DISCONNECTED"
    case .noSubscriber: return "ERR_NO_SUBSCRIBER"
    case .advertisingOptionUnsupported: return "ERR_UNSUPPORTED"
    }
  }

  var message: String {
    switch self {
    case .payloadExceedsMtu(let maxPayload, let payloadSize):
      var message = "Payload size \(payloadSize) exceeds the \(maxPayload) bytes a single " +
        "notification or indication can carry on this link. Nothing was sent."
      if maxPayload <= defaultAttMtuPayload {
        message += " The link is still at the default ATT MTU of \(defaultAttMtu); a central that " +
          "negotiates a larger one is reported through onMtuChanged."
      }
      return message
    case .requestNotFound(let requestId):
      return "Request \(requestId) not found or already responded"
    case .requestDeviceMismatch(let requestId, let owner, let supplied):
      return "Request \(requestId) belongs to device \(owner), not \(supplied)"
    case .responseOffsetAfterRequest(let requestId, let requested, let supplied):
      return "Request \(requestId) asked for the attribute from offset \(requested), but the " +
        "response supplies it from offset \(supplied), which leaves the requested bytes missing. " +
        "Pass the value together with the offset it starts at — offset 0 with the whole value " +
        "always works."
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
    case .characteristicNotFound(let service, let characteristic):
      return "Characteristic \(characteristic) was not found in service \(service)"
    case .notifyQueueFull(let limit):
      return "\(limit) notifications are already waiting for the transmit queue to drain. " +
        "Wait for earlier sends to resolve before queueing more."
    case .deviceDisconnected(let deviceId):
      return "Device \(deviceId) disconnected"
    case .noSubscriber(let deviceId, let characteristic):
      // CoreBluetooth "ignores any centrals that haven't subscribed to the characteristic's
      // value", so there is nothing to override on iOS — the send genuinely cannot happen.
      return "Device \(deviceId) has not subscribed to characteristic \(characteristic). " +
        "Wait for onCharacteristicSubscribed. CoreBluetooth only transmits to subscribed " +
        "centrals, so this cannot be overridden on iOS."
    case .advertisingOptionUnsupported(let option, let reason):
      return "iOS cannot honour the advertising option \"\(option)\": \(reason) " +
        "CBPeripheralManager.startAdvertising supports only CBAdvertisementDataLocalNameKey and " +
        "CBAdvertisementDataServiceUUIDsKey."
    }
  }
}

/// The link budget for one central, expressed in the units the public API uses.
struct DeviceMtu {
  /// ATT_MTU in octets.
  let mtu: Int
  /// Octets that fit in one notification or indication: `ATT_MTU - 3`.
  let maxNotificationPayload: Int

  /// iOS only ever reports a payload length — `CBCentral.maximumUpdateValueLength` is "the maximum
  /// amount of data, in bytes, that can be received by the central in a single notification or
  /// indication" — so the ATT_MTU is reconstructed from it rather than read directly.
  init(maxNotificationPayload: Int) {
    self.maxNotificationPayload = maxNotificationPayload
    self.mtu = maxNotificationPayload + attNotificationHeaderSize
  }
}

/// Identifies a characteristic within the configured GATT database.
struct CharacteristicAddress: Hashable {
  let service: CBUUID
  let characteristic: CBUUID
}

/// Per-characteristic opt-in delegation of ATT request handling to JavaScript. Every flag defaults
/// to `false`, which keeps the module answering the request itself.
struct CharacteristicDelegation: Equatable {
  var read = false
  var write = false

  static let none = CharacteristicDelegation()
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
  func onMtuChanged(deviceId: String, mtu: DeviceMtu)
  func onCharacteristicSubscribed(deviceId: String, serviceUuid: String, characteristicUuid: String)
  func onCharacteristicUnsubscribed(deviceId: String, serviceUuid: String, characteristicUuid: String)
}

/// Maps a status supplied by JavaScript onto the ATT error code CoreBluetooth transmits.
///
/// An ATT error code is a single octet (Bluetooth Core Specification 5.4, Vol 3, Part F, Table
/// 3.4) and `CBATTError.Code` models 0x00 through 0x11, so those map straight across and match
/// what Android sends for the same call. The specification also defines 0x12, 0x13 and the
/// application (0x80–0x9F) and profile (0xE0–0xFF) ranges, but CoreBluetooth has no case for them
/// and `respond(to:withResult:)` only accepts a `CBATTError.Code`; anything unrepresentable is
/// reported as the generic "unlikely error" rather than being downgraded to success.
func attErrorCode(for status: Int) -> CBATTError.Code {
  switch status {
  case 0x00: return .success
  case 0x01: return .invalidHandle
  case 0x02: return .readNotPermitted
  case 0x03: return .writeNotPermitted
  case 0x04: return .invalidPdu
  case 0x05: return .insufficientAuthentication
  case 0x06: return .requestNotSupported
  case 0x07: return .invalidOffset
  case 0x08: return .insufficientAuthorization
  case 0x09: return .prepareQueueFull
  case 0x0A: return .attributeNotFound
  case 0x0B: return .attributeNotLong
  case 0x0C: return .insufficientEncryptionKeySize
  case 0x0D: return .invalidAttributeValueLength
  case 0x0E: return .unlikelyError
  case 0x0F: return .insufficientEncryption
  case 0x10: return .unsupportedGroupType
  case 0x11: return .insufficientResources
  default: return .unlikelyError
  }
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

  /// Milliseconds a delegated request may go unanswered; `0` disables the expiry entirely.
  private let requestTimeoutMs: Int

  init(requestTimeoutMs: Int = defaultRequestTimeoutMs) {
    self.requestTimeoutMs = requestTimeoutMs
    super.init()
  }

  /// Invoked on the main queue for every `peripheralManagerDidUpdateState` callback.
  var onStateChange: ((CBManagerState) -> Void)?

  private var peripheralManager: CBPeripheralManager?
  private var serviceConfiguration: [CBMutableService] = []
  private var servicesAwaitingRegistration: Set<CBUUID> = []
  private var openCompletion: ((Error?) -> Void)?
  private var readinessWaiters: [(Error?) -> Void] = []
  private var advertisingCompletion: ((Error?) -> Void)?
  /// Only ever touched on the main queue.
  private var advertisingTimeout: DispatchWorkItem?
  private var addedServices: [CBUUID: CBMutableService] = [:]

  /// Centrals the module believes are connected, keyed by `CBCentral.identifier`.
  ///
  /// `CBPeripheralManagerDelegate` declares no connection-level callback — the protocol is exactly
  /// `peripheralManagerDidUpdateState`, `willRestoreState`, `didStartAdvertising`, `didAddService`,
  /// `didSubscribeTo`, `didUnsubscribeFrom`, `didReceiveReadRequest`, `didReceiveWriteRequests`,
  /// `peripheralManagerIsReadyToUpdateSubscribers` and the three L2CAP methods — so membership is
  /// derived from the only signals CoreBluetooth does deliver: a subscribe, a read request or a
  /// write request. A central is therefore discovered on its first ATT activity rather than when
  /// the link is established.
  private var connectedCentrals: [String: CBCentral] = [:]

  /// Last `maximumUpdateValueLength` observed per central, used purely to detect a change.
  /// CoreBluetooth has no MTU-changed callback, so the only opportunity to notice one is when a
  /// central next produces activity.
  private var centralPayloadLengths: [String: Int] = [:]

  private var subscribedCentrals: [String: [CBUUID: CBCentral]] = [:]
  private var characteristicValues: [CBUUID: Data] = [:]
  private var pendingRequests: [Int: PendingRequest] = [:]
  private var requestCounter = 0
  private var delegations: [CharacteristicAddress: CharacteristicDelegation] = [:]
  // Fallback for a characteristic whose owning service cannot be identified. Only populated for
  // characteristic UUIDs that occur exactly once in the configuration, so a hit is unambiguous.
  private var delegationsByCharacteristic: [CBUUID: CharacteristicDelegation] = [:]

  /// Notifications CoreBluetooth could not accept yet, oldest first. Apple documents that when
  /// `updateValue(_:for:onSubscribedCentrals:)` returns `false` "because the underlying transmit
  /// queue is full", the manager calls `peripheralManagerIsReady(toUpdateSubscribers:)` "when more
  /// space in the transmit queue becomes available. After you receive this delegate method
  /// callback, you may resend the update" — so exactly these payloads, in this order, are what has
  /// to be resent.
  private var pendingNotifications: [QueuedNotification] = []

  private struct QueuedNotification {
    let deviceId: String
    let characteristicUuid: CBUUID
    let characteristic: CBMutableCharacteristic
    let central: CBCentral
    let value: Data
    let completion: (Error?) -> Void
  }

  private struct PendingRequest {
    let request: CBATTRequest
    /// Only a read response carries a value back to the central, so only a read request's `value`
    /// is overwritten when the response is sent. A write request's `value` is the written data and
    /// CoreBluetooth does not document overwriting it as supported.
    let isRead: Bool
    /// The armed expiry, kept so answering or discarding the request can cancel it.
    let timeout: DispatchWorkItem?
  }

  /// Records which characteristics hand their ATT requests to JavaScript. Call before `open`.
  func setDelegations(_ map: [CharacteristicAddress: CharacteristicDelegation]) {
    delegations = map
    var occurrences: [CBUUID: Int] = [:]
    for address in map.keys {
      occurrences[address.characteristic, default: 0] += 1
    }
    delegationsByCharacteristic = [:]
    for (address, delegation) in map where occurrences[address.characteristic] == 1 {
      delegationsByCharacteristic[address.characteristic] = delegation
    }
  }

  /// `CBATTRequest.characteristic.service` is a weak reference, so an unambiguous
  /// characteristic-UUID match is used rather than silently dropping the delegation.
  private func delegation(for characteristic: CBCharacteristic) -> CharacteristicDelegation {
    if let serviceUuid = characteristic.service?.uuid,
       let exact = delegations[
        CharacteristicAddress(service: serviceUuid, characteristic: characteristic.uuid)
       ] {
      return exact
    }
    return delegationsByCharacteristic[characteristic.uuid] ?? CharacteristicDelegation.none
  }

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

  /// `startAdvertising:` documents its complete set of supported keys as
  /// `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`, so those are the
  /// only two built here. Every other option is rejected before it reaches this point, except
  /// `timeoutMs`, which is emulated.
  func startAdvertising(
    localName: String?,
    serviceUuids: [CBUUID]?,
    timeoutMs: Int,
    completion: @escaping (Error?) -> Void
  ) {
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
    cancelAdvertisingTimeout()
    peripheralManager?.startAdvertising(advertisementData)
    scheduleAdvertisingTimeout(timeoutMs)
  }

  func stopAdvertising() {
    cancelAdvertisingTimeout()
    peripheralManager?.stopAdvertising()
    if let pending = advertisingCompletion {
      advertisingCompletion = nil
      pending(NSError(domain: "ExpoGattServer", code: 0, userInfo: [NSLocalizedDescriptionKey: "Advertising stopped"]))
    }
  }

  /// Emulates `AdvertiseSettings.setTimeout`, which CoreBluetooth has no equivalent for. Android
  /// simply stops advertising at the limit, with no error and no callback, so that is what this
  /// reproduces.
  private func scheduleAdvertisingTimeout(_ timeoutMs: Int) {
    guard timeoutMs > 0 else { return }
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      // Cleared first, so `stopAdvertising` does not try to cancel the item running it.
      self.advertisingTimeout = nil
      self.stopAdvertising()
    }
    advertisingTimeout = work
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(timeoutMs), execute: work)
  }

  private func cancelAdvertisingTimeout() {
    advertisingTimeout?.cancel()
    advertisingTimeout = nil
  }

  /// Sends one notification and reports the outcome through `completion` — with `nil` once
  /// CoreBluetooth has accepted the payload for transmission, or with the failure that stopped it.
  /// Throws only for problems detectable before the payload joins the queue.
  ///
  /// A payload the transmit queue cannot take is retained and resent, in order, when the manager
  /// reports it is ready again. Nothing else is resent: an unrelated central never receives an
  /// unsolicited update because another central's send was throttled.
  ///
  /// A central that has not subscribed is reported as `ERR_NO_SUBSCRIBER` rather than treated as a
  /// successful send. There is no override on iOS: `updateValue(_:for:onSubscribedCentrals:)`
  /// "ignores any centrals that haven't subscribed to the characteristic's value".
  func sendNotification(
    deviceId: String, serviceUuid: String,
    characteristicUuid: String, value: Data,
    completion: @escaping (Error?) -> Void
  ) throws {
    let charUUID = CBUUID(string: characteristicUuid)

    guard let characteristic = findCharacteristic(
      serviceUuid: CBUUID(string: serviceUuid),
      characteristicUuid: charUUID
    ) else {
      throw GattServerError.characteristicNotFound(
        service: serviceUuid, characteristic: characteristicUuid
      )
    }

    // The mirrored value is updated either way, so a read still serves the latest value even when
    // no one is listening for it.
    characteristicValues[charUUID] = value

    // Connection and subscription are reported separately now, so an unknown central is told it is
    // not connected rather than that it has not subscribed — the same distinction Android draws.
    guard connectedCentrals[deviceId] != nil else {
      throw GattServerError.deviceDisconnected(deviceId: deviceId)
    }

    guard let centrals = subscribedCentrals[deviceId],
          let central = centrals[charUUID] else {
      throw GattServerError.noSubscriber(
        deviceId: deviceId, characteristic: characteristicUuid
      )
    }

    // Checked before the payload goes anywhere near CoreBluetooth. `updateValue` documents that a
    // value exceeding `maximumUpdateValueLength` "will be truncated to fit", and a notification has
    // no continuation mechanism — unlike a read, which the central can finish with a Read Blob
    // request — so transmitting it would silently lose the tail.
    let maxPayload = central.maximumUpdateValueLength
    guard value.count <= maxPayload else {
      throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count)
    }

    let entry = QueuedNotification(
      deviceId: deviceId,
      characteristicUuid: charUUID,
      characteristic: characteristic,
      central: central,
      value: value,
      completion: completion
    )

    // Anything already waiting has to go out first, or a later payload would overtake an earlier
    // one on the same characteristic.
    guard pendingNotifications.isEmpty else {
      guard pendingNotifications.count < maxQueuedNotifications else {
        throw GattServerError.notifyQueueFull(limit: maxQueuedNotifications)
      }
      pendingNotifications.append(entry)
      return
    }

    if !deliver(entry) {
      pendingNotifications.append(entry)
    }
  }

  /// Hands one queued notification to CoreBluetooth. Returns `false` only when the transmit queue
  /// is full and the entry must stay queued until the manager reports it is ready.
  private func deliver(_ entry: QueuedNotification) -> Bool {
    guard let peripheral = peripheralManager else {
      entry.completion(GattServerError.serverStopped)
      return true
    }
    // Re-checked here as well as at enqueue time: the link budget can shrink while an entry waits
    // for the transmit queue, and the payload must never reach CoreBluetooth if it cannot be
    // carried intact.
    let maxPayload = entry.central.maximumUpdateValueLength
    guard entry.value.count <= maxPayload else {
      entry.completion(GattServerError.payloadExceedsMtu(
        maxPayload: maxPayload, payloadSize: entry.value.count
      ))
      return true
    }
    guard peripheral.updateValue(
      entry.value, for: entry.characteristic, onSubscribedCentrals: [entry.central]
    ) else {
      return false
    }
    delegate?.onNotificationSent(
      deviceId: entry.deviceId,
      characteristicUuid: entry.characteristicUuid.uuidString,
      status: 0
    )
    entry.completion(nil)
    return true
  }

  /// Fails and drops every queued notification matching `predicate`.
  private func failPendingNotifications(
    _ error: GattServerError, where predicate: (QueuedNotification) -> Bool = { _ in true }
  ) {
    let abandoned = pendingNotifications.filter(predicate)
    guard !abandoned.isEmpty else { return }
    pendingNotifications.removeAll(where: predicate)
    for entry in abandoned {
      entry.completion(error)
    }
  }

  /// Records a request handed to JavaScript and arms the expiry that answers it if JavaScript never
  /// does. Called before the event is emitted, so a listener that responds synchronously still finds
  /// the request.
  private func registerPendingRequest(_ requestId: Int, request: CBATTRequest, isRead: Bool) {
    var timeout: DispatchWorkItem?
    if requestTimeoutMs > 0 {
      let work = DispatchWorkItem { [weak self] in self?.expireRequest(requestId) }
      timeout = work
      DispatchQueue.main.asyncAfter(
        deadline: .now() + .milliseconds(requestTimeoutMs), execute: work
      )
    }
    pendingRequests[requestId] = PendingRequest(request: request, isRead: isRead, timeout: timeout)
  }

  /// Answers a request JavaScript left unanswered, so the central's transaction completes with an
  /// error rather than stalling until its own ATT transaction timeout drops the connection.
  ///
  /// "Unlikely Error" is the closest the specification offers: the request was valid and the server
  /// simply failed to produce a response, which none of the more specific codes describes.
  private func expireRequest(_ requestId: Int) {
    guard let pending = discardPendingRequest(requestId) else { return }
    peripheralManager?.respond(to: pending.request, withResult: .unlikelyError)
  }

  /// Forgets one pending request, cancelling the expiry it armed.
  @discardableResult
  private func discardPendingRequest(_ requestId: Int) -> PendingRequest? {
    guard let pending = pendingRequests.removeValue(forKey: requestId) else { return nil }
    pending.timeout?.cancel()
    return pending
  }

  /// Forgets matching pending requests, cancelling the expiry each one armed.
  private func discardPendingRequests(where predicate: (PendingRequest) -> Bool) {
    for (requestId, pending) in pendingRequests where predicate(pending) {
      pending.timeout?.cancel()
      pendingRequests.removeValue(forKey: requestId)
    }
  }

  /// Answers a pending read or write request.
  ///
  /// `offset` states where `value` begins within the attribute, and the response is rebased onto the
  /// offset the request actually asked for — so passing offset 0 with the whole value answers a Read
  /// Blob continuation correctly, and passing the request's own offset with a pre-sliced value works
  /// too. This is the same contract Android honours.
  func sendResponse(
    deviceId: String, requestId: Int, status: Int,
    offset: Int, value: Data
  ) throws {
    // Everything that could reject the call is checked before the pending entry is consumed, so a
    // failed attempt leaves the request answerable instead of stranding the central until its ATT
    // transaction times out. This is the ordering Android already used.
    guard let pending = pendingRequests[requestId] else {
      throw GattServerError.requestNotFound(requestId: requestId)
    }
    let request = pending.request

    let owner = request.central.identifier.uuidString
    guard owner == deviceId else {
      throw GattServerError.requestDeviceMismatch(
        requestId: requestId, owner: owner, supplied: deviceId
      )
    }

    let payload = try responsePayload(
      for: pending, requestId: requestId, offset: offset, value: value
    )
    discardPendingRequest(requestId)

    let result = attErrorCode(for: status)
    if pending.isRead {
      request.value = payload
    }
    // A read response is deliberately not size-checked. An `ATT_READ_RSP` carries at most
    // `ATT_MTU - 1` octets and the central continues a longer value with `ATT_READ_BLOB_REQ`, which
    // arrives as another read request bearing an offset — so answering with more than fits is normal
    // ATT, not a failure. Apple's own guidance is to assign the whole remainder from the request's
    // offset and let the central "retrieve the entire value", and this module's automatic read path
    // does exactly that, so rejecting it here would only have penalised delegated reads for
    // behaving identically.
    peripheralManager?.respond(to: request, withResult: result)
  }

  /// Rebases a supplied response value onto the offset the request asked for.
  ///
  /// `CBATTRequest.offset` is "the zero-based index of the first byte for the read or write" and is
  /// read-only, and `respond(to:withResult:)` takes no offset — CoreBluetooth derives it from the
  /// request — so honouring the caller's `offset` means aligning the value to it here.
  private func responsePayload(
    for pending: PendingRequest, requestId: Int, offset: Int, value: Data
  ) throws -> Data {
    // A Write Response carries no value, so there is nothing to rebase.
    guard pending.isRead else { return value }

    let requested = pending.request.offset
    guard offset <= requested else {
      throw GattServerError.responseOffsetAfterRequest(
        requestId: requestId, requested: requested, supplied: offset
      )
    }
    let skip = requested - offset
    guard skip > 0 else { return value }
    // The caller supplied nothing at or beyond the requested offset, which is the specification's
    // signal that the attribute ends there.
    guard skip < value.count else { return Data() }
    return value.subdata(in: skip..<value.count)
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
    failPendingNotifications(.serverStopped)
    for (_, service) in addedServices {
      peripheralManager?.remove(service)
    }
    serviceConfiguration.removeAll()
    servicesAwaitingRegistration.removeAll()
    addedServices.removeAll()
    connectedCentrals.removeAll()
    centralPayloadLengths.removeAll()
    subscribedCentrals.removeAll()
    characteristicValues.removeAll()
    discardPendingRequests { _ in true }
    delegations.removeAll()
    delegationsByCharacteristic.removeAll()
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

  /// `CBCharacteristic.service` is a weak reference that a torn-down database may already have
  /// cleared, so the owning service is looked up in the published database instead.
  private func serviceUuid(containing characteristicUuid: CBUUID) -> String {
    addedServices.values.first {
      $0.characteristics?.contains { $0.uuid == characteristicUuid } ?? false
    }?.uuid.uuidString ?? ""
  }

  private func nextRequestId() -> Int {
    requestCounter += 1
    return requestCounter
  }

  /// Records ATT activity from `central` and reports a first sighting as a connection.
  ///
  /// The stored reference is always refreshed: CoreBluetooth may hand out a distinct `CBCentral`
  /// instance per callback, and `maximumUpdateValueLength` is read from whichever one is current.
  @discardableResult
  private func noteActivity(from central: CBCentral) -> String {
    let deviceId = central.identifier.uuidString
    let isFirstSighting = connectedCentrals[deviceId] == nil
    connectedCentrals[deviceId] = central
    if isFirstSighting {
      // CoreBluetooth exposes no name for a central — only `CBPeripheral` has one — so there is
      // nothing truthful to report here.
      delegate?.onDeviceConnected(deviceId: deviceId, name: nil)
    }

    let payloadLength = central.maximumUpdateValueLength
    if centralPayloadLengths.updateValue(payloadLength, forKey: deviceId) != payloadLength {
      delegate?.onMtuChanged(
        deviceId: deviceId, mtu: DeviceMtu(maxNotificationPayload: payloadLength)
      )
    }
    return deviceId
  }

  /// The current link budget for `deviceId`, or `nil` when no such central is known.
  ///
  /// Read live from the retained `CBCentral` rather than from the change-detection cache, so the
  /// answer is whatever CoreBluetooth reports right now.
  func mtu(for deviceId: String) -> DeviceMtu? {
    guard let central = connectedCentrals[deviceId] else { return nil }
    return DeviceMtu(maxNotificationPayload: central.maximumUpdateValueLength)
  }

  /// Drops every trace of `deviceId` and reports the disconnection exactly once. A device that was
  /// never seen, or that has already been reported, produces nothing.
  private func markDisconnected(_ deviceId: String, reason: GattServerError) {
    guard connectedCentrals.removeValue(forKey: deviceId) != nil else { return }
    centralPayloadLengths.removeValue(forKey: deviceId)
    subscribedCentrals.removeValue(forKey: deviceId)
    discardPendingRequests { $0.request.central.identifier.uuidString == deviceId }
    failPendingNotifications(reason) { $0.deviceId == deviceId }
    delegate?.onDeviceDisconnected(deviceId: deviceId)
  }
}

extension GattServerManager: CBPeripheralManagerDelegate {
  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    onStateChange?(peripheral.state)

    switch peripheral.state {
    case .poweredOn:
      // Apple documents that "the powered off state clears the local database; in this case you
      // must explicitly re-add all services". `serviceConfiguration` is retained for exactly this
      // reason, so every transition to powered on re-publishes it — the first one included.
      publishConfiguredServices(on: peripheral)
      flushReadinessWaiters(nil)
    case .unknown, .resetting:
      // Transient — a further state update is coming, so neither fail nor publish yet.
      break
    default:
      // Any state below powered on drops the published database and disconnects every central,
      // so discard the mirrored state rather than letting it go stale.
      let error = GattServerError.bluetoothUnavailable(state: peripheral.state)
      servicesAwaitingRegistration.removeAll()

      // Every subscription dies with the database, so report each one as ended before the service
      // lookup it needs is discarded.
      let ended = subscribedCentrals.map { ($0.key, Array($0.value.keys)) }
      for (deviceId, characteristicUuids) in ended {
        for characteristicUuid in characteristicUuids {
          delegate?.onCharacteristicUnsubscribed(
            deviceId: deviceId,
            serviceUuid: serviceUuid(containing: characteristicUuid),
            characteristicUuid: characteristicUuid.uuidString
          )
        }
      }
      addedServices.removeAll()

      // Apple documents that a state below powered on means "any connected centrals have been
      // disconnected", so this is the one moment iOS can report a disconnection for a central that
      // never subscribed to anything.
      let disconnected = Array(connectedCentrals.keys)
      connectedCentrals.removeAll()
      centralPayloadLengths.removeAll()
      subscribedCentrals.removeAll()
      discardPendingRequests { _ in true }
      failPendingNotifications(.bluetoothUnavailable(state: peripheral.state))
      for deviceId in disconnected {
        delegate?.onDeviceDisconnected(deviceId: deviceId)
      }

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
    // Subscribing is one of the activity signals a connection is derived from, but it no longer
    // doubles as the connection event: a central that subscribes to three characteristics is one
    // connection, and `noteActivity` reports it once.
    let deviceId = noteActivity(from: central)
    var subs = subscribedCentrals[deviceId] ?? [:]
    subs[characteristic.uuid] = central
    subscribedCentrals[deviceId] = subs
    delegate?.onCharacteristicSubscribed(
      deviceId: deviceId,
      serviceUuid: characteristic.service?.uuid.uuidString ?? "",
      characteristicUuid: characteristic.uuid.uuidString
    )
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didUnsubscribeFrom characteristic: CBCharacteristic
  ) {
    let deviceId = central.identifier.uuidString
    subscribedCentrals[deviceId]?.removeValue(forKey: characteristic.uuid)
    delegate?.onCharacteristicUnsubscribed(
      deviceId: deviceId,
      serviceUuid: characteristic.service?.uuid.uuidString ?? "",
      characteristicUuid: characteristic.uuid.uuidString
    )
    // Nothing will ever accept these now, so fail them instead of leaking the queue.
    failPendingNotifications(.deviceDisconnected(deviceId: deviceId)) {
      $0.deviceId == deviceId && $0.characteristicUuid == characteristic.uuid
    }
    // CoreBluetooth delivers this same callback whether the central deliberately cleared its
    // Client Characteristic Configuration or simply went away, and offers nothing to tell the two
    // apart, so losing the last subscription is the only disconnect signal available for a
    // subscribed central. A central that unsubscribes but stays connected is therefore reported as
    // disconnected; a later read or write re-discovers it and reports a fresh connection.
    if subscribedCentrals[deviceId]?.isEmpty == true {
      markDisconnected(deviceId, reason: .deviceDisconnected(deviceId: deviceId))
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveRead request: CBATTRequest
  ) {
    noteActivity(from: request.central)

    // An opted-in characteristic always reaches JS. A configured initial value is served from this
    // cache rather than the CBMutableCharacteristic initialiser, so without the opt-in a
    // characteristic declared with `value` would never produce a single read event.
    if !delegation(for: request.characteristic).read,
       let value = characteristicValues[request.characteristic.uuid] {
      let offset = request.offset

      // An offset past the end of the value is answered with the error the specification requires —
      // 0x07 "Invalid Offset", `CBATTError.invalidOffset` (Core Specification, Vol 3, Part F,
      // Section 3.4.1.1) — rather than handed to a listener the characteristic never opted in to,
      // which left the central waiting for its ATT transaction to time out. This is also exactly
      // what Apple's own peripheral-role guidance prescribes. An offset equal to the length is
      // in range and answered with an empty value.
      if offset > value.count {
        peripheral.respond(to: request, withResult: .invalidOffset)
      } else {
        request.value = offset < value.count ? value.subdata(in: offset..<value.count) : Data()
        peripheral.respond(to: request, withResult: .success)
      }
      return
    }

    let reqId = nextRequestId()
    registerPendingRequest(reqId, request: request, isRead: true)

    delegate?.onCharacteristicReadRequest(
      deviceId: request.central.identifier.uuidString,
      requestId: reqId,
      serviceUuid: request.characteristic.service?.uuid.uuidString ?? "",
      characteristicUuid: request.characteristic.uuid.uuidString,
      offset: request.offset
    )
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveWrite requests: [CBATTRequest]
  ) {
    guard let first = requests.first else { return }

    for request in requests {
      noteActivity(from: request.central)
    }

    // Apple documents that `respond(to:withResult:)` must be called exactly once per callback,
    // passing the first request of the array, and that the batch is all-or-nothing: "if you can't
    // fulfill an individual request, you shouldn't fulfill any of them". So a delegated batch is
    // registered as a single pending request backed by `first`, every event it produces carries
    // that one id, and the first `sendResponse` for it answers the whole batch.
    let batchId = nextRequestId()
    let delegated = requests.contains { delegation(for: $0.characteristic).write }

    if delegated {
      registerPendingRequest(batchId, request: first, isRead: false)
    } else {
      peripheral.respond(to: first, withResult: .success)
    }

    for request in requests {
      let serviceUuid = request.characteristic.service?.uuid.uuidString ?? ""
      let value = request.value ?? Data()

      // A delegated batch may still be rejected, so the mirrored value is left untouched and the
      // listener commits it with `updateCharacteristicValue` once it has accepted the write.
      if !delegated, let charUUID = addedServices.values
        .flatMap({ $0.characteristics ?? [] })
        .first(where: { $0.uuid == request.characteristic.uuid })?.uuid {
        characteristicValues[charUUID] = value
      }

      delegate?.onCharacteristicWriteRequest(
        deviceId: request.central.identifier.uuidString,
        requestId: batchId,
        serviceUuid: serviceUuid,
        characteristicUuid: request.characteristic.uuid.uuidString,
        offset: request.offset,
        value: value,
        responseNeeded: delegated
      )
    }
  }

  /// Resends only the payloads that were actually refused, oldest first, and stops as soon as the
  /// transmit queue fills again so the rest keep their place in line.
  func peripheralManagerIsReady(_ peripheral: CBPeripheralManager) {
    while let next = pendingNotifications.first {
      guard deliver(next) else { return }
      pendingNotifications.removeFirst()
    }
  }
}
