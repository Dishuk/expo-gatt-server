import CoreBluetooth

/// Default ATT_MTU, in octets — Core Spec Vol 3, Part G, §5.2.1.
let defaultAttMtu = 23

/// Octets an `ATT_HANDLE_VALUE_NTF` / `ATT_HANDLE_VALUE_IND` PDU spends before the value: a one-octet
/// Attribute Opcode plus a two-octet Attribute Handle (Core Spec Vol 3, Part F, §§3.4.7.1–3.4.7.2).
let attNotificationHeaderSize = 3

private let defaultAttMtuPayload = defaultAttMtu - attNotificationHeaderSize

/// Upper bound on notifications parked while the CoreBluetooth transmit queue is full. That queue
/// belongs to the peripheral manager rather than to any one central, so the bound is shared too.
/// Without it a producer that outruns the link would grow the queue forever.
private let maxQueuedNotifications = 64

/// The ATT transaction timeout. A transaction not completed within 30 s fails, and no further request,
/// command, indication or notification may then be sent on that ATT bearer — recovering costs a whole
/// new bearer (Core Spec Vol 3, Part F, §3.3.3). A module timeout at or above it could never answer
/// before the peer gives up, so it is the exclusive upper bound on the request timeout.
let attTransactionTimeoutMs = 30_000

/// How long a request delegated to JavaScript may go unanswered before the module answers it itself.
/// Sits well inside `attTransactionTimeoutMs`, leaving the peer 20 s of margin so it receives a real
/// ATT error response and its bearer stays usable, while still allowing a handler to do genuine
/// asynchronous work.
let defaultRequestTimeoutMs = 10_000

enum GattServerError: Error {
  case payloadExceedsMtu(maxPayload: Int, payloadSize: Int)
  case requestNotFound(requestId: Int)
  case requestDeviceMismatch(requestId: Int, owner: String, supplied: String)
  case responseOffsetAfterRequest(requestId: Int, requested: Int, supplied: Int)
  case bluetoothUnavailable(state: CBManagerState)
  case serviceRegistrationFailed(uuid: String, reason: String)
  case serverStopped
  case databaseNotPublished
  case characteristicNotFound(service: String, characteristic: String)
  case notifyQueueFull(limit: Int)
  case deviceDisconnected(deviceId: String)
  case noSubscriber(deviceId: String, characteristic: String)
  case confirmUnsupported(characteristic: String, confirm: Bool)
  case advertisingOptionUnsupported(option: String, reason: String)
  case configurationUnsupported(option: String, reason: String)

  var code: String {
    switch self {
    case .payloadExceedsMtu: return "PAYLOAD_EXCEEDS_MTU"
    case .requestNotFound: return "REQUEST_NOT_FOUND"
    case .requestDeviceMismatch: return "REQUEST_DEVICE_MISMATCH"
    case .responseOffsetAfterRequest: return "ERR_RESPONSE_OFFSET"
    case .bluetoothUnavailable(let state):
      return state == .unauthorized ? "ERR_PERMISSION" : "ERR_BLUETOOTH"
    case .serviceRegistrationFailed: return "ERR_CREATE_SERVER"
    case .serverStopped, .databaseNotPublished: return "ERR_NO_SERVER"
    case .characteristicNotFound: return "ERR_CHARACTERISTIC_NOT_FOUND"
    case .notifyQueueFull: return "ERR_NOTIFY_QUEUE_FULL"
    case .deviceDisconnected: return "ERR_DEVICE_DISCONNECTED"
    case .noSubscriber: return "ERR_NO_SUBSCRIBER"
    case .confirmUnsupported: return "ERR_CONFIRM_UNSUPPORTED"
    case .advertisingOptionUnsupported, .configurationUnsupported: return "ERR_UNSUPPORTED"
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
    case .databaseNotPublished:
      return "No GATT database is published, so there is nothing to advertise. Wait for createServer " +
        "to resolve; isServerRunning reports whether the database is still there, which a failed " +
        "registration or Bluetooth going down undoes."
    case .characteristicNotFound(let service, let characteristic):
      return "Characteristic \(characteristic) was not found in service \(service)"
    case .notifyQueueFull(let limit):
      return "\(limit) notifications are already waiting for the transmit queue to drain. " +
        "Wait for earlier sends to resolve before queueing more."
    case .deviceDisconnected(let deviceId):
      return "Device \(deviceId) disconnected"
    case .noSubscriber(let deviceId, let characteristic):
      return "Device \(deviceId) has not subscribed to characteristic \(characteristic). " +
        "Wait for onCharacteristicSubscribed. CoreBluetooth only transmits to subscribed " +
        "centrals, so this cannot be overridden on iOS."
    case .confirmUnsupported(let characteristic, let confirm):
      // `updateValue` has no confirm parameter and picks notification or indication from the declared
      // properties, so this check is all that stands between the caller's intent and silently getting
      // the other one.
      if confirm {
        return "Characteristic \(characteristic) does not declare the \"indicate\" property, so it " +
          "cannot send the acknowledged indication confirm: true asks for. Declare \"indicate\" on " +
          "the characteristic, or send a notification with confirm: false."
      }
      return "Characteristic \(characteristic) does not declare the \"notify\" property, so it " +
        "cannot send an unacknowledged notification. Declare \"notify\" on the characteristic, or " +
        "send an indication with confirm: true."
    case .advertisingOptionUnsupported(let option, let reason):
      return "iOS cannot honour the advertising option \"\(option)\": \(reason) " +
        "CBPeripheralManager.startAdvertising supports only CBAdvertisementDataLocalNameKey and " +
        "CBAdvertisementDataServiceUUIDsKey."
    case .configurationUnsupported(let option, let reason):
      return "iOS cannot honour \"\(option)\": \(reason)"
    }
  }
}

/// The outcome an advertising call gets when something other than CoreBluetooth ends it. Deliberately
/// not a `GattServerError`, so it surfaces as `ERR_ADVERTISE`, which is what Android reports for the
/// same events.
private func advertisingError(_ message: String) -> NSError {
  NSError(domain: "ExpoGattServer", code: 0, userInfo: [NSLocalizedDescriptionKey: message])
}

/// The link budget for one central, expressed in the units the public API uses.
struct DeviceMtu {
  /// ATT_MTU in octets.
  let mtu: Int
  /// Octets that fit in one notification or indication: `ATT_MTU - 3`.
  let maxNotificationPayload: Int

  /// iOS only ever reports a payload length, `CBCentral.maximumUpdateValueLength`, so the ATT_MTU is
  /// reconstructed from it rather than read directly.
  init(maxNotificationPayload: Int) {
    self.maxNotificationPayload = maxNotificationPayload
    self.mtu = maxNotificationPayload + attNotificationHeaderSize
  }
}

struct CharacteristicAddress: Hashable {
  let service: CBUUID
  let characteristic: CBUUID
}

/// Every flag defaults to `false`, which keeps the module answering the request itself.
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
/// `CBATTError.Code` models 0x00 through 0x11 (Core Spec 5.4, Vol 3, Part F, Table 3.4), so those map
/// straight across and match what Android sends. The specification also defines 0x12, 0x13 and the
/// application and profile ranges, but `respond(to:withResult:)` accepts only a `CBATTError.Code`, so
/// anything unrepresentable becomes the generic "unlikely error" rather than being downgraded to
/// success.
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

/// The Bluetooth Base UUID's trailing four groups — Core Spec Vol 3, Part B, §2.5.1.
private let bluetoothBaseUuidSuffix = "-0000-1000-8000-00805f9b34fb"

extension CBUUID {
  /// The lowercase 128-bit spelling, which is what `java.util.UUID.toString` produces on Android.
  ///
  /// `CBUUID.uuidString` is not that: it uppercases the 128-bit form and echoes a 16-bit or 32-bit
  /// UUID back in the short form it was constructed from, which made the same characteristic arrive in
  /// event payloads spelled differently on each platform. Normalising is repeated here as well as in
  /// JavaScript because these UUIDs come back out of CoreBluetooth rather than from the configuration.
  var normalizedString: String {
    let lower = uuidString.lowercased()
    guard lower.count < 36 else { return lower }
    return String(repeating: "0", count: 8 - lower.count) + lower + bluetoothBaseUuidSuffix
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

/// Every member of this class must be reached from the main queue, including `open` and `stop`.
///
/// `CBPeripheralManager` is created with `queue: .main`, so every delegate callback mutates this state
/// there, and Swift's `Dictionary` and `Array` tolerate no concurrent mutation at all. The module is
/// what enforces this: Expo runs `AsyncFunction` bodies on a shared background queue and `Function`
/// bodies on the JavaScript thread, so neither reaches the main queue by itself.
class GattServerManager: NSObject {
  weak var delegate: GattServerManagerDelegate?

  /// Milliseconds a delegated request may go unanswered; `0` disables the expiry entirely.
  private let requestTimeoutMs: Int

  init(requestTimeoutMs: Int = defaultRequestTimeoutMs) {
    self.requestTimeoutMs = requestTimeoutMs
    super.init()
  }

  /// Invoked on the main queue.
  var onStateChange: ((CBManagerState) -> Void)?

  private var peripheralManager: CBPeripheralManager?
  private var serviceConfiguration: [CBMutableService] = []
  private var servicesAwaitingRegistration: Set<CBUUID> = []
  private var openCompletion: ((Error?) -> Void)?
  private var readinessWaiters: [(Error?) -> Void] = []
  private var advertisingCompletion: ((Error?) -> Void)?
  private var advertisingTimeout: DispatchWorkItem?

  /// Bumped by every stop, so a start still waiting for the database can tell that the application asked
  /// for the opposite while it waited.
  private var advertisingGeneration = 0

  private var addedServices: [CBUUID: CBMutableService] = [:]

  /// How far the current round of `add(_:)` calls has got. Apple documents that "the powered off state
  /// clears the local database", so this drops back to `idle` on any state below powered on and only
  /// reaches `published` once the re-publish that follows powering on is acknowledged.
  private enum DatabasePublication {
    /// Nothing is published and a publication round is still expected — before the first state update,
    /// and after a transient state discarded the database.
    case idle
    case inProgress
    case published
    case failed
  }

  private var publication: DatabasePublication = .idle

  /// Whether every configured service is currently published.
  private var databasePublished: Bool { publication == .published }

  /// Whether the current round of `add(_:)` calls has already had one service rejected. The callbacks
  /// arrive in no guaranteed order, so without this a failure followed by a success would see nothing
  /// left awaiting registration and publish a database that is missing a service.
  private var registrationFailed = false

  /// Centrals the module believes are connected, keyed by `CBCentral.identifier`.
  ///
  /// `CBPeripheralManagerDelegate` declares no connection-level callback, so membership is derived
  /// from the only signals CoreBluetooth does deliver — a subscribe, a read request or a write
  /// request. A central is therefore discovered on its first ATT activity rather than when the link
  /// is established.
  private var connectedCentrals: [String: CBCentral] = [:]

  /// Last `maximumUpdateValueLength` observed per central, used purely to detect a change:
  /// CoreBluetooth has no MTU-changed callback, so the only opportunity to notice one is when a
  /// central next produces activity.
  private var centralPayloadLengths: [String: Int] = [:]

  private var subscribedCentrals: [String: [CharacteristicAddress: CBCentral]] = [:]
  // Keyed by service as well as characteristic, because GATT permits the same characteristic UUID in
  // two services: under a UUID-only key one instance's value answered reads of the other's, and a write
  // to either clobbered both.
  private var characteristicValues: [CharacteristicAddress: Data] = [:]
  private var pendingRequests: [Int: PendingRequest] = [:]
  private var requestCounter = 0
  private var delegations: [CharacteristicAddress: CharacteristicDelegation] = [:]

  /// Notifications CoreBluetooth could not accept yet, oldest first. When
  /// `updateValue(_:for:onSubscribedCentrals:)` returns `false` because the transmit queue is full,
  /// Apple documents that the update may be resent once `peripheralManagerIsReady` arrives — so
  /// exactly these payloads, in this order, are what has to go out then.
  private var pendingNotifications: [QueuedNotification] = []

  private struct QueuedNotification {
    let deviceId: String
    let address: CharacteristicAddress
    let characteristic: CBMutableCharacteristic
    let central: CBCentral
    let value: Data
    let completion: (Error?) -> Void
  }

  /// One value a partially delegated write batch withheld, kept with what the attribute held when the
  /// batch was assembled — the only thing a later commit can tell a stale value apart by.
  private struct DeferredWrite {
    let value: Data
    /// `nil` when the attribute had no value at all, which is distinct from an empty one.
    let baseline: Data?
  }

  private struct PendingRequest {
    let request: CBATTRequest
    /// Only a read response carries a value back to the central. A write request's `value` is the
    /// written data, and CoreBluetooth does not document overwriting it as supported.
    let isRead: Bool
    /// What a partially delegated write batch assembled for the characteristics that did *not* opt in,
    /// withheld until the batch is accepted. Apple documents the batch as an atomic unit — "if the
    /// execution of one of the requests would cause a failure [...] none of the requests should be
    /// executed" — so half of it must not be committed while JavaScript may still reject the rest.
    let deferredValues: [CharacteristicAddress: DeferredWrite]
    /// The armed expiry, kept so answering or discarding the request can cancel it.
    let timeout: DispatchWorkItem?
  }

  /// Records which characteristics hand their ATT requests to JavaScript. Call before `open`.
  func setDelegations(_ map: [CharacteristicAddress: CharacteristicDelegation]) {
    delegations = map
  }

  private func delegation(for address: CharacteristicAddress) -> CharacteristicDelegation {
    delegations[address] ?? CharacteristicDelegation.none
  }

  /// Opens the peripheral manager and publishes `services`. `completion` runs exactly once on the main
  /// queue — with `nil` only after every service is confirmed published.
  ///
  /// The completion is necessarily deferred: `CBPeripheralManager.state` is `.unknown` until
  /// `peripheralManagerDidUpdateState` fires, and services can only be added while powered on.
  func open(
    services: [CBMutableService],
    initialValues: [CharacteristicAddress: Data] = [:],
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

  /// Invokes `completion` once the configured services are published, rather than sampling `state`
  /// synchronously: a synchronous read right after `open` still returns `.unknown`, because the state
  /// only becomes meaningful when `peripheralManagerDidUpdateState` fires, and the publication that
  /// transition starts finishes in later main-queue turns again. Powering on is therefore not enough to
  /// release a parked caller — anything requiring a published database would find one half-built.
  ///
  /// A transient state or a publication still running parks the caller; a terminal state or a
  /// publication that failed fails it immediately.
  func whenDatabasePublished(_ completion: @escaping (Error?) -> Void) {
    guard let peripheral = peripheralManager else {
      completion(GattServerError.serverStopped)
      return
    }
    switch peripheral.state {
    case .poweredOn:
      switch publication {
      case .published:
        completion(nil)
      case .failed:
        completion(GattServerError.databaseNotPublished)
      case .idle, .inProgress:
        readinessWaiters.append(completion)
      }
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

  private func publishConfiguredServices(on peripheral: CBPeripheralManager) {
    publication = .inProgress
    registrationFailed = false
    servicesAwaitingRegistration = Set(serviceConfiguration.map { $0.uuid })
    guard !servicesAwaitingRegistration.isEmpty else {
      publication = .published
      completeOpen(nil)
      flushReadinessWaiters(nil)
      return
    }
    for service in serviceConfiguration {
      peripheral.add(service)
    }
  }

  /// Advertises once the database is published, holding the call until then rather than refusing it, and
  /// invokes `completion` exactly once.
  ///
  /// A stop that lands while the call is held rejects it with the error a stop already gives a start in
  /// flight, so one stop means one thing: proceeding instead would put the radio on the air after the
  /// application explicitly asked for the opposite.
  func startAdvertising(
    localName: String?,
    serviceUuids: [CBUUID]?,
    timeoutMs: Int,
    completion: @escaping (Error?) -> Void
  ) {
    let generation = advertisingGeneration
    whenDatabasePublished { [weak self] error in
      guard let self = self else {
        completion(GattServerError.serverStopped)
        return
      }
      if let error = error {
        completion(error)
        return
      }
      guard generation == self.advertisingGeneration else {
        completion(advertisingError("Advertising stopped"))
        return
      }
      self.beginAdvertising(
        localName: localName, serviceUuids: serviceUuids,
        timeoutMs: timeoutMs, completion: completion
      )
    }
  }

  /// `startAdvertising:` documents its complete set of supported keys as
  /// `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`, so those are the only
  /// two built here. Every other option is rejected before this point, except `timeoutMs`, emulated
  /// below.
  private func beginAdvertising(
    localName: String?,
    serviceUuids: [CBUUID]?,
    timeoutMs: Int,
    completion: @escaping (Error?) -> Void
  ) {
    // A half-built or empty database is still a database CoreBluetooth will let scanners find, and a
    // registration that failed leaves exactly that behind.
    guard databasePublished else {
      completion(GattServerError.databaseNotPublished)
      return
    }
    claimAdvertisingCompletion()?(advertisingError("Advertising restarted"))
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
    // Bumped before anything else, so a start released from `whenDatabasePublished` in the meantime
    // still sees this stop rather than reaching the radio behind it.
    advertisingGeneration += 1
    cancelAdvertisingTimeout()
    peripheralManager?.stopAdvertising()
    claimAdvertisingCompletion()?(advertisingError("Advertising stopped"))
  }

  /// Takes ownership of the pending advertising completion, so the `Promise` behind it is settled by
  /// exactly one of `didStartAdvertising`, a restart, a stop and Bluetooth going down — each of which can
  /// arrive for the same completion, and two of which would otherwise resolve and reject the same
  /// promise.
  private func claimAdvertisingCompletion() -> ((Error?) -> Void)? {
    defer { advertisingCompletion = nil }
    return advertisingCompletion
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
  /// CoreBluetooth has accepted the payload for transmission. Throws only for problems detectable
  /// before the payload joins the queue.
  ///
  /// A payload the transmit queue cannot take is retained and resent, in order, when the manager
  /// reports it is ready again. Nothing else is resent, so an unrelated central never receives an
  /// unsolicited update because another central's send was throttled.
  ///
  /// A central that has not subscribed is reported as `ERR_NO_SUBSCRIBER` rather than treated as a
  /// successful send, with no override: `updateValue(_:for:onSubscribedCentrals:)` "ignores any
  /// centrals that haven't subscribed to the characteristic's value".
  ///
  /// `confirm` is validated against the declared properties and then goes no further, because
  /// `updateValue` takes no such parameter and derives notification versus indication from those same
  /// properties. Validating is the only way the flag can mean anything here.
  ///
  /// The mirrored value is deliberately left alone: this pushes a value to subscribers, while a read is
  /// answered from whatever `updateCharacteristicValue` last stored. Mirroring here also used to give a
  /// characteristic configured without a `value` one, which silently stopped its reads reaching the
  /// listener.
  func sendNotification(
    deviceId: String, serviceUuid: String,
    characteristicUuid: String, value: Data, confirm: Bool,
    completion: @escaping (Error?) -> Void
  ) throws {
    // A database exists only while powered on — Apple documents that "the powered off state clears the
    // local database" — so without this the lookup below reports a perfectly valid address as missing
    // whenever Bluetooth is off. Android reports the same two situations under the same codes.
    guard let peripheral = peripheralManager else { throw GattServerError.serverStopped }
    guard peripheral.state == .poweredOn else {
      throw GattServerError.bluetoothUnavailable(state: peripheral.state)
    }

    let address = CharacteristicAddress(
      service: CBUUID(string: serviceUuid),
      characteristic: CBUUID(string: characteristicUuid)
    )

    guard let characteristic = findCharacteristic(
      serviceUuid: address.service,
      characteristicUuid: address.characteristic
    ) else {
      throw GattServerError.characteristicNotFound(
        service: serviceUuid, characteristic: characteristicUuid
      )
    }

    guard characteristic.properties.contains(confirm ? .indicate : .notify) else {
      throw GattServerError.confirmUnsupported(
        characteristic: characteristicUuid, confirm: confirm
      )
    }

    // Checked after the address and before the subscription, which is the order Android uses too, so a
    // call carrying more than one fault reports the same code on either platform.
    guard connectedCentrals[deviceId] != nil else {
      throw GattServerError.deviceDisconnected(deviceId: deviceId)
    }

    guard let centrals = subscribedCentrals[deviceId],
          let central = centrals[address] else {
      throw GattServerError.noSubscriber(
        deviceId: deviceId, characteristic: characteristicUuid
      )
    }

    // `updateValue` documents that a value exceeding `maximumUpdateValueLength` "will be truncated to
    // fit", and a notification has no continuation mechanism — unlike a read, which the central can
    // finish with a Read Blob request — so transmitting it would silently lose the tail.
    let maxPayload = central.maximumUpdateValueLength
    guard value.count <= maxPayload else {
      throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count)
    }

    let entry = QueuedNotification(
      deviceId: deviceId,
      address: address,
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

  /// Hands queued notifications to CoreBluetooth until one is refused, taking each off the queue before
  /// delivering it.
  ///
  /// Dequeuing first because [deliver] settles the entry's promise, and settling is JavaScript's cue to
  /// send the next value: an entry still sitting in the array while its own completion runs could be
  /// removed a second time, or have a different one removed in its place. A refusal puts it back at the
  /// head, since the order a characteristic's values go out in is the order they were queued.
  private func drainPendingNotifications() {
    while let next = pendingNotifications.first {
      pendingNotifications.removeFirst()
      guard deliver(next) else {
        pendingNotifications.insert(next, at: 0)
        return
      }
    }
  }

  /// Hands one queued notification to CoreBluetooth. Returns `false` only when the transmit queue
  /// is full and the entry must stay queued until the manager reports it is ready.
  private func deliver(_ entry: QueuedNotification) -> Bool {
    guard let peripheral = peripheralManager else {
      entry.completion(GattServerError.serverStopped)
      return true
    }
    // Re-checked as well as at enqueue time: the link budget can shrink while an entry waits for the
    // transmit queue, and the payload must never reach CoreBluetooth if it cannot be carried intact.
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
      characteristicUuid: entry.address.characteristic.normalizedString,
      status: 0
    )
    entry.completion(nil)
    return true
  }

  private func failPendingNotifications(
    _ error: GattServerError, where predicate: (QueuedNotification) -> Bool = { _ in true }
  ) {
    let abandoned = pendingNotifications.filter(predicate)
    guard !abandoned.isEmpty else { return }
    pendingNotifications.removeAll(where: predicate)
    for entry in abandoned {
      entry.completion(error)
    }
    // The refusal that owes us a `peripheralManagerIsReady` may have belonged to an entry just abandoned,
    // and CoreBluetooth owes nothing on behalf of a central that has gone. Whatever is still queued would
    // then wait for a callback that never arrives — and never time out — so it is retried here instead.
    if !pendingNotifications.isEmpty && peripheralManager != nil {
      drainPendingNotifications()
    }
  }

  /// Arms the expiry that answers a delegated request if JavaScript never does. Called before the event
  /// is emitted, so a listener that responds synchronously still finds the request.
  private func registerPendingRequest(
    _ requestId: Int, request: CBATTRequest, isRead: Bool,
    deferredValues: [CharacteristicAddress: DeferredWrite] = [:]
  ) {
    var timeout: DispatchWorkItem?
    if requestTimeoutMs > 0 {
      let work = DispatchWorkItem { [weak self] in self?.expireRequest(requestId) }
      timeout = work
      DispatchQueue.main.asyncAfter(
        deadline: .now() + .milliseconds(requestTimeoutMs), execute: work
      )
    }
    pendingRequests[requestId] = PendingRequest(
      request: request, isRead: isRead, deferredValues: deferredValues, timeout: timeout
    )
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

  @discardableResult
  private func discardPendingRequest(_ requestId: Int) -> PendingRequest? {
    guard let pending = pendingRequests.removeValue(forKey: requestId) else { return nil }
    pending.timeout?.cancel()
    return pending
  }

  private func discardPendingRequests(where predicate: (PendingRequest) -> Bool) {
    for (requestId, pending) in pendingRequests where predicate(pending) {
      pending.timeout?.cancel()
      pendingRequests.removeValue(forKey: requestId)
    }
  }

  /// Discards matching requests after answering each one with `result`.
  ///
  /// Used where the central may still hold a usable ATT bearer. `respondToRequest:withResult:` "must be
  /// called" for every read and write callback, and an unanswered request stalls that bearer until the
  /// 30 s transaction timeout retires it permanently (Core Spec Vol 3, Part F, §3.3.3). Every response
  /// is sent after the bookkeeping is settled, so CoreBluetooth is never called back into mid-teardown.
  private func answerAndDiscardPendingRequests(
    withResult result: CBATTError.Code, where predicate: (PendingRequest) -> Bool
  ) {
    var answered: [CBATTRequest] = []
    for (requestId, pending) in pendingRequests where predicate(pending) {
      pending.timeout?.cancel()
      pendingRequests.removeValue(forKey: requestId)
      answered.append(pending.request)
    }
    for request in answered {
      peripheralManager?.respond(to: request, withResult: result)
    }
  }

  /// Answers a pending read or write request.
  ///
  /// `offset` states where `value` begins within the attribute, and the response is rebased onto the
  /// offset the request actually asked for — so passing offset 0 with the whole value answers a Read
  /// Blob continuation correctly, and passing the request's own offset with a pre-sliced value works
  /// too. Android honours the same contract.
  func sendResponse(
    deviceId: String, requestId: Int, status: Int,
    offset: Int, value: Data
  ) throws {
    // Everything that could reject the call is checked before the pending entry is consumed, so a
    // failed attempt leaves the request answerable instead of stranding the central until its ATT
    // transaction times out.
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
    // Committed before the response goes out, so a central that reads straight after its write response
    // sees what it wrote. Only a success commits them: any ATT error rejects the whole batch, which
    // Apple documents as all-or-nothing.
    if result == .success {
      for (address, deferred) in pending.deferredValues {
        // Anything written while the batch was outstanding — by updateCharacteristicValue or by another
        // central — is the newer intent, and reverting it here is what nothing could recover from.
        guard characteristicValues[address] == deferred.baseline else { continue }
        characteristicValues[address] = deferred.value
      }
    }
    // Deliberately not size-checked: the central continues a value longer than one `ATT_READ_RSP` with
    // `ATT_READ_BLOB_REQ`, so answering with more than fits is normal ATT. Apple's own guidance is to
    // assign the whole remainder from the request's offset, which the automatic read path also does.
    peripheralManager?.respond(to: request, withResult: result)
  }

  private func responsePayload(
    for pending: PendingRequest, requestId: Int, offset: Int, value: Data
  ) throws -> Data {
    try rebasedResponseValue(
      value, isRead: pending.isRead, suppliedOffset: offset,
      requestedOffset: pending.request.offset, requestId: requestId
    )
  }

  /// Rebases a response value supplied from `suppliedOffset` onto `requestedOffset`, the offset the
  /// request actually asked for. `CBATTRequest.offset` is read-only and `respond(to:withResult:)` takes
  /// no offset — CoreBluetooth derives it from the request — so honouring the caller's offset means
  /// aligning the value to it here.
  ///
  /// Split out from `responsePayload` so it can be exercised without a `CBATTRequest`, which has no
  /// public initialiser. Android's `responsePayload` implements the same contract.
  func rebasedResponseValue(
    _ value: Data, isRead: Bool, suppliedOffset: Int, requestedOffset: Int, requestId: Int
  ) throws -> Data {
    // A Write Response carries no value, so there is nothing to rebase.
    guard isRead else { return value }

    guard suppliedOffset <= requestedOffset else {
      throw GattServerError.responseOffsetAfterRequest(
        requestId: requestId, requested: requestedOffset, supplied: suppliedOffset
      )
    }
    let skip = requestedOffset - suppliedOffset
    guard skip > 0 else { return value }
    // The caller supplied nothing at or beyond the requested offset, which is the specification's
    // signal that the attribute ends there.
    guard skip < value.count else { return Data() }
    return value.subdata(in: skip..<value.count)
  }

  /// Replaces the mirrored value that a read of this characteristic is answered from.
  ///
  /// The address is checked against the published database first, because an unrecognised key would be
  /// stored successfully and silently, somewhere no read would ever look.
  func updateCharacteristicValue(
    serviceUuid: String, characteristicUuid: String, value: Data
  ) throws {
    guard let peripheral = peripheralManager else { throw GattServerError.serverStopped }
    // A database exists only while powered on — Apple documents that "the powered off state clears the
    // local database" — so anything else is the Bluetooth problem it is, not a missing characteristic.
    guard peripheral.state == .poweredOn else {
      throw GattServerError.bluetoothUnavailable(state: peripheral.state)
    }
    let address = CharacteristicAddress(
      service: CBUUID(string: serviceUuid),
      characteristic: CBUUID(string: characteristicUuid)
    )
    guard findCharacteristic(
      serviceUuid: address.service, characteristicUuid: address.characteristic
    ) != nil else {
      throw GattServerError.characteristicNotFound(
        service: serviceUuid, characteristic: characteristicUuid
      )
    }
    characteristicValues[address] = value
  }

  /// Releases everything the server holds, so a later `open` starts from an empty database.
  ///
  /// Unpublishing goes through `removeAllServices` rather than removing each entry of `addedServices`:
  /// that mirror only holds what `peripheralManager(_:didAdd:error:)` has already acknowledged, so a
  /// service still awaiting its callback — or one whose callback reported an error — would stay in the
  /// shared GATT database for the lifetime of the process and collide with the next `createServer`.
  func stop() {
    stopAdvertising()
    completeOpen(GattServerError.serverStopped)
    flushReadinessWaiters(GattServerError.serverStopped)
    failPendingNotifications(.serverStopped)
    peripheralManager?.removeAllServices()

    // Cleared before the delegate is dropped: `peripheralManagerDidUpdateState` re-publishes
    // `serviceConfiguration` on every transition to powered on, and a stopped server must not come
    // back to life through either route.
    serviceConfiguration.removeAll()
    servicesAwaitingRegistration.removeAll()
    publication = .idle
    registrationFailed = false
    addedServices.removeAll()
    connectedCentrals.removeAll()
    centralPayloadLengths.removeAll()
    subscribedCentrals.removeAll()
    characteristicValues.removeAll()
    discardPendingRequests { _ in true }
    delegations.removeAll()
    requestCounter = 0
    onStateChange = nil

    // CoreBluetooth can outlive this reference and still deliver a queued callback, which would
    // otherwise repopulate the state just cleared above.
    peripheralManager?.delegate = nil
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

  /// The service-and-characteristic address of a characteristic CoreBluetooth handed back, or `nil` when
  /// the published database cannot name exactly one owner for it.
  ///
  /// Neither obvious route suffices alone: `CBCharacteristic.service` is declared `weak`, so a
  /// torn-down database may already have cleared it, and a characteristic UUID may legally appear in two
  /// services, so a search by UUID alone can be ambiguous. Matching the instance the database actually
  /// holds settles both; what is left genuinely ambiguous is reported rather than guessed at.
  private func address(of characteristic: CBCharacteristic) -> CharacteristicAddress? {
    func address(in service: CBMutableService) -> CharacteristicAddress {
      CharacteristicAddress(service: service.uuid, characteristic: characteristic.uuid)
    }
    if let owner = addedServices.values.first(where: {
      $0.characteristics?.contains(where: { $0 === characteristic }) ?? false
    }) {
      return address(in: owner)
    }
    if let serviceUuid = characteristic.service?.uuid, let owner = addedServices[serviceUuid] {
      return address(in: owner)
    }
    let owners = addedServices.values.filter {
      $0.characteristics?.contains { $0.uuid == characteristic.uuid } ?? false
    }
    guard owners.count == 1, let owner = owners.first else { return nil }
    return address(in: owner)
  }

  /// Assembles one written fragment into an attribute's value, or returns `nil` when `offset` is past
  /// the current end — which the specification answers with "Invalid Offset".
  ///
  /// `queued` selects between the two write procedures, which disagree about the octets *after* the
  /// fragment:
  ///
  /// - An unqueued `ATT_WRITE_REQ` carries only a handle and a value, and "the attribute value shall be
  ///   truncated or lengthened to match the length of the Attribute Value parameter" (Core Spec Vol 3,
  ///   Part F, §3.4.5.1) — so a shorter write shortens the attribute, tail and all.
  /// - A queued write's part goes at "the offset of the first octet where the Part Attribute Value
  ///   parameter is to be written" (§3.4.6.1), which says nothing about the octets beyond it, so they
  ///   are preserved. Android's prepared-write path does exactly this, and matching it is what keeps
  ///   one long write leaving the same attribute behind on both platforms.
  ///
  /// Assembling a queued part at offset 0 as a replacement — which this used to do, having only the
  /// offset to go on — truncated an attribute that a long write did not cover to its end, while Android
  /// kept the remainder.
  func spliced(_ current: Data, offset: Int, part: Data, queued: Bool) -> Data? {
    guard offset <= current.count else { return nil }
    guard queued else { return part }
    var result = Data(current.prefix(offset))
    result.append(part)
    result.append(contentsOf: current.dropFirst(offset + part.count))
    return result
  }

  /// Whether a batch of write offsets can only have come from the queued-write procedure.
  ///
  /// CoreBluetooth runs that procedure below the app layer and delivers its result through the same
  /// callback as an ordinary write, with no flag telling them apart — so the shape of the batch is the
  /// only evidence. More than one request, or any request at a non-zero offset, is beyond what a single
  /// `ATT_WRITE_REQ` can produce and therefore names a queued write.
  ///
  /// What is left genuinely ambiguous is a lone part at offset 0: identical in shape to an unqueued
  /// write, so it is assembled as one. That is the single case where a long write can still leave a
  /// different attribute behind than it does on Android, which sees the procedure directly and keeps the
  /// tail. It costs a tail only when a client splits a value into exactly one part *and* stops short of
  /// the attribute's end.
  ///
  /// Takes the offsets rather than the requests, because `CBATTRequest` has no public initialiser and
  /// this is the whole of what the decision depends on.
  func isQueuedWriteBatch(offsets: [Int]) -> Bool {
    offsets.count > 1 || offsets.contains { $0 > 0 }
  }

  private func nextRequestId() -> Int {
    requestCounter += 1
    return requestCounter
  }

  /// Records ATT activity from `central` and reports a first sighting as a connection. The stored
  /// reference is always refreshed, because CoreBluetooth may hand out a distinct `CBCentral` instance
  /// per callback and `maximumUpdateValueLength` is read from whichever one is current.
  @discardableResult
  private func noteActivity(from central: CBCentral) -> String {
    let deviceId = central.identifier.uuidString
    let isFirstSighting = connectedCentrals[deviceId] == nil
    connectedCentrals[deviceId] = central
    if isFirstSighting {
      // CoreBluetooth exposes no name for a central — only `CBPeripheral` has one.
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

  /// The current link budget for `deviceId`, or `nil` when no such central is known. Read live from the
  /// retained `CBCentral` rather than from the change-detection cache.
  func mtu(for deviceId: String) -> DeviceMtu? {
    guard let central = connectedCentrals[deviceId] else { return nil }
    return DeviceMtu(maxNotificationPayload: central.maximumUpdateValueLength)
  }

  /// Every central that has subscribed, read or written and not since been dropped — not every central
  /// holding a link, since CoreBluetooth declares no connection-level callback.
  var connectedDeviceIds: [String] {
    Array(connectedCentrals.keys)
  }

  var isServerRunning: Bool {
    databasePublished
  }

  /// Read straight from CoreBluetooth, which tracks this itself.
  var isAdvertising: Bool {
    peripheralManager?.isAdvertising ?? false
  }

  /// Drops every trace of `deviceId` and reports the disconnection exactly once: a device that was
  /// never seen, or has already been reported, produces nothing.
  ///
  /// The disconnection is inferred rather than reported, so anything the central left pending is
  /// answered instead of dropped: it may well still be connected, and a dropped request would cost it
  /// its ATT bearer. "Unlikely Error" is the closest code the specification offers for a valid request
  /// the server abandoned, and is what an expiry sends too.
  private func markDisconnected(_ deviceId: String, reason: GattServerError) {
    guard connectedCentrals.removeValue(forKey: deviceId) != nil else { return }
    centralPayloadLengths.removeValue(forKey: deviceId)
    subscribedCentrals.removeValue(forKey: deviceId)
    answerAndDiscardPendingRequests(withResult: .unlikelyError) {
      $0.request.central.identifier.uuidString == deviceId
    }
    failPendingNotifications(reason) { $0.deviceId == deviceId }
    delegate?.onDeviceDisconnected(deviceId: deviceId)
  }
}

extension GattServerManager: CBPeripheralManagerDelegate {
  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    onStateChange?(peripheral.state)

    switch peripheral.state {
    case .poweredOn:
      // Apple documents that "the powered off state clears the local database; in this case you must
      // explicitly re-add all services", which is why `serviceConfiguration` is retained and every
      // transition to powered on re-publishes it — the first one included. Waiters are released by the
      // publication itself, several main-queue turns later, rather than here.
      publishConfiguredServices(on: peripheral)
    case .unknown:
      // Nothing has happened yet — a further state update is coming.
      break
    case .resetting:
      // `CBManagerState.resetting` is 1, below `poweredOff`'s 4, so both of Apple's thresholds apply:
      // every central has been disconnected and the local database is cleared. Only the waiters are
      // spared, because a further state update really is coming and the re-publish that follows powering
      // on can still satisfy them.
      discardPublishedDatabase(reason: .bluetoothUnavailable(state: peripheral.state))
    default:
      let error = GattServerError.bluetoothUnavailable(state: peripheral.state)
      discardPublishedDatabase(reason: error)
      completeOpen(error)
      flushReadinessWaiters(error)
    }
  }

  /// Discards everything that only existed while the database was published, and reports every
  /// subscription and connection as ended.
  ///
  /// Apple documents a state below powered on as meaning "any connected centrals have been
  /// disconnected", and one below powered off as clearing the local database — so this runs for
  /// `resetting` as well as `poweredOff`, `unauthorized` and `unsupported`. Requests are dropped rather
  /// than answered, because the bearers they belong to are gone with the connections.
  private func discardPublishedDatabase(reason: GattServerError) {
    publication = .idle
    servicesAwaitingRegistration.removeAll()

    // `peripheralManagerDidStartAdvertising:error:` is documented only as returning "the result of a
    // startAdvertising: call", with nothing promising one arrives when the state drops instead — so a
    // start CoreBluetooth already has is settled here rather than left pending for the process
    // lifetime. Claiming the completion is what stops a late callback settling it a second time, and
    // the expiry goes with the advertisement it belonged to rather than stopping a later one.
    cancelAdvertisingTimeout()
    claimAdvertisingCompletion()?(reason)

    // Every subscription dies with the database, so report each one as ended.
    let ended = subscribedCentrals.map { ($0.key, Array($0.value.keys)) }
    for (deviceId, addresses) in ended {
      for address in addresses {
        delegate?.onCharacteristicUnsubscribed(
          deviceId: deviceId,
          serviceUuid: address.service.normalizedString,
          characteristicUuid: address.characteristic.normalizedString
        )
      }
    }
    addedServices.removeAll()

    // This is the one moment iOS can report a disconnection for a central that never subscribed to
    // anything.
    let disconnected = Array(connectedCentrals.keys)
    connectedCentrals.removeAll()
    centralPayloadLengths.removeAll()
    subscribedCentrals.removeAll()
    discardPendingRequests { _ in true }
    failPendingNotifications(reason)
    for deviceId in disconnected {
      delegate?.onDeviceDisconnected(deviceId: deviceId)
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didStartAdvertising error: Error?) {
    claimAdvertisingCompletion()?(error)
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didAdd service: CBService, error: Error?
  ) {
    // Bluetooth dropping mid-registration discards the database while adds are still outstanding; without
    // this the late acknowledgement would publish it again and report a server that no longer exists.
    guard publication == .inProgress else { return }

    servicesAwaitingRegistration.remove(service.uuid)

    if let error = error {
      registrationFailed = true
      completeOpen(GattServerError.serviceRegistrationFailed(
        uuid: service.uuid.normalizedString,
        reason: error.localizedDescription
      ))
    } else {
      // Mirrored from the configuration this manager built rather than from the callback's `CBService`,
      // because only the configured instance is guaranteed to carry the characteristics — and it is
      // matching those instances that lets `address(of:)` name a characteristic's service without
      // relying on the weak back-pointer.
      addedServices[service.uuid] = serviceConfiguration.first { $0.uuid == service.uuid }
        ?? service as? CBMutableService
        ?? CBMutableService(type: service.uuid, primary: service.isPrimary)
    }

    // The set is no longer cleared on failure, so the siblings still registering are waited for rather
    // than abandoned — which is what makes the unpublish below safe to schedule.
    guard servicesAwaitingRegistration.isEmpty else { return }
    if registrationFailed {
      publication = .failed
      unpublishFailedRegistration()
      // Settled here rather than left parked for a re-publish that is not coming: nothing retries a
      // failed registration, so the next state update is the only other thing that could release them.
      flushReadinessWaiters(GattServerError.databaseNotPublished)
    } else {
      publication = .published
      completeOpen(nil)
      flushReadinessWaiters(nil)
    }
  }

  /// Takes back the services a failed registration round did manage to publish, which otherwise stay in
  /// the process-wide GATT database until the next `createServer` calls `stop()`.
  ///
  /// Whether `removeAllServices` may be called from inside `peripheralManager(_:didAdd:error:)`, with
  /// sibling `add(_:)` calls still outstanding, is documented nowhere — so neither is relied on. This
  /// runs only once every service of the round has reported, and only on the next main-queue turn, so
  /// the callback has returned by then. A re-publish may have been queued behind it, and owns the
  /// database if so.
  private func unpublishFailedRegistration() {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      guard self.registrationFailed,
            self.servicesAwaitingRegistration.isEmpty,
            !self.databasePublished else { return }
      self.peripheralManager?.removeAllServices()
      self.addedServices.removeAll()
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didSubscribeTo characteristic: CBCharacteristic
  ) {
    // Subscribing is one of the activity signals a connection is derived from, but it does not double
    // as the connection event: a central that subscribes to three characteristics is one connection,
    // which `noteActivity` reports once.
    let deviceId = noteActivity(from: central)
    // An unresolvable subscription is reported but not recorded: filed under the wrong service it would
    // make a send to another service's same-named characteristic look deliverable, and CoreBluetooth
    // would then drop it silently.
    guard let address = address(of: characteristic) else {
      delegate?.onCharacteristicSubscribed(
        deviceId: deviceId,
        serviceUuid: "",
        characteristicUuid: characteristic.uuid.normalizedString
      )
      return
    }
    var subs = subscribedCentrals[deviceId] ?? [:]
    subs[address] = central
    subscribedCentrals[deviceId] = subs
    delegate?.onCharacteristicSubscribed(
      deviceId: deviceId,
      serviceUuid: address.service.normalizedString,
      characteristicUuid: characteristic.uuid.normalizedString
    )
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didUnsubscribeFrom characteristic: CBCharacteristic
  ) {
    let deviceId = central.identifier.uuidString
    let resolved = address(of: characteristic)
    if let resolved = resolved {
      subscribedCentrals[deviceId]?.removeValue(forKey: resolved)
    } else {
      // Dropping every same-named entry is the conservative choice for an address that cannot be named:
      // one left behind would keep a central looking subscribed after it stopped listening.
      subscribedCentrals[deviceId] = subscribedCentrals[deviceId]?
        .filter { $0.key.characteristic != characteristic.uuid }
    }
    delegate?.onCharacteristicUnsubscribed(
      deviceId: deviceId,
      serviceUuid: resolved?.service.normalizedString ?? "",
      characteristicUuid: characteristic.uuid.normalizedString
    )
    // Nothing will ever accept these now, so fail them instead of leaking the queue.
    failPendingNotifications(.deviceDisconnected(deviceId: deviceId)) { entry in
      guard entry.deviceId == deviceId else { return false }
      guard let resolved = resolved else {
        return entry.address.characteristic == characteristic.uuid
      }
      return entry.address == resolved
    }
    // CoreBluetooth delivers this same callback whether the central cleared its Client Characteristic
    // Configuration or simply went away, and offers nothing to tell the two apart, so losing the last
    // subscription is the only disconnect signal available. A central that unsubscribes but stays
    // connected is therefore reported as disconnected, and a later read or write re-discovers it.
    if subscribedCentrals[deviceId]?.isEmpty == true {
      markDisconnected(deviceId, reason: .deviceDisconnected(deviceId: deviceId))
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveRead request: CBATTRequest
  ) {
    noteActivity(from: request.central)

    // Answered with an error rather than from a value that may belong to a same-named characteristic in
    // another service: the only thing worse than failing a read is answering it with another attribute's
    // contents.
    guard let address = address(of: request.characteristic) else {
      peripheral.respond(to: request, withResult: .unlikelyError)
      return
    }

    // An opted-in characteristic always reaches JS. A configured initial value is served from this cache
    // rather than the CBMutableCharacteristic initialiser, so without the opt-in a characteristic
    // declared with `value` would never produce a single read event.
    if !delegation(for: address).read, let value = characteristicValues[address] {
      let offset = request.offset

      // An offset past the end is answered with `CBATTError.invalidOffset`, 0x07 (Core Spec Vol 3,
      // Part F, §3.4.1.1), rather than handed to a listener the characteristic never opted in to, which
      // left the central waiting for its ATT transaction to time out. An offset equal to the length is
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
      serviceUuid: address.service.normalizedString,
      characteristicUuid: request.characteristic.uuid.normalizedString,
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

    // Resolved before anything is applied: a fragment whose owning service cannot be named would
    // otherwise be written into a same-named characteristic of another service. The batch is an atomic
    // unit, so one unresolvable fragment fails all of it.
    var addresses: [CharacteristicAddress] = []
    for request in requests {
      guard let address = address(of: request.characteristic) else {
        peripheral.respond(to: first, withResult: .unlikelyError)
        return
      }
      addresses.append(address)
    }

    // Apple documents that `respond(to:withResult:)` must be called exactly once per callback, passing
    // the first request of the array. So a batch JavaScript has to answer is one pending request backed
    // by `first`, and the first `sendResponse` for its id answers the whole batch.
    let batchId = nextRequestId()
    // Decided per characteristic, as the direct write path already does: one that never opted in must
    // still have its value applied, even when a sibling in the same batch delegates.
    let delegated = Set(addresses.filter { delegation(for: $0).write })

    // Everything is assembled before anything is applied or answered, so a fragment the attribute
    // cannot take fails the whole batch rather than half of it.
    let queued = isQueuedWriteBatch(offsets: requests.map { $0.offset })
    var assembled: [CharacteristicAddress: Data] = [:]
    for (request, address) in zip(requests, addresses) {
      let current = assembled[address] ?? characteristicValues[address] ?? Data()
      guard let merged = spliced(
        current, offset: request.offset, part: request.value ?? Data(), queued: queued
      ) else {
        peripheral.respond(to: first, withResult: .invalidOffset)
        return
      }
      assembled[address] = merged
    }

    // A delegated characteristic's value is JavaScript's to commit with `updateCharacteristicValue` once
    // it has accepted the write. The rest of a partially delegated batch waits for that same answer, so
    // the batch stays the atomic unit Apple documents: "if the execution of one of the requests would
    // cause a failure [...] none of the requests should be executed".
    let automatic = assembled.filter { !delegated.contains($0.key) }
    if delegated.isEmpty {
      for (address, value) in automatic {
        characteristicValues[address] = value
      }
      peripheral.respond(to: first, withResult: .success)
    } else {
      var deferred: [CharacteristicAddress: DeferredWrite] = [:]
      for (address, value) in automatic {
        deferred[address] = DeferredWrite(value: value, baseline: characteristicValues[address])
      }
      registerPendingRequest(batchId, request: first, isRead: false, deferredValues: deferred)
    }

    for (request, address) in zip(requests, addresses) {
      delegate?.onCharacteristicWriteRequest(
        deviceId: request.central.identifier.uuidString,
        requestId: batchId,
        serviceUuid: address.service.normalizedString,
        characteristicUuid: request.characteristic.uuid.normalizedString,
        offset: request.offset,
        value: request.value ?? Data(),
        responseNeeded: delegated.contains(address)
      )
    }
  }

  /// Resends only the payloads that were actually refused, oldest first, stopping as soon as the transmit
  /// queue fills again so the rest keep their place in line.
  func peripheralManagerIsReady(_ peripheral: CBPeripheralManager) {
    drainPendingNotifications()
  }
}
