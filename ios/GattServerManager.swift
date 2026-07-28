import CoreBluetooth

/// Default ATT_MTU, in octets — Core Spec Vol 3, Part G, §5.2.1.
let defaultAttMtu = 23

/// Header octets in ATT_HANDLE_VALUE_NTF/IND PDU: 1-octet opcode + 2-octet handle (Core Spec Vol 3, Part F, §§3.4.7.1–3.4.7.2).
let attNotificationHeaderSize = 3

private let defaultAttMtuPayload = defaultAttMtu - attNotificationHeaderSize

/// "The maximum length of an attribute value shall be 512 octets" — Core Spec Vol 3, Part F, §3.2.9.
let maxAttributeValueLength = 512

/// Checked separately: a queued write can exceed per-PDU limits; an unqueued write cannot.
func exceedsAttributeLength(_ size: Int) -> Bool {
  size > maxAttributeValueLength
}

/// Octets that fit in one notification: min of link MTU and max attribute value length.
func notificationPayloadLimit(for central: CBCentral) -> Int {
  min(central.maximumUpdateValueLength, maxAttributeValueLength)
}

/// Upper bound per central while the transmit queue is full. Per-central limit prevents one silent link from starving others.
private let maxQueuedNotifications = 64

/// How long a parked notification waits before abandoned. Above `attTransactionTimeoutMs` so slow links aren't cut off prematurely.
private let notificationTimeoutMs = 35_000

/// ATT transaction timeout; failure requires new bearer (Core Spec Vol 3, Part F, §3.3.3). Module timeout must be below this.
let attTransactionTimeoutMs = 30_000

/// Request timeout for JavaScript handlers; below attTransactionTimeoutMs with margin for bearer recovery.
let defaultRequestTimeoutMs = 10_000

/// How long a registration round waits before timing out — generously above what healthy hardware needs.
let publicationTimeoutMs = 30_000

/// Timeout for peripheralManagerDidStartAdvertising callback (Apple guarantees nothing). Matches Android AdvertiseCallback.
let advertisingStartTimeoutMs = 30_000

enum GattServerError: Error {
  case payloadExceedsMtu(maxPayload: Int, payloadSize: Int)
  case requestNotFound(requestId: Int)
  case requestDeviceMismatch(requestId: Int, owner: String, supplied: String)
  case responseOffsetAfterRequest(requestId: Int, requested: Int, supplied: Int)
  case responseOffsetNegative(requestId: Int, requested: Int, supplied: Int)
  case bluetoothUnavailable(state: CBManagerState)
  case serviceRegistrationFailed(uuid: String, reason: String)
  case publicationTimedOut(awaiting: [String], timeoutMs: Int)
  case serverStopped
  case databaseNotPublished
  case characteristicNotFound(service: String, characteristic: String)
  case notifyQueueFull(limit: Int)
  case notificationTimedOut(timeoutMs: Int)
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
    case .responseOffsetAfterRequest, .responseOffsetNegative: return "ERR_RESPONSE_OFFSET"
    case .bluetoothUnavailable(let state):
      return state == .unauthorized ? "ERR_PERMISSION" : "ERR_BLUETOOTH"
    case .serviceRegistrationFailed, .publicationTimedOut: return "ERR_CREATE_SERVER"
    case .serverStopped, .databaseNotPublished: return "ERR_NO_SERVER"
    case .characteristicNotFound: return "ERR_CHARACTERISTIC_NOT_FOUND"
    case .notifyQueueFull: return "ERR_NOTIFY_QUEUE_FULL"
    case .notificationTimedOut: return "ERR_NOTIFY"
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
    case .responseOffsetNegative(let requestId, let requested, let supplied):
      return "Request \(requestId) was answered with offset \(supplied) against a requested offset " +
        "of \(requested). An ATT offset is an unsigned 16-bit value."
    case .bluetoothUnavailable(let state):
      switch state {
      case .poweredOff: return "Bluetooth is turned off"
      case .unauthorized: return "Bluetooth permission not granted"
      case .unsupported: return "BLE not supported on this device"
      default: return "Bluetooth not ready"
      }
    case .serviceRegistrationFailed(let uuid, let reason):
      return "Failed to publish service \(uuid): \(reason)"
    case .publicationTimedOut(let awaiting, let timeoutMs):
      return "CoreBluetooth did not acknowledge \(awaiting.joined(separator: ", ")) within " +
        "\(timeoutMs) ms, so the database was not published. Call createServer again to retry."
    case .serverStopped:
      return "Server was stopped before it finished opening"
    case .databaseNotPublished:
      return "No GATT database is published, so there is nothing to advertise. Wait for createServer " +
        "to resolve; isServerRunning reports whether the database is still there, which a failed " +
        "registration or Bluetooth going down undoes."
    case .characteristicNotFound(let service, let characteristic):
      return "Characteristic \(characteristic) was not found in service \(service)"
    case .notifyQueueFull(let limit):
      return "\(limit) notifications are already waiting for the transmit queue to drain for this " +
        "central. Wait for earlier sends to resolve before queueing more."
    case .notificationTimedOut(let timeoutMs):
      return "CoreBluetooth did not report the transmit queue ready within \(timeoutMs) ms, so the " +
        "notification was abandoned and the ones behind it were retried. The central may have gone " +
        "away without CoreBluetooth reporting it."
    case .deviceDisconnected(let deviceId):
      return "Device \(deviceId) disconnected"
    case .noSubscriber(let deviceId, let characteristic):
      return "Device \(deviceId) has not subscribed to characteristic \(characteristic). " +
        "Wait for onCharacteristicSubscribed. CoreBluetooth only transmits to subscribed " +
        "centrals, so this cannot be overridden on iOS."
    case .confirmUnsupported(let characteristic, let confirm):
      // updateValue has no confirm parameter; this check prevents silent mismatch.
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

/// Surfaces as `ERR_ADVERTISE`, matching Android.
private func advertisingError(_ message: String) -> NSError {
  NSError(domain: "ExpoGattServer", code: 0, userInfo: [NSLocalizedDescriptionKey: message])
}

/// The link budget for one central, expressed in the units the public API uses.
struct DeviceMtu {
  /// ATT_MTU in octets.
  let mtu: Int
  /// Octets that fit in one notification or indication: `ATT_MTU - 3`.
  let maxNotificationPayload: Int

  /// iOS reports only payload length; reconstructed to ATT_MTU. Capped by max attribute value length (Core Spec Vol 3, Part F, §3.2.9).
  init(maxNotificationPayload: Int) {
    self.mtu = maxNotificationPayload + attNotificationHeaderSize
    self.maxNotificationPayload = min(maxNotificationPayload, maxAttributeValueLength)
  }
}

struct CharacteristicAddress: Hashable {
  let service: CBUUID
  let characteristic: CBUUID
}

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
  /// The published database went away for a reason no promise is waiting to report. See
  /// `reportPublicationFailure`.
  func onServerPublicationFailed(code: String, message: String)
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

  /// The shortest spelling of this UUID that means the same thing, for use in an advertisement.
  ///
  /// Unlike Android's encoder, `CBUUID` advertises whatever width it was constructed from: a 16-bit
  /// alias occupies two octets of the 31-byte budget, its 128-bit expansion sixteen. The shared
  /// TypeScript layer expands every UUID to 128 bits so both platforms see one spelling — correct for
  /// addressing and for event payloads, and free on Android, but on iOS it silently cost fourteen bytes
  /// of advertising space per UUID. That is enough to push a service UUID out of the advertisement and
  /// into the Apple-only scan-response overflow area, where a non-Apple central filtering on it stops
  /// finding the peripheral at all.
  ///
  /// Only exact members of the Bluetooth base range contract; a vendor UUID has no shorter form and is
  /// returned unchanged.
  var advertisedForm: CBUUID {
    let lower = uuidString.lowercased()
    guard lower.count == 36, lower.hasSuffix(bluetoothBaseUuidSuffix) else { return self }
    let leading = String(lower.prefix(8))
    // A 16-bit alias is a 32-bit one whose top half is zero, and `CBUUID(string:)` accepts both widths.
    let short = leading.hasPrefix("0000") ? String(leading.suffix(4)) : leading
    return CBUUID(string: short)
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

/// All state must be reached from the main queue; no concurrent access. CBPeripheralManager is created with queue: .main.
class GattServerManager: NSObject {
  weak var delegate: GattServerManagerDelegate?

  /// Milliseconds a delegated request may go unanswered; `0` disables the expiry entirely.
  private let requestTimeoutMs: Int

  init(requestTimeoutMs: Int = defaultRequestTimeoutMs) {
    self.requestTimeoutMs = requestTimeoutMs
    super.init()
  }

  var onStateChange: ((CBManagerState) -> Void)?

  private var peripheralManager: CBPeripheralManager?
  private var serviceConfiguration: [CBMutableService] = []
  private var servicesAwaitingRegistration: Set<CBUUID> = []
  private var openCompletion: ((Error?) -> Void)?
  private var readinessWaiters: [(Error?) -> Void] = []
  private var advertisingCompletion: ((Error?) -> Void)?
  private var advertisingTimeout: DispatchWorkItem?

  /// Cancelled by `claimAdvertisingCompletion`, live only while a start awaits.
  private var advertisingStartTimeout: DispatchWorkItem?

  /// The `timeoutMs` to arm after the start succeeds.
  private var pendingAdvertisingTimeoutMs = 0

  /// Bumped by every stop; lets a waiting start detect that stop() was called.
  private var advertisingGeneration = 0

  private var addedServices: [CBUUID: CBMutableService] = [:]

  /// Current publication round progress. Resets to idle when powered off (Core Spec Vol 3, Part F, §3.4.2).
  private enum DatabasePublication {
    /// Nothing published; round expected (initial or after power cycle).
    case idle
    case inProgress
    case published
    case failed
  }

  private var publication: DatabasePublication = .idle

  /// Tags the current publication round; prevents stale timeouts from settling newer rounds.
  private var publicationGeneration = 0

  /// Bounds a publication round. CoreBluetooth offers no guarantee `didAdd` arrives, so without this bound callers hang forever.
  private var publicationTimeout: DispatchWorkItem?

  /// Whether every configured service is currently published.
  private var databasePublished: Bool { publication == .published }

  /// Round failure, or nil if viable. Shared with three audiences: completion, waiters, and event. Must use same error for all.
  private var registrationFailure: GattServerError?

  /// Services queued for `add(_:)`, serialized one at a time to correlate `didAdd` callbacks with rounds. CoreBluetooth passes no round context.
  private var registrationQueue: [CBMutableService] = []
  private var outstandingRegistration: CBUUID?

  /// Count of `didAdd` callbacks from discarded rounds, spent before checking round state. Prevents stale acks from advancing current round.
  private var acknowledgementsOwedToDiscardedRounds = 0

  /// Whether this round reported to openCompletion; reset per round.
  private var roundReportedToCaller = false

  /// Connected centrals, keyed by UUID. Discovered on first ATT activity since CBPeripheralManagerDelegate has no connection-level callback.
  private var connectedCentrals: [String: CBCentral] = [:]

  /// Last payload length per central, cached to detect MTU changes on next activity.
  private var centralPayloadLengths: [String: Int] = [:]

  private var subscribedCentrals: [String: [CharacteristicAddress: CBCentral]] = [:]
  /// Keyed by (service, characteristic) since GATT allows the same UUID in different services.
  private var characteristicValues: [CharacteristicAddress: Data] = [:]
  private var pendingRequests: [Int: PendingRequest] = [:]
  private var requestCounter = 0
  private var delegations: [CharacteristicAddress: CharacteristicDelegation] = [:]

  /// Notifications refused by transmit queue, oldest first. Re-sent when `peripheralManagerIsReady` fires.
  private var pendingNotifications: [QueuedNotification] = []

  /// Unique ID per queued entry; prevents timeouts from abandoning the wrong one.
  private var nextNotificationId = 0

  private struct QueuedNotification {
    let id: Int
    let deviceId: String
    let address: CharacteristicAddress
    let characteristic: CBMutableCharacteristic
    let central: CBCentral
    let value: Data
    let completion: (Error?) -> Void
    /// Expiry held with entry so timeout and removal cannot separate.
    var timeout: DispatchWorkItem?
  }

  /// Value withheld from a partially delegated batch; baseline used to detect stale overwrites.
  private struct DeferredWrite {
    let value: Data
    /// nil when attribute had no value initially (distinct from empty).
    let baseline: Data?
  }

  private struct PendingRequest {
    let request: CBATTRequest
    /// True for read (carries response value); false for write (value is input only).
    let isRead: Bool
    /// Auto values for non-delegated characteristics, withheld until batch is accepted (atomicity).
    let deferredValues: [CharacteristicAddress: DeferredWrite]
    /// Expiry to cancel on answer or discard.
    let timeout: DispatchWorkItem?
  }

  /// Records which characteristics hand their ATT requests to JavaScript. Call before `open`.
  func setDelegations(_ map: [CharacteristicAddress: CharacteristicDelegation]) {
    delegations = map
  }

  private func delegation(for address: CharacteristicAddress) -> CharacteristicDelegation {
    delegations[address] ?? CharacteristicDelegation.none
  }

  /// Opens and publishes services. Completion fires once on the main queue; nil only after all services confirmed published.
  func open(
    services: [CBMutableService],
    initialValues: [CharacteristicAddress: Data] = [:],
    completion: @escaping (Error?) -> Void
  ) {
    serviceConfiguration = services
    characteristicValues = initialValues
    openCompletion = completion
    peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    armWaiterTimeout(reason: .bluetoothUnavailable(state: .unknown))
  }

  var bluetoothState: CBManagerState {
    peripheralManager?.state ?? .unknown
  }

  /// Invokes completion once services are published (not just when powered on). Transient states or in-progress publication parks the caller.
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
        armWaiterTimeout(reason: .databaseNotPublished)
      }
    case .unknown, .resetting:
      readinessWaiters.append(completion)
      armWaiterTimeout(reason: .bluetoothUnavailable(state: peripheral.state))
    default:
      completion(GattServerError.bluetoothUnavailable(state: peripheral.state))
    }
  }

  private func completeOpen(_ error: Error?) {
    guard let completion = openCompletion else { return }
    openCompletion = nil
    roundReportedToCaller = true
    completion(error)
  }

  private func flushReadinessWaiters(_ error: Error?) {
    let waiters = readinessWaiters
    readinessWaiters.removeAll()
    for waiter in waiters {
      waiter(error)
    }
  }

  /// Emits onServerPublicationFailed only when no promise is waiting (to avoid duplicate reports).
  private func reportPublicationFailure(_ error: Error) {
    guard openCompletion == nil, !roundReportedToCaller, readinessWaiters.isEmpty else { return }
    let gattError = error as? GattServerError
    delegate?.onServerPublicationFailed(
      code: gattError?.code ?? "ERR_CREATE_SERVER",
      message: gattError?.message ?? error.localizedDescription
    )
  }

  private func publishConfiguredServices(on peripheral: CBPeripheralManager) {
    publicationGeneration += 1
    publication = .inProgress
    registrationFailure = nil
    roundReportedToCaller = false
    registrationQueue = serviceConfiguration
    outstandingRegistration = nil
    servicesAwaitingRegistration = Set(serviceConfiguration.map { $0.uuid })
    guard !servicesAwaitingRegistration.isEmpty else {
      // No adds to consume owed acks; drop them. Empty database is valid for advertise-only peripherals.
      acknowledgementsOwedToDiscardedRounds = 0
      publication = .published
      cancelPublicationTimeout()
      completeOpen(nil)
      flushReadinessWaiters(nil)
      return
    }
    armPublicationTimeout()
    // Clears CoreBluetooth's database before adding. Apple clears on power-off and resetting; safe to call always.
    peripheral.removeAllServices()
    addNextService(on: peripheral)
  }

  /// Abandons the acknowledgement this round was waiting on, recording that one is still owed.
  private func noteDiscardedRegistration() {
    guard outstandingRegistration != nil else { return }
    outstandingRegistration = nil
    acknowledgementsOwedToDiscardedRounds += 1
  }

  /// Hands the next service of the round to `add(_:)`, one at a time. See [registrationQueue].
  private func addNextService(on peripheral: CBPeripheralManager) {
    guard publication == .inProgress, !registrationQueue.isEmpty else { return }
    let service = registrationQueue.removeFirst()
    outstandingRegistration = service.uuid
    peripheral.add(service)
  }

  /// Fails the round and notifies all audiences in correct order (completeOpen → reportPublicationFailure → flushReadinessWaiters).
  private func failPublicationRound(_ error: GattServerError) {
    registrationFailure = error
    publication = .failed
    registrationQueue.removeAll()
    noteDiscardedRegistration()
    servicesAwaitingRegistration.removeAll()
    cancelPublicationTimeout()
    unpublishFailedRegistration()
    completeOpen(error)
    reportPublicationFailure(error)
    flushReadinessWaiters(error)
  }

  /// Bounds the current round with a generation check.
  private func armPublicationTimeout() {
    cancelPublicationTimeout()
    let generation = publicationGeneration
    let work = DispatchWorkItem { [weak self] in
      guard let self = self,
            self.publicationGeneration == generation,
            self.publication == .inProgress else { return }
      self.publicationTimeout = nil
      // Clear outstanding (not owed): timeout is proof ack won't arrive.
      self.outstandingRegistration = nil
      let error = GattServerError.publicationTimedOut(
        awaiting: self.servicesAwaitingRegistration.map { $0.normalizedString }.sorted(),
        timeoutMs: publicationTimeoutMs
      )
      self.failPublicationRound(error)
    }
    publicationTimeout = work
    DispatchQueue.main.asyncAfter(
      deadline: .now() + .milliseconds(publicationTimeoutMs), execute: work
    )
  }

  private func cancelPublicationTimeout() {
    publicationTimeout?.cancel()
    publicationTimeout = nil
  }

  /// Bounds callers when no round is building (e.g., resetting state). Stored in publicationTimeout so rounds can replace it.
  private func armWaiterTimeout(reason: GattServerError) {
    guard openCompletion != nil || !readinessWaiters.isEmpty else { return }
    guard publicationTimeout == nil else { return }
    let generation = publicationGeneration
    let work = DispatchWorkItem { [weak self] in
      guard let self = self,
            self.publicationGeneration == generation,
            self.publication != .published else { return }
      self.publicationTimeout = nil
      self.completeOpen(reason)
      self.flushReadinessWaiters(reason)
    }
    publicationTimeout = work
    DispatchQueue.main.asyncAfter(
      deadline: .now() + .milliseconds(publicationTimeoutMs), execute: work
    )
  }

  /// Advertises once published; holds the call until then. A stop rejects it (same error as a stopped start in flight).
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

  /// Builds only CBAdvertisementDataLocalNameKey and CBAdvertisementDataServiceUUIDsKey (per Apple docs).
  private func beginAdvertising(
    localName: String?,
    serviceUuids: [CBUUID]?,
    timeoutMs: Int,
    completion: @escaping (Error?) -> Void
  ) {
    guard databasePublished else {
      completion(GattServerError.databaseNotPublished)
      return
    }
    let advertisedUuids = (serviceUuids ?? []).map { $0.advertisedForm }
    do {
      try assertAdvertisementFits(localName: localName, serviceUuids: advertisedUuids)
    } catch {
      completion(advertisingError(error.localizedDescription))
      return
    }
    let displaced = claimAdvertisingCompletion()
    displaced?(advertisingError("Advertising restarted"))
    // Stop the old one before starting the new one. Callback carries no identity, so race remains possible if starts are issued back-to-back.
    if displaced != nil {
      peripheralManager?.stopAdvertising()
    }
    advertisingCompletion = completion
    var advertisementData: [String: Any] = [:]
    if let name = localName {
      advertisementData[CBAdvertisementDataLocalNameKey] = name
    }
    if !advertisedUuids.isEmpty {
      advertisementData[CBAdvertisementDataServiceUUIDsKey] = advertisedUuids
    }
    cancelAdvertisingTimeout()
    // Held until peripheralManagerDidStartAdvertising fires (which reports the on-air time started).
    pendingAdvertisingTimeoutMs = timeoutMs
    peripheralManager?.startAdvertising(advertisementData)
    armAdvertisingStartTimeout()
  }

  func stopAdvertising() {
    advertisingGeneration += 1
    cancelAdvertisingTimeout()
    pendingAdvertisingTimeoutMs = 0
    peripheralManager?.stopAdvertising()
    claimAdvertisingCompletion()?(advertisingError("Advertising stopped"))
  }

  /// Takes ownership so the promise is settled by exactly one of didStartAdvertising, restart, stop, or Bluetooth down.
  private func claimAdvertisingCompletion() -> ((Error?) -> Void)? {
    defer {
      advertisingCompletion = nil
      cancelAdvertisingStartTimeout()
    }
    return advertisingCompletion
  }

  /// Stops advertising on timeout (avoiding hung start with radio on).
  private func armAdvertisingStartTimeout() {
    cancelAdvertisingStartTimeout()
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      self.advertisingStartTimeout = nil
      self.pendingAdvertisingTimeoutMs = 0
      self.peripheralManager?.stopAdvertising()
      self.claimAdvertisingCompletion()?(advertisingError(
        "The Bluetooth stack did not report the advertisement as started within " +
          "\(advertisingStartTimeoutMs) ms. Nothing is advertising."
      ))
    }
    advertisingStartTimeout = work
    DispatchQueue.main.asyncAfter(
      deadline: .now() + .milliseconds(advertisingStartTimeoutMs), execute: work
    )
  }

  private func cancelAdvertisingStartTimeout() {
    advertisingStartTimeout?.cancel()
    advertisingStartTimeout = nil
  }

  /// Emulates AdvertiseSettings.setTimeout by stopping at the limit (matching Android behavior).
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

  /// Sends one notification; reports outcome to completion (nil once CoreBluetooth accepts). Throws for pre-queue validation errors.
  /// Queue-refused payloads are retained and resent in order when peripheralManagerIsReady fires. Unsubscribed centrals are ERR_NO_SUBSCRIBER.
  func sendNotification(
    deviceId: String, serviceUuid: String,
    characteristicUuid: String, value: Data, confirm: Bool,
    completion: @escaping (Error?) -> Void
  ) throws {
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

    // updateValue takes no confirm flag; picks PDU from properties. Only checks declaration, not transport used.
    guard characteristic.properties.contains(confirm ? .indicate : .notify) else {
      throw GattServerError.confirmUnsupported(
        characteristic: characteristicUuid, confirm: confirm
      )
    }

    guard connectedCentrals[deviceId] != nil else {
      throw GattServerError.deviceDisconnected(deviceId: deviceId)
    }

    guard let centrals = subscribedCentrals[deviceId],
          let subscribed = centrals[address] else {
      throw GattServerError.noSubscriber(
        deviceId: deviceId, characteristic: characteristicUuid
      )
    }

    // Use current central instance for sizing (it's refreshed on every callback). Stale instance could reject valid payloads.
    let central = connectedCentrals[deviceId] ?? subscribed

    // Notifications are truncated silently (unlike reads which support Read Blob). Must enforce payload limit.
    let maxPayload = notificationPayloadLimit(for: central)
    guard value.count <= maxPayload else {
      throw GattServerError.payloadExceedsMtu(maxPayload: maxPayload, payloadSize: value.count)
    }

    nextNotificationId += 1
    let entry = QueuedNotification(
      id: nextNotificationId,
      deviceId: deviceId,
      address: address,
      characteristic: characteristic,
      central: central,
      value: value,
      completion: completion
    )

    guard pendingNotifications.isEmpty else {
      let queuedForCentral = pendingNotifications.reduce(0) {
        $0 + ($1.deviceId == deviceId ? 1 : 0)
      }
      guard queuedForCentral < maxQueuedNotifications else {
        throw GattServerError.notifyQueueFull(limit: maxQueuedNotifications)
      }
      park(entry)
      return
    }

    if !deliver(entry) {
      park(entry)
    }
  }

  /// Queues an entry that transmit queue refused, with expiry timeout.
  private func park(_ entry: QueuedNotification) {
    var parked = entry
    let id = entry.id
    let work = DispatchWorkItem { [weak self] in
      guard let self = self else { return }
      guard let index = self.pendingNotifications.firstIndex(where: { $0.id == id }) else { return }
      let abandoned = self.pendingNotifications.remove(at: index)
      abandoned.completion(GattServerError.notificationTimedOut(timeoutMs: notificationTimeoutMs))
      // Callback may never arrive, so retry rest instead of waiting for each to expire.
      self.drainPendingNotifications()
    }
    parked.timeout = work
    pendingNotifications.append(parked)
    DispatchQueue.main.asyncAfter(
      deadline: .now() + .milliseconds(notificationTimeoutMs), execute: work
    )
  }

  /// Delivers queued notifications until one is refused, dequeuing first so completion callbacks don't interfere.
  private func drainPendingNotifications() {
    while let next = pendingNotifications.first {
      pendingNotifications.removeFirst()
      guard deliver(next) else {
        // Requeue at head with existing expiry (don't re-arm).
        pendingNotifications.insert(next, at: 0)
        return
      }
      next.timeout?.cancel()
    }
  }

  /// Delivers one queued notification. Returns false only if transmit queue is full.
  private func deliver(_ entry: QueuedNotification) -> Bool {
    guard let peripheral = peripheralManager else {
      entry.completion(GattServerError.serverStopped)
      return true
    }
    // Resolve again: central instance can be refreshed between dequeue and send. Link budget may also shrink.
    let central = connectedCentrals[entry.deviceId] ?? entry.central
    let maxPayload = notificationPayloadLimit(for: central)
    guard entry.value.count <= maxPayload else {
      entry.completion(GattServerError.payloadExceedsMtu(
        maxPayload: maxPayload, payloadSize: entry.value.count
      ))
      return true
    }
    guard peripheral.updateValue(
      entry.value, for: entry.characteristic, onSubscribedCentrals: [central]
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
      entry.timeout?.cancel()
      entry.completion(error)
    }
    // Callback may have been for abandoned entry; retry rest to avoid forever-waiting entries.
    if !pendingNotifications.isEmpty && peripheralManager != nil {
      drainPendingNotifications()
    }
  }

  /// Arms expiry for unanswered delegated requests; called before the event fires so sync listeners can still find it.
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

  /// Answers unanswered request with unlikelyError so central doesn't stall waiting for timeout.
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

  /// Answers matching requests with result, then discards them. Responses sent after bookkeeping to avoid mid-teardown callbacks.
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

  /// Answers a pending request. Rebases value onto the requested offset; offset 0 with whole value always works.
  func sendResponse(
    deviceId: String, requestId: Int, status: Int,
    offset: Int, value: Data
  ) throws {
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
    // Commit values before sending response so later reads see them. Only on success (batches are atomic).
    if result == .success {
      for (address, deferred) in pending.deferredValues {
        // Don't overwrite if attribute changed while pending; newer intent is better.
        guard characteristicValues[address] == deferred.baseline else { continue }
        characteristicValues[address] = deferred.value
      }
    }
    // Not size-checked: reads can use Read Blob requests for continuations (unlike notifications).
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

  /// Rebases response from suppliedOffset to requestedOffset. CBATTRequest.offset is read-only; CoreBluetooth derives offset from request.
  func rebasedResponseValue(
    _ value: Data, isRead: Bool, suppliedOffset: Int, requestedOffset: Int, requestId: Int
  ) throws -> Data {
    guard isRead else { return value }

    // Re-check: native module is directly callable. Prevent negative offsets from trimming response undetected.
    guard suppliedOffset >= 0, requestedOffset >= 0 else {
      throw GattServerError.responseOffsetNegative(
        requestId: requestId, requested: requestedOffset, supplied: suppliedOffset
      )
    }

    guard suppliedOffset <= requestedOffset else {
      throw GattServerError.responseOffsetAfterRequest(
        requestId: requestId, requested: requestedOffset, supplied: suppliedOffset
      )
    }
    let skip = requestedOffset - suppliedOffset
    guard skip > 0 else { return value }
    guard skip < value.count else { return Data() }
    return value.subdata(in: skip..<value.count)
  }

  /// Replaces the mirrored value read responses are answered from. Address validated against published database.
  func updateCharacteristicValue(
    serviceUuid: String, characteristicUuid: String, value: Data
  ) throws {
    guard let peripheral = peripheralManager else { throw GattServerError.serverStopped }
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

  /// Stops server and clears all state. Uses removeAllServices to unpublish (not individual entries).
  func stop() {
    stopAdvertising()
    completeOpen(GattServerError.serverStopped)
    flushReadinessWaiters(GattServerError.serverStopped)
    failPendingNotifications(.serverStopped)
    // Answer requests (not silent drop) before removing services. Centrals likely still connected.
    answerAndDiscardPendingRequests(withResult: .unlikelyError) { _ in true }
    peripheralManager?.removeAllServices()

    // Cleared before the delegate is dropped: `peripheralManagerDidUpdateState` re-publishes
    // `serviceConfiguration` on every transition to powered on, and a stopped server must not come
    // back to life through either route.
    serviceConfiguration.removeAll()
    servicesAwaitingRegistration.removeAll()
    cancelPublicationTimeout()
    publication = .idle
    registrationFailure = nil
    registrationQueue.removeAll()
    outstandingRegistration = nil
    acknowledgementsOwedToDiscardedRounds = 0
    addedServices.removeAll()
    connectedCentrals.removeAll()
    centralPayloadLengths.removeAll()
    subscribedCentrals.removeAll()
    characteristicValues.removeAll()
    delegations.removeAll()
    requestCounter = 0
    onStateChange = nil

    // Clear delegate to prevent queued callbacks from repopulating cleared state.
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

  /// Resolves characteristic to its service address, or nil if ambiguous (same UUID in multiple services or service gone).
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

  /// Assembles fragment at offset into current value. Returns nil if offset past end (InvalidOffset).
  /// For unqueued writes, replaces all (Core Spec Vol 3, Part F, §3.4.5.1).
  /// For queued writes, preserves octets beyond fragment (Core Spec Vol 3, Part F, §3.4.6.1).
  func spliced(_ current: Data, offset: Int, part: Data, queued: Bool) -> Data? {
    guard offset <= current.count else { return nil }
    guard queued else { return part }
    var result = Data(current.prefix(offset))
    result.append(part)
    result.append(contentsOf: current.dropFirst(offset + part.count))
    return result
  }

  /// True if any offset is non-zero (queued write signature). ATT_WRITE_REQ has no offset field (Core Spec Vol 3, Part F, §3.4.5.1).
  /// Lone offset-0 part is ambiguous; treated as unqueued write.
  func isQueuedWriteBatch(offsets: [Int]) -> Bool {
    offsets.contains { $0 > 0 }
  }

  /// Addresses whose fragments are queued writes (grouped per attribute; decision reused for assembly and reporting).
  func queuedWriteAddresses(_ fragments: [WriteFragment]) -> Set<CharacteristicAddress> {
    var offsetsByAddress: [CharacteristicAddress: [Int]] = [:]
    for fragment in fragments {
      offsetsByAddress[fragment.address, default: []].append(fragment.offset)
    }
    return Set(offsetsByAddress.filter { isQueuedWriteBatch(offsets: $0.value) }.keys)
  }

  /// One write request's payload, minimal form for testability (CBATTRequest has no public init).
  struct WriteFragment {
    let address: CharacteristicAddress
    let offset: Int
    let value: Data
  }

  /// Assembles fragments onto current values; returns nil if any part is past end (atomic failure). Per-attribute queued-write decision.
  func assembleWriteBatch(
    _ fragments: [WriteFragment],
    current: [CharacteristicAddress: Data]
  ) -> [CharacteristicAddress: Data]? {
    let queued = queuedWriteAddresses(fragments)

    var assembled: [CharacteristicAddress: Data] = [:]
    for fragment in fragments {
      let base = assembled[fragment.address] ?? current[fragment.address] ?? Data()
      guard let merged = spliced(
        base,
        offset: fragment.offset,
        part: fragment.value,
        queued: queued.contains(fragment.address)
      ) else {
        return nil
      }
      assembled[fragment.address] = merged
    }
    return assembled
  }

  /// What a batch of write fragments resolves to, and the ATT error to answer it with when it resolves
  /// to nothing.
  enum WriteBatchOutcome: Equatable {
    case invalidOffset
    case exceedsAttributeLength
    case assembled([CharacteristicAddress: Data])
  }

  /// Validates batch assembly; checked on result (fragments alone can't exceed limit due to placement).
  func resolveWriteBatch(
    _ fragments: [WriteFragment],
    current: [CharacteristicAddress: Data]
  ) -> WriteBatchOutcome {
    guard let assembled = assembleWriteBatch(fragments, current: current) else {
      return .invalidOffset
    }
    if assembled.contains(where: { exceedsAttributeLength($0.value.count) }) {
      return .exceedsAttributeLength
    }
    return .assembled(assembled)
  }

  private func nextRequestId() -> Int {
    requestCounter += 1
    return requestCounter
  }

  /// Records central activity and first sighting as connection. Always refreshes stored instance (per-callback variation).
  @discardableResult
  private func noteActivity(from central: CBCentral) -> String {
    let deviceId = central.identifier.uuidString
    let isFirstSighting = connectedCentrals[deviceId] == nil
    connectedCentrals[deviceId] = central
    if isFirstSighting {
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

  /// Current link budget for deviceId, read live from retained CBCentral (not from cache).
  func mtu(for deviceId: String) -> DeviceMtu? {
    guard let central = connectedCentrals[deviceId] else { return nil }
    return DeviceMtu(maxNotificationPayload: central.maximumUpdateValueLength)
  }

  /// Every central that has done ATT activity (not every link holder; CBPeripheralManagerDelegate has no connection callback).
  var connectedDeviceIds: [String] {
    Array(connectedCentrals.keys)
  }

  var isServerRunning: Bool {
    databasePublished
  }

  var isAdvertising: Bool {
    peripheralManager?.isAdvertising ?? false
  }

  /// Clears all state for deviceId and reports disconnection once. abortPendingRequests=false for inferred disconnects (unsubscribe).
  /// Pass false to let timeout expire on unresolved requests; pass true only when central is known gone.
  private func markDisconnected(
    _ deviceId: String, reason: GattServerError, abortPendingRequests: Bool
  ) {
    guard connectedCentrals.removeValue(forKey: deviceId) != nil else { return }
    centralPayloadLengths.removeValue(forKey: deviceId)
    subscribedCentrals.removeValue(forKey: deviceId)
    if abortPendingRequests {
      answerAndDiscardPendingRequests(withResult: .unlikelyError) {
        $0.request.central.identifier.uuidString == deviceId
      }
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
      let error = GattServerError.bluetoothUnavailable(state: peripheral.state)
      discardPublishedDatabase(reason: error)
      // Sparing them costs them their bound: `discardPublishedDatabase` cancels the publication timeout,
      // which is the *round's* limit, and only `publishConfiguredServices` re-arms it — on a transition
      // to powered on that may never come. So a `createServer` and every parked `startAdvertising` were
      // left with no timer at all, which is precisely the outcome `publicationTimeoutMs` exists to
      // prevent. The wait is bounded here instead, keyed to the promises rather than to a round that no
      // longer exists.
      armWaiterTimeout(reason: error)
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
    cancelPublicationTimeout()
    servicesAwaitingRegistration.removeAll()
    noteDiscardedRegistration()

    // `peripheralManagerDidStartAdvertising:error:` is documented only as returning "the result of a
    // startAdvertising: call", with nothing promising one arrives when the state drops instead — so a
    // start CoreBluetooth already has is settled here rather than left pending for the process
    // lifetime. Claiming the completion is what stops a late callback settling it a second time, and
    // the expiry goes with the advertisement it belonged to rather than stopping a later one.
    cancelAdvertisingTimeout()
    pendingAdvertisingTimeoutMs = 0
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

  // Different selector than expected; only path that resolves startAdvertising promise.
  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    // Arm timeout only if start succeeded (airtime limit only applies to successful ads).
    if error == nil {
      scheduleAdvertisingTimeout(pendingAdvertisingTimeoutMs)
    }
    pendingAdvertisingTimeoutMs = 0
    claimAdvertisingCompletion()?(error)
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didAdd service: CBService, error: Error?
  ) {
    // Spend owed acks before checking round state (they arrive anytime).
    if acknowledgementsOwedToDiscardedRounds > 0 {
      acknowledgementsOwedToDiscardedRounds -= 1
      return
    }
    guard publication == .inProgress else { return }
    // Attribute to waiting service (only thing CoreBluetooth identifies). Stale acks belong to discarded rounds.
    guard outstandingRegistration == service.uuid else { return }
    outstandingRegistration = nil

    servicesAwaitingRegistration.remove(service.uuid)

    if let error = error {
      // Stop at first rejection (serialization means no siblings in flight).
      failPublicationRound(GattServerError.serviceRegistrationFailed(
        uuid: service.uuid.normalizedString,
        reason: error.localizedDescription
      ))
      return
    }

    // Mirror from configuration (has characteristics); matching instances lets address(of:) work without weak back-pointer.
    addedServices[service.uuid] = serviceConfiguration.first { $0.uuid == service.uuid }
      ?? service as? CBMutableService
      ?? CBMutableService(type: service.uuid, primary: service.isPrimary)

    guard registrationQueue.isEmpty else {
      addNextService(on: peripheral)
      return
    }
    cancelPublicationTimeout()
    publication = .published
    completeOpen(nil)
    flushReadinessWaiters(nil)
  }

  /// Unpublishes services from a failed round (deferred to next main-queue turn to avoid mid-callback calls).
  private func unpublishFailedRegistration() {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      guard self.registrationFailure != nil,
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
    let deviceId = noteActivity(from: central)
    // Report unresolvable subscriptions (wrong service) but don't record them.
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
      // Drop all same-UUID entries (conservative: unresolvable address).
      subscribedCentrals[deviceId] = subscribedCentrals[deviceId]?
        .filter { $0.key.characteristic != characteristic.uuid }
    }
    delegate?.onCharacteristicUnsubscribed(
      deviceId: deviceId,
      serviceUuid: resolved?.service.normalizedString ?? "",
      characteristicUuid: characteristic.uuid.normalizedString
    )
    failPendingNotifications(.deviceDisconnected(deviceId: deviceId)) { entry in
      guard entry.deviceId == deviceId else { return false }
      guard let resolved = resolved else {
        return entry.address.characteristic == characteristic.uuid
      }
      return entry.address == resolved
    }
    // Callback arrives for both unsubscribe and disconnect (no way to tell apart). Losing last sub is disconnect signal.
    // Inferred disconnect: leave requests alone to expire. ?? true: unresolvable subs weren't recorded.
    if subscribedCentrals[deviceId]?.isEmpty ?? true {
      markDisconnected(
        deviceId, reason: .deviceDisconnected(deviceId: deviceId), abortPendingRequests: false
      )
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveRead request: CBATTRequest
  ) {
    noteActivity(from: request.central)

    guard let address = address(of: request.characteristic) else {
      peripheral.respond(to: request, withResult: .unlikelyError)
      return
    }

    if !delegation(for: address).read, let value = characteristicValues[address] {
      let offset = request.offset

      // Offset past end → invalidOffset (0x07; Core Spec Vol 3, Part F, §3.4.1.1).
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

    var addresses: [CharacteristicAddress] = []
    for request in requests {
      guard let address = address(of: request.characteristic) else {
        peripheral.respond(to: first, withResult: .unlikelyError)
        return
      }
      addresses.append(address)
    }

    // Must call respond(to:withResult:) exactly once per callback, on first request. Batch is answered as a unit.
    let batchId = nextRequestId()
    let delegated = Set(addresses.filter { delegation(for: $0).write })

    // Assemble before applying so one invalid fragment fails the whole batch (atomicity).
    let fragments = zip(requests, addresses).map {
      WriteFragment(address: $1, offset: $0.offset, value: $0.value ?? Data())
    }
    let assembled: [CharacteristicAddress: Data]
    switch resolveWriteBatch(fragments, current: characteristicValues) {
    case .invalidOffset:
      peripheral.respond(to: first, withResult: .invalidOffset)
      return
    case .exceedsAttributeLength:
      peripheral.respond(to: first, withResult: .invalidAttributeValueLength)
      return
    case .assembled(let values):
      assembled = values
    }

    // Delegated values committed by JS later. Non-delegated values deferred until batch accepted (atomicity).
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

    // Long writes: once per attribute, offset 0, assembled value (like Android). Queued writes deduplicated.
    // Exactly one event marked responseNeeded (batch is answered as unit).
    let queued = queuedWriteAddresses(fragments)
    let responderIndex = addresses.firstIndex { delegated.contains($0) }
    var reported: Set<CharacteristicAddress> = []
    for (index, (request, address)) in zip(requests, addresses).enumerated() {
      let isQueued = queued.contains(address)
      if isQueued, !reported.insert(address).inserted {
        continue
      }
      delegate?.onCharacteristicWriteRequest(
        deviceId: request.central.identifier.uuidString,
        requestId: batchId,
        serviceUuid: address.service.normalizedString,
        characteristicUuid: address.characteristic.normalizedString,
        offset: 0,
        value: isQueued ? (assembled[address] ?? Data()) : (request.value ?? Data()),
        responseNeeded: index == responderIndex
      )
    }
  }

  /// Drains queued notifications. Selector name matters: peripheralManagerIsReady(_:) is never called.
  func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
    drainPendingNotifications()
  }
}
