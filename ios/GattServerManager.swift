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
  func onMtuChanged(deviceId: String, mtu: DeviceMtu)
  /// The published database went away for a reason no promise is waiting to report. See
  /// `reportPublicationFailure`.
  func onServerPublicationFailed(code: String, message: String)
  func onCharacteristicSubscribed(deviceId: String, serviceUuid: String, characteristicUuid: String)
  func onCharacteristicUnsubscribed(deviceId: String, serviceUuid: String, characteristicUuid: String)
}

/// The peripheral's lifecycle: publishing the database and routing every ATT callback either to an
/// automatic answer or to JavaScript.
///
/// The concerns with state of their own live beside it — `AdvertisingCoordinator` owns the radio,
/// `NotificationQueue` the sends CoreBluetooth refused, and `PendingRequestStore` the requests handed to
/// JavaScript. The write arithmetic is in `WriteBatch.swift`, with no state at all.
///
/// All state must be reached from the main queue; no concurrent access. CBPeripheralManager is created with queue: .main.
class GattServerManager: NSObject {
  weak var delegate: GattServerManagerDelegate?

  private var advertising: AdvertisingCoordinator!
  private var pendingRequests: PendingRequestStore!
  private var notifications: NotificationQueue!

  init(requestTimeoutMs: Int = defaultRequestTimeoutMs) {
    super.init()
    advertising = AdvertisingCoordinator(
      peripheral: { [weak self] in self?.peripheralManager },
      isDatabasePublished: { [weak self] in self?.databasePublished ?? false }
    )
    pendingRequests = PendingRequestStore(timeoutMs: requestTimeoutMs) { [weak self] request in
      // Answers with unlikelyError so the central does not stall waiting for its own timeout.
      self?.peripheralManager?.respond(to: request, withResult: .unlikelyError)
    }
    notifications = NotificationQueue(
      isActive: { [weak self] in self?.peripheralManager != nil },
      deliver: { [weak self] entry in
        guard let self = self else {
          entry.completion(GattServerError.serverStopped)
          return true
        }
        return self.deliver(entry)
      }
    )
  }

  var onStateChange: ((CBManagerState) -> Void)?

  private var peripheralManager: CBPeripheralManager?
  private var serviceConfiguration: [CBMutableService] = []
  private var servicesAwaitingRegistration: Set<CBUUID> = []
  private var openCompletion: ((Error?) -> Void)?
  private var readinessWaiters: [(Error?) -> Void] = []

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
  private var delegations: [CharacteristicAddress: CharacteristicDelegation] = [:]

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
    let generation = advertising.generation
    whenDatabasePublished { [weak self] error in
      guard let self = self else {
        completion(GattServerError.serverStopped)
        return
      }
      if let error = error {
        completion(error)
        return
      }
      guard generation == self.advertising.generation else {
        completion(advertisingError("Advertising stopped"))
        return
      }
      self.advertising.begin(
        localName: localName, serviceUuids: serviceUuids,
        timeoutMs: timeoutMs, completion: completion
      )
    }
  }

  func stopAdvertising() {
    advertising.stop()
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

    try notifications.submit(
      QueuedNotification(
        id: notifications.makeId(),
        deviceId: deviceId,
        address: address,
        characteristic: characteristic,
        central: central,
        value: value,
        completion: completion
      )
    )
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

  /// Answers matching requests with result, then discards them. Responses sent after bookkeeping to avoid mid-teardown callbacks.
  private func answerAndDiscardPendingRequests(
    withResult result: CBATTError.Code, where predicate: (PendingRequest) -> Bool
  ) {
    for request in pendingRequests.claim(where: predicate) {
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

    let payload = try rebasedResponseValue(
      value, isRead: pending.isRead, suppliedOffset: offset,
      requestedOffset: pending.request.offset, requestId: requestId
    )
    pendingRequests.discard(requestId)

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
    advertising.stop()
    completeOpen(GattServerError.serverStopped)
    flushReadinessWaiters(GattServerError.serverStopped)
    notifications.failAll(.serverStopped)
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
    pendingRequests.resetIds()
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
    advertising.isAdvertising
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
    notifications.failAll(reason) { $0.deviceId == deviceId }
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
      // spared, since a further state update is coming and the re-publish that follows powering on can
      // still satisfy them.
      let error = GattServerError.bluetoothUnavailable(state: peripheral.state)
      discardPublishedDatabase(reason: error)
      // Re-bounds them: `discardPublishedDatabase` cancels the publication timeout, and only
      // `publishConfiguredServices` re-arms it — on a transition to powered on that may never come. This
      // bound is keyed to the promises rather than to a round that no longer exists.
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

    advertising.discard(reason: reason)

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
    pendingRequests.discard { _ in true }
    notifications.failAll(reason)
    for deviceId in disconnected {
      delegate?.onDeviceDisconnected(deviceId: deviceId)
    }
  }

  // Different selector than expected; only path that resolves startAdvertising promise.
  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    advertising.didStart(error: error)
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
    notifications.failAll(.deviceDisconnected(deviceId: deviceId)) { entry in
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

    let reqId = pendingRequests.nextId()
    pendingRequests.register(reqId, request: request, isRead: true)

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
    let batchId = pendingRequests.nextId()
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
      pendingRequests.register(batchId, request: first, isRead: false, deferredValues: deferred)
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
    notifications.drain()
  }
}
