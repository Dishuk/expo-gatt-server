import ExpoModulesCore
import CoreBluetooth

public class ExpoGattServerModule: Module {
  /// Read and written only on the main queue, along with everything the manager itself owns — see the
  /// note on `GattServerManager`.
  private var manager: GattServerManager?

  /// Counts `stopServer` calls, so a `createServer` still in flight can tell that the application asked
  /// to tear down after it started.
  ///
  /// `createServer` is an `AsyncFunction`, so its body runs on Expo's worker queue and only then hops to
  /// the main queue; `stopServer` is a synchronous `Function` whose body runs on the JS thread and hops
  /// directly. A `createServer(); stopServer();` pair — an effect that sets up and returns its teardown,
  /// unmounted before the promise settles — can therefore reach the main queue in the opposite order:
  /// the stop finds no manager and does nothing, then the create publishes the whole database. The
  /// application believes it has no server while the services stay in the process-wide GATT database
  /// with no handle left to remove them.
  ///
  /// This is the same hazard `advertisingStopEpoch` covers for advertising in `src/index.ts`, which
  /// `createServer` was simply never given.
  ///
  /// Guarded by a lock rather than confined to the main queue, because the whole point is to be read
  /// from the worker queue before the hop and written from the JS thread without one — the two threads
  /// whose ordering is the thing being recovered.
  private let serverStopEpochLock = NSLock()
  private var serverStopEpochValue = 0

  private var serverStopEpoch: Int {
    serverStopEpochLock.lock()
    defer { serverStopEpochLock.unlock() }
    return serverStopEpochValue
  }

  /// Called synchronously on the JS thread, so the recorded order is the order the application issued
  /// the calls in — not the order their main-queue blocks happen to run.
  private func recordServerStop() {
    serverStopEpochLock.lock()
    defer { serverStopEpochLock.unlock() }
    serverStopEpochValue += 1
  }

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
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created")
          return
        }
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

    AsyncFunction("getConnectedDevices") { (promise: Promise) in
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.resolve([])
          return
        }
        promise.resolve(mgr.connectedDeviceIds.map { ["deviceId": $0, "name": ""] })
      }
    }

    // No CBPeripheralManager method drops a central, and cancelPeripheralConnection(_:) belongs to
    // CBCentralManager and takes a CBPeripheral, so it cannot be turned around on a central. Nothing
    // is approximated here, because no documented CoreBluetooth call does this.
    AsyncFunction("disconnectDevice") { (deviceId: String, promise: Promise) in
      let error = GattServerError.configurationUnsupported(
        option: "disconnectDevice",
        reason: "CBPeripheralManager declares no method that drops a connected central, and " +
          "CBCentralManager.cancelPeripheralConnection applies to a CBPeripheral in the central " +
          "role. A peripheral can stop advertising and unpublish its services, but neither is " +
          "documented as disconnecting anybody. Only the central can end the connection."
      )
      promise.reject(error.code, error.message)
    }

    AsyncFunction("isServerRunning") { (promise: Promise) in
      DispatchQueue.main.async { promise.resolve(self.manager?.isServerRunning ?? false) }
    }

    AsyncFunction("isAdvertising") { (promise: Promise) in
      DispatchQueue.main.async { promise.resolve(self.manager?.isAdvertising ?? false) }
    }

    AsyncFunction("getBluetoothState") { (promise: Promise) in
      DispatchQueue.main.async {
        // `CBPeripheralManager.state` needs an instantiated manager, and instantiating one purely to
        // read state would trigger the Bluetooth permission prompt.
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
    }

    AsyncFunction("createServer") { (
      services: [[String: Any]],
      options: [String: Any],
      promise: Promise
    ) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
        return
      }

      // Read before any parsing, so a `stopServer` issued at any point from here on is seen below.
      let epoch = self.serverStopEpoch

      do {
        let requestTimeoutMs = try self.parseRequestTimeout(options["requestTimeoutMs"])
        var initialValues: [CharacteristicAddress: Data] = [:]
        var cbServices: [CBMutableService] = []
        // `addedServices` is keyed by service UUID and `findCharacteristic` takes the first match, so a
        // repeat would leave one attribute unreachable and the other addressed by both spellings.
        // Rejected in JavaScript too; repeated here because the native module is reachable directly.
        var serviceUuids: Set<CBUUID> = []
        for serviceConfig in services {
          let service = try self.parseServiceConfig(serviceConfig, initialValues: &initialValues)
          guard serviceUuids.insert(service.uuid).inserted else {
            throw GattArgumentError(
              message: "Duplicate service UUID \(service.uuid.normalizedString). Give each service " +
                "its own UUID, or merge their characteristics into one service."
            )
          }
          cbServices.append(service)
        }
        let delegations = try self.parseDelegations(services)
        // Only the parsing above is queue-agnostic; the manager is built and opened on the main queue.
        let parsedValues = initialValues
        DispatchQueue.main.async {
          // The application asked to stop while this create was still parsing. Publishing now would
          // leave a database nothing holds a handle to, so nothing is opened at all.
          guard self.serverStopEpoch == epoch else {
            promise.reject("ERR_NO_SERVER", "Server was stopped before it finished opening")
            return
          }
          self.manager?.stop()
          let mgr = GattServerManager(requestTimeoutMs: requestTimeoutMs)
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
          mgr.open(services: cbServices, initialValues: parsedValues) { error in
            if let error = error as? GattServerError {
              promise.reject(error.code, error.message)
            } else if let error = error {
              promise.reject("ERR_CREATE_SERVER", error.localizedDescription)
            } else {
              promise.resolve(nil)
            }
          }
        }
      } catch let error as GattServerError {
        // Keeps a specific code such as ERR_UNSUPPORTED, which the generic catch below flattens into
        // ERR_CREATE_SERVER.
        promise.reject(error.code, error.message)
      } catch {
        promise.reject("ERR_CREATE_SERVER", error.localizedDescription)
      }
    }

    AsyncFunction("startAdvertising") { (config: [String: Any], promise: Promise) in
      if let err = self.checkBluetoothAuthorization() {
        promise.reject("ERR_PERMISSION", err)
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
      let timeoutMs: Int
      do {
        timeoutMs = try parseTimeoutMs(
          config["timeoutMs"],
          field: "advertising timeout",
          bound: maxAdvertisingTimeoutMs,
          default: 0,
          boundDescription: "\(maxAdvertisingTimeoutMs) milliseconds, where 0 means no time limit"
        )
      } catch {
        promise.reject("ERR_ADVERTISE", error.localizedDescription)
        return
      }

      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created. Call createServer first.")
          return
        }
        // Waits for the database to be published rather than sampling the state: it is `.unknown` until
        // peripheralManagerDidUpdateState fires and the publication that follows takes further
        // main-queue turns, which rejected perfectly healthy calls made straight after createServer.
        // The manager does the waiting, because only it can tell a stop from a genuine release.
        mgr.startAdvertising(
          localName: localName, serviceUuids: serviceUuids, timeoutMs: timeoutMs
        ) { error in
          if let error = error as? GattServerError {
            // Keeps ERR_NO_SERVER and ERR_BLUETOOTH, which the generic branch below would flatten into
            // ERR_ADVERTISE.
            promise.reject(error.code, error.message)
          } else if let error = error {
            promise.reject("ERR_ADVERTISE", error.localizedDescription)
          } else {
            promise.resolve(nil)
          }
        }
      }
    }

    // Kept synchronous, so the JavaScript signature stays `void` and matches Android's. The teardown
    // itself is deferred because it must run on the main queue, and blocking the JavaScript thread on
    // it invites a deadlock against a main thread already waiting on JavaScript.
    //
    // This body runs on the JavaScript thread, while an `AsyncFunction` body runs on Expo's own worker
    // queue — so this is **not** ordered against an un-awaited `startAdvertising`, and a stop issued
    // second can reach the manager first. The shared layer carries the application's call order across
    // that gap; see `advertisingStopEpoch` in `src/index.ts`.
    Function("stopAdvertising") {
      DispatchQueue.main.async { self.manager?.stopAdvertising() }
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
      let data: Data
      do {
        try self.validateUuid(serviceUuid, field: "service")
        try self.validateUuid(characteristicUuid, field: "characteristic")
        data = try parseBytes(value, field: "notification")
      } catch {
        promise.reject("ERR_NOTIFY", error.localizedDescription)
        return
      }
      // `requireSubscription` has no iOS counterpart: CoreBluetooth only ever transmits to subscribed
      // centrals, so an unsubscribed send cannot be forced through.
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created")
          return
        }
        do {
          try mgr.sendNotification(
            deviceId: deviceId,
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: data,
            confirm: confirm
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
      let data: Data
      do {
        data = try parseBytes(value, field: "response")
      } catch {
        promise.reject("ERR_RESPONSE", error.localizedDescription)
        return
      }
      DispatchQueue.main.async {
        // `REQUEST_NOT_FOUND` rather than `ERR_NO_SERVER`, because that is what the situation is:
        // answering a request the module no longer holds is a missing request. With no manager there are
        // no pending requests at all — `stop` answered and discarded them — so the lookup could only have
        // failed anyway. Android's binding reports the same code here, for the same reason;
        // `docs/api.md` invites branching on `code` without branching on `Platform.OS`, and this is one
        // of the places that has to hold for that to be true.
        guard let mgr = self.manager else {
          promise.reject(
            "REQUEST_NOT_FOUND", "Request \(requestId) not found or already responded"
          )
          return
        }
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

    AsyncFunction("updateCharacteristicValue") { (
      serviceUuid: String,
      characteristicUuid: String,
      value: [Int],
      promise: Promise
    ) in
      let data: Data
      do {
        try self.validateUuid(serviceUuid, field: "service")
        try self.validateUuid(characteristicUuid, field: "characteristic")
        data = try parseBytes(value, field: "characteristic")
      } catch {
        promise.reject("ERR_UPDATE_VALUE", error.localizedDescription)
        return
      }
      DispatchQueue.main.async {
        guard let mgr = self.manager else {
          promise.reject("ERR_NO_SERVER", "Server not created")
          return
        }
        do {
          try mgr.updateCharacteristicValue(
            serviceUuid: serviceUuid,
            characteristicUuid: characteristicUuid,
            value: data
          )
          promise.resolve(nil)
        } catch let error as GattServerError {
          promise.reject(error.code, error.message)
        } catch {
          promise.reject("ERR_UPDATE_VALUE", error.localizedDescription)
        }
      }
    }

    // Synchronous and deferred for the same reasons as `stopAdvertising`.
    Function("stopServer") {
      // Recorded here, on the JS thread, rather than inside the block: a `createServer` still parsing
      // on the worker queue reaches the main queue after this block does, and would otherwise publish
      // a database this call was meant to prevent.
      self.recordServerStop()
      DispatchQueue.main.async {
        self.manager?.stop()
        self.manager = nil
      }
    }

    OnDestroy {
      // The block captures the module strongly, so deferring the teardown cannot skip it.
      self.recordServerStop()
      DispatchQueue.main.async {
        self.manager?.stop()
        self.manager = nil
      }
    }
  }

  /// `CBPeripheralManager.startAdvertising` silently ignores every key but the local name and service
  /// UUIDs. Only the options that change what a scanner *observes* are rejected here, since dropping
  /// those yields a peripheral that appears to advertise yet can never be found by a central filtering
  /// on them. `mode`, `txPowerLevel` and `includeTxPowerLevel` are merely radio hints, warned about in
  /// JavaScript instead — rejecting them would force every cross-platform caller to branch on platform
  /// just to tune Android's battery use.
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

  /// `CBUUID(string:)` raises an uncatchable Objective-C exception for anything other than a 16-bit,
  /// 32-bit or hyphenated 128-bit string, so every string is checked before it reaches CoreBluetooth.
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

  /// Anything outside 0...255 would be silently corrupted by a clamping or truncating conversion.
  private func parseRequestTimeout(_ value: Any?) throws -> Int {
    try parseTimeoutMs(
      value,
      field: "request timeout",
      bound: attTransactionTimeoutMs - 1,
      default: defaultRequestTimeoutMs,
      boundDescription: "\(attTransactionTimeoutMs - 1) milliseconds — below the ATT transaction " +
        "timeout of \(attTransactionTimeoutMs) ms, past which the central has already given up — " +
        "where 0 disables the timeout"
    )
  }

  /// Absent or empty `delegate` configuration produces no entry, so the default stays fully automatic.
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
    initialValues: inout [CharacteristicAddress: Data]
  ) throws -> CBMutableService {
    let uuid = try parseUuid(map["uuid"], field: "service")
    let service = CBMutableService(type: uuid, primary: try parseIsPrimary(map["type"]))

    var characteristics: [CBMutableCharacteristic] = []
    var characteristicUuids: Set<CBUUID> = []
    if let charList = map["characteristics"] as? [[String: Any]] {
      for charMap in charList {
        let characteristic = try parseCharacteristicConfig(
          charMap, service: uuid, initialValues: &initialValues
        )
        guard characteristicUuids.insert(characteristic.uuid).inserted else {
          throw GattArgumentError(
            message: "Duplicate characteristic UUID \(characteristic.uuid.normalizedString) in " +
              "service \(uuid.normalizedString). The same characteristic UUID in a different " +
              "service is fine."
          )
        }
        characteristics.append(characteristic)
      }
    }
    service.characteristics = characteristics
    return service
  }

  private func parseIsPrimary(_ value: Any?) throws -> Bool {
    switch value as? String {
    case nil, "primary": return true
    case "secondary": return false
    default:
      throw GattArgumentError(
        message: "Invalid service type \"\(value ?? "nil")\". Expected \"primary\" or \"secondary\"."
      )
    }
  }

  private func parseCharacteristicConfig(
    _ map: [String: Any],
    service: CBUUID,
    initialValues: inout [CharacteristicAddress: Data]
  ) throws -> CBMutableCharacteristic {
    let uuid = try parseUuid(map["uuid"], field: "characteristic")
    let properties = try parseProperties(map["properties"] as? [String])
    let permissions = try parsePermissions(map["permissions"] as? [String])

    // A CBMutableCharacteristic created with a non-nil value is forced read-only by CoreBluetooth, and
    // adding it with any other properties or permissions raises "Characteristics with cached values
    // must be read-only" — so the characteristic is always published with a dynamic (nil) value and the
    // initial value served from this cache, letting any configuration Android accepts work here too.
    //
    // `[]` is a configured value, not an absent one: it declares a present but zero-length attribute,
    // which Android caches and auto-answers reads from.
    if let bytes = try parseByteArray(map["value"], field: "characteristic") {
      initialValues[CharacteristicAddress(service: service, characteristic: uuid)] = bytes
    }

    let characteristic = CBMutableCharacteristic(
      type: uuid,
      properties: securedSubscription(properties, permissions),
      value: nil,
      permissions: permissions
    )

    if let descriptorList = map["descriptors"] as? [[String: Any]], !descriptorList.isEmpty {
      characteristic.descriptors = try descriptorList.map { try parseDescriptorConfig($0) }
    }

    return characteristic
  }

  /// Raises the security of the subscription itself to match the security declared on the value.
  ///
  /// A `CBAttributePermissions` member guards only a read or a write of the value; nothing in it reaches
  /// the Client Characteristic Configuration descriptor, which CoreBluetooth owns and never exposes. The
  /// only gate on subscribing is the separate property pair Apple documents as "only trusted devices can
  /// enable notifications/indications of the characteristic value", so without this an unpaired central
  /// could subscribe to a characteristic whose direct read it is refused and receive every later value in
  /// cleartext — the same hole Android leaves in that descriptor's own write permission.
  ///
  /// Derived from the permissions rather than exposed as two more `CharacteristicProperty` names so that
  /// one configuration means the same thing on both platforms and no consumer has to branch on the OS.
  /// The plain `.notify`/`.indicate` member is kept alongside: it is what sets the corresponding bit of
  /// the published characteristic declaration (Core Spec Vol 3, Part G, Table 3.5), which a central needs
  /// to see before it will subscribe at all.
  private func securedSubscription(
    _ properties: CBCharacteristicProperties,
    _ permissions: CBAttributePermissions
  ) -> CBCharacteristicProperties {
    guard permissions.contains(.readEncryptionRequired)
      || permissions.contains(.writeEncryptionRequired) else {
      return properties
    }

    var secured = properties
    if properties.contains(.notify) {
      secured.insert(.notifyEncryptionRequired)
    }
    if properties.contains(.indicate) {
      secured.insert(.indicateEncryptionRequired)
    }
    return secured
  }

  /// `CBMutableDescriptor` is documented as supporting "only the `Characteristic User Description` and
  /// `Characteristic Presentation Format` descriptors". Anything else is refused rather than handed to
  /// CoreBluetooth, which would reject the whole service at publication time.
  private func parseDescriptorConfig(_ map: [String: Any]) throws -> CBMutableDescriptor {
    let uuid = try parseUuid(map["uuid"], field: "descriptor")
    // An absent value publishes a zero-length descriptor, which is what Android's
    // `toByteArray(… ?: emptyList())` does.
    let bytes = try parseByteArray(map["value"], field: "descriptor") ?? Data()

    switch uuid {
    case CBUUID(string: CBUUIDCharacteristicUserDescriptionString):
      // Apple models this descriptor's value as an NSString, so anything but UTF-8 has no
      // representation to publish.
      guard let text = String(data: bytes, encoding: .utf8) else {
        throw GattServerError.configurationUnsupported(
          option: "descriptor \(uuid.uuidString)",
          reason: "the Characteristic User Description value is an NSString on iOS, and these " +
            "bytes are not valid UTF-8."
        )
      }
      return CBMutableDescriptor(type: uuid, value: text)
    case CBUUID(string: CBUUIDCharacteristicFormatString):
      return CBMutableDescriptor(type: uuid, value: bytes)
    default:
      throw GattServerError.configurationUnsupported(
        option: "descriptor \(uuid.uuidString)",
        reason: "CBMutableDescriptor supports only the Characteristic User Description " +
          "(\(CBUUIDCharacteristicUserDescriptionString)) and Characteristic Presentation Format " +
          "(\(CBUUIDCharacteristicFormatString)) descriptors. Android publishes any descriptor, so " +
          "this one has to be declared for Android only."
      )
    }
  }

  /// Apple annotates `CBCharacteristicPropertyBroadcast` and
  /// `CBCharacteristicPropertyExtendedProperties` as "Not allowed for local characteristics", so both are
  /// refused here instead of being set and rejected at publication time.
  private func parseProperties(_ list: [String]?) throws -> CBCharacteristicProperties {
    var props: CBCharacteristicProperties = []
    for str in list ?? [] {
      switch str {
      case "read": props.insert(.read)
      case "write": props.insert(.write)
      case "writeNoResponse": props.insert(.writeWithoutResponse)
      case "notify": props.insert(.notify)
      case "indicate": props.insert(.indicate)
      case "signedWrite": props.insert(.authenticatedSignedWrites)
      case "broadcast", "extendedProperties":
        throw GattServerError.configurationUnsupported(
          option: "characteristic property \"\(str)\"",
          reason: "CoreBluetooth documents the matching CBCharacteristicProperties member as not " +
            "allowed for local characteristics. Declare it for Android only."
        )
      default:
        throw GattArgumentError(message: "Invalid characteristic property \"\(str)\".")
      }
    }
    return props
  }

  /// `CBAttributePermissions` has exactly four members, so Android's MITM and signed variants have
  /// nothing to map onto. Every near equivalent is *weaker* than what was asked for — an MITM variant
  /// requires authenticated pairing rather than any encrypted link, a signed variant a signature over an
  /// unencrypted one — so they are refused rather than approximated into a less protected attribute.
  private func parsePermissions(_ list: [String]?) throws -> CBAttributePermissions {
    var perms: CBAttributePermissions = []
    for str in list ?? [] {
      switch str {
      case "readable": perms.insert(.readable)
      case "writeable": perms.insert(.writeable)
      case "readEncrypted": perms.insert(.readEncryptionRequired)
      case "writeEncrypted": perms.insert(.writeEncryptionRequired)
      case "readEncryptedMitm", "writeEncryptedMitm", "writeSigned", "writeSignedMitm":
        throw GattServerError.configurationUnsupported(
          option: "permission \"\(str)\"",
          reason: "CBAttributePermissions offers only readable, writeable, " +
            "readEncryptionRequired and writeEncryptionRequired, and approximating this one would " +
            "publish a less protected attribute than was asked for. Use \"readEncrypted\" or " +
            "\"writeEncrypted\" for a portable encrypted attribute."
        )
      default:
        throw GattArgumentError(message: "Invalid permission \"\(str)\".")
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
