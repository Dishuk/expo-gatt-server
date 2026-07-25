# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

> ### Read this before upgrading
>
> **This release reworks the public API, and a large number of the changes are breaking.** Every one is
> marked **Breaking** below. The most likely to break an existing integration:
>
> - `GATT_FAILURE` and the `MTU_SMALL` error code are **removed**
> - event payloads now report every UUID as the lowercase 128-bit form, so a `===` against a short or
>   uppercase spelling that used to match on one platform no longer does
> - `sendNotification` rejects instead of resolving when nothing is subscribed, when the characteristic
>   does not declare the property `confirm` asks for, or when the payload exceeds the MTU
> - `sendNotification` no longer changes the value a read returns — call `updateCharacteristicValue` too
>   if it should
> - `updateCharacteristicValue` returns a promise and rejects on an unknown characteristic
> - `CharacteristicWriteRequestEvent.responseNeeded` now means "the module is waiting for you", not
>   "the central asked for an acknowledgement"
> - a mistyped property or permission name throws instead of being ignored
> - Android no longer renames the device's Bluetooth adapter, no longer declares
>   `ACCESS_FINE_LOCATION` at all, and no longer declares `android.hardware.bluetooth_le` as required

### Added

- An Expo config plugin, so the package configures its own build-time requirements instead of leaving
  every consumer to hand-edit `app.json`. Adding `"plugins": ["expo-gatt-server"]` writes
  `NSBluetoothAlwaysUsageDescription`, without which iOS terminates the app the moment it touches
  CoreBluetooth. Three optional properties: `bluetoothAlwaysPermission` for that description,
  `bluetoothPeripheralBackgroundMode` to add `bluetooth-peripheral` to `UIBackgroundModes`, and
  `requireBluetoothLeHardware` to declare `android.hardware.bluetooth_le` required — the manifest
  merger ORs `android:required`, so this is how an app that genuinely needs BLE overrides the
  `required="false"` the module declares to keep its consumers on Google Play. Every property defaults
  to the previous behaviour, and an omitted `bluetoothAlwaysPermission` leaves an existing
  `ios.infoPlist` value alone
- `isSupported`, and lazy failure everywhere else. The native module is now resolved with
  `requireOptionalNativeModule`, so **importing the package no longer throws** on web or in Expo Go —
  `requireNativeModule` threw at import time, which took down any bundle that reached the import even
  if it only used BLE conditionally. Calls that need the radio now reject with a message naming the
  platform and the reason; `getBluetoothState` resolves to `unsupported`, `getConnectedDevices` to
  `[]`, `isServerRunning` / `isAdvertising` to `false`, `stopAdvertising` / `stopServer` do nothing,
  and every `add*Listener` returns a subscription whose `remove()` does nothing
- `getConnectedDevices`, with the type `ConnectedDevice`, for enumerating connected centrals. Reports
  the module's own tracking rather than
  `BluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)`, which would include centrals
  connected to other apps' GATT servers. On iOS "connected" is derived from ATT activity, because
  CoreBluetooth declares no connection-level callback
- `disconnectDevice`, backed by `BluetoothGattServer.cancelConnection`. **Android only** — the whole
  of `CBPeripheralManager` contains no method that drops a central, and
  `CBCentralManager.cancelPeripheralConnection` applies to a `CBPeripheral` in the central role, so
  iOS rejects with `ERR_UNSUPPORTED` rather than approximating it
- `isServerRunning` and `isAdvertising`. `isServerRunning` reports whether a GATT database is
  currently published, so it goes false while Bluetooth is off and true again once the module
  re-publishes. `isAdvertising` reads `CBPeripheralManager.isAdvertising` on iOS and is tracked
  natively on Android, which offers no such query
- `getBluetoothState`, the `onBluetoothStateChanged` event with `addBluetoothStateChangedListener`, and
  the types `BluetoothState` and `BluetoothStateChangedEvent`. The adapter state is normalised to one
  union across `CBManagerState` and `BluetoothAdapter`, so a consumer never branches on platform.
  `getBluetoothState` is safe before `createServer` and resolves to `unsupported` where the native
  module is absent; the event is delivered only while a server exists, because state monitoring is tied
  to the server lifecycle on both platforms. On iOS the query cannot report more than `unknown` or
  `unauthorized` before a server exists, since `CBPeripheralManager.state` needs an instantiated manager
  and instantiating one would trigger the permission prompt
- `getMtu`, the `onMtuChanged` event with `addMtuChangedListener`, and the types `DeviceMtu` and
  `MtuChangedEvent`. The link budget was previously invisible from JavaScript, so a payload could only
  be sized by trial and rejection. `mtu` is the ATT MTU in octets and `maxNotificationPayload` is
  `mtu - 3`, the figure to size a `sendNotification` against. Android reports the ATT MTU exactly and
  the module derives the payload; iOS exposes only `CBCentral.maximumUpdateValueLength`, so there the
  payload is exact and the MTU is derived. iOS has no MTU callback at all, so the value is sampled at
  the central's next ATT activity and the first event arrives with `onDeviceConnected`
- `GattCharacteristicConfig.delegate`, with the type `CharacteristicDelegateConfig`. `delegate.read`
  keeps every read coming to JavaScript however current the cached value is, which is what a computed or
  dynamic read needs — previously a characteristic stopped emitting read events as soon as it had a
  value. `delegate.write` withholds the automatic acknowledgement so `sendResponse` can accept the write
  with `GATT_SUCCESS` or reject it with an `ATT_ERROR_*` code, which was not previously possible at all.
  Both default to off, so the module keeps answering requests itself
- The `ATT_ERROR_*` constants, one per ATT error code the specification defines from `0x01` to `0x11`
  (Core Specification, Vol 3, Part F, Table 3.4). Only that range is exposed, because `CBATTError.Code`
  stops there and anything beyond it would be downgraded to Unlikely Error on iOS
- The `EventSubscription` type, re-exported from `expo-modules-core`, and every `add*Listener` annotated
  with it, so a stored subscription can be typed without depending on `expo-modules-core` directly
- Encrypted and authenticated `CharacteristicPermission` variants — `readEncrypted`,
  `readEncryptedMitm`, `writeEncrypted`, `writeEncryptedMitm`, `writeSigned`, `writeSignedMitm`.
  Previously only `readable` and `writeable` existed, so anything built on this package was
  necessarily an unauthenticated peripheral. `CBAttributePermissions` has only four members, so the
  MITM and signed variants reject on iOS with `ERR_UNSUPPORTED` rather than being approximated into a
  weaker guarantee than was asked for
- `CharacteristicProperty` values `broadcast`, `signedWrite` and `extendedProperties`. Apple
  documents `broadcast` and `extendedProperties` as not allowed for local characteristics, so both
  reject on iOS with `ERR_UNSUPPORTED`
- `GattServiceConfig.type`, with the type `GattServiceType`, for publishing a secondary service
- `GattCharacteristicConfig.descriptors`, with the type `GattDescriptorConfig`, for declaring
  descriptors beyond the automatic Client Characteristic Configuration descriptor. iOS accepts only
  0x2901 and 0x2904, the two `CBMutableDescriptor` supports; the CCCD is rejected on both platforms
  because the module publishes and answers it itself
- The constant `CLIENT_CHARACTERISTIC_CONFIGURATION_UUID`
- `createServer` option `requestTimeoutMs`, with the type `CreateServerOptions` and the constants
  `ATT_TRANSACTION_TIMEOUT_MS` and `DEFAULT_REQUEST_TIMEOUT_MS`. A delegated request that JavaScript
  never answers is now completed with `ATT_ERROR_UNLIKELY_ERROR` after 10 s by default, instead of
  being retained forever while the central stalls until its own 30 s ATT transaction timeout drops
  the connection
- `onCharacteristicSubscribed` / `onCharacteristicUnsubscribed` events, with
  `addCharacteristicSubscribedListener` and `addCharacteristicUnsubscribedListener`
- Per-device, per-characteristic Client Characteristic Configuration tracking on Android, as the
  Bluetooth specification requires
- `sendNotification` option `requireSubscription`, with the type `SendNotificationOptions`, for sending
  without a subscription on Android. It changes nothing on iOS, where
  `updateValue(_:for:onSubscribedCentrals:)` ignores unsubscribed centrals, so there is no send to force
- Error codes `ERR_NO_SUBSCRIBER`, `ERR_NOTIFY_QUEUE_FULL`, `ERR_DEVICE_DISCONNECTED`,
  `ERR_CHARACTERISTIC_NOT_FOUND`, `ERR_CONFIRM_UNSUPPORTED`, `REQUEST_DEVICE_MISMATCH`,
  `ERR_RESPONSE_OFFSET`, `ERR_NO_CONTEXT` and `ERR_DISCONNECT`
- `AdvertiseConfig.android` with `includeDeviceName` and `setAdapterName`
- `AdvertiseConfig` options `mode`, `txPowerLevel`, `timeoutMs`, `manufacturerData` and
  `serviceData`, with the types `AdvertisingMode`, `AdvertisingTxPower`, `ManufacturerDataEntry` and
  `ServiceDataEntry`. `timeoutMs` is bounded at 180000 on both platforms, the limit
  `AdvertiseSettings.Builder.setTimeout` enforces, and is **emulated** on iOS by a module timer that
  calls `stopAdvertising` — the same observable outcome, but only while the process is alive
- Error code `ERR_UNSUPPORTED`, for configuration and advertising options iOS cannot express
- Short-form UUIDs: every UUID the API accepts may now be written as 4 hex digits (16-bit) or 8 hex
  digits (32-bit) as well as the hyphenated 128-bit form, on both platforms
- A runnable `example/` harness app, unit tests for the TypeScript layer in `src/__tests__/`, and a
  `LICENSE` file

### Changed

- 16-bit and 32-bit UUIDs are expanded onto the Bluetooth Base UUID in the shared TypeScript layer, so
  both platforms accept identical input. `CBUUID` took all three forms while Java's `UUID.fromString`
  requires the 8-4-4-4-12 form, so `'180D'` used to be accepted on iOS and throw on Android. Applies to
  service, characteristic and descriptor `uuid`, `serviceUuids`, `serviceData`, and the UUID arguments
  of `sendNotification` and `updateCharacteristicValue`. The expansion is
  `128_bit_value = short_value * 2^96 + Bluetooth_Base_UUID` (Core Specification, Vol 3, Part B,
  Section 2.5.1). Advertising payloads are unaffected in size, because Android re-encodes an advertised
  UUID in its shortest form
- **Breaking:** event payloads report `serviceUuid` and `characteristicUuid` as the lowercase 128-bit
  form on both platforms, rather than echoing the spelling the configuration used. iOS previously
  reported `CBUUID.uuidString`, which uppercases the 128-bit form and echoes short UUIDs back short, so
  the same characteristic arrived spelled differently on each platform and a `===` against the
  configuration failed on iOS. `deviceId` is unchanged — it is an opaque handle, not a Bluetooth UUID
- **Breaking:** `value: []` on a characteristic now means a present, zero-length value on iOS too, so
  reads are auto-answered with an empty value instead of being delegated to JavaScript. Android already
  behaved this way; omit `value` for the delegating behaviour
- **Breaking:** an unrecognised characteristic property or permission name now throws instead of
  being silently ignored. A typo used to publish an attribute with one fewer permission than the
  configuration asked for, with no indication
- **Breaking:** Android no longer renames the device's Bluetooth adapter when `localName` is set.
  Android has no per-advertisement local name, so the device's own name is advertised instead. Set
  `android.setAdapterName` to opt back in to the rename, which is now undone when advertising stops
- **Breaking:** Android advertises in `lowPower` mode by default, matching the platform's own
  default, instead of the hardcoded `lowLatency`. Pass `mode: 'lowLatency'` for the old behaviour
- **Breaking:** `android.hardware.bluetooth_le` is declared `required="false"`, so the module no
  longer filters consuming apps off Google Play on non-BLE devices. Declare it `required="true"` in
  your own manifest, or pass the config plugin's `requireBluetoothLeHardware`, to restore the old
  behaviour
- **Breaking:** iOS rejects `manufacturerData`, `serviceData` and `connectable: false` with
  `ERR_UNSUPPORTED` instead of ignoring them; `mode`, `txPowerLevel` and `includeTxPowerLevel` are
  still ignored there but now log a warning
- **Breaking:** `CharacteristicWriteRequestEvent.responseNeeded` reports whether the **module** is
  waiting for JavaScript to answer the request, not whether the central asked for an acknowledgement. It
  was previously hardcoded per platform and meant neither. It is `true` only for a characteristic
  configured with `delegate.write` whose write carries a response; every other write is acknowledged
  before the event is emitted. `CharacteristicReadRequestEvent.requestId` and the write event's
  `requestId` are likewise real request identifiers now, rather than fixed values
- **Breaking:** a byte outside `0`–`255` anywhere in a `number[]` argument throws instead of being
  silently truncated or clamped into a different value. Affects characteristic and descriptor `value`,
  `sendNotification`, `sendResponse`, `manufacturerData` and `serviceData`
- **Breaking:** an out-of-range `sendResponse` `status` or `offset` throws. An ATT error code is a single
  octet, and Android narrowed a wider status to its low byte on the way into the Bluetooth stack, so it
  went out on the wire as an unrelated error rather than being reported
- **Breaking:** `sendResponse` honours `offset` as "where `value` begins within the attribute" and
  rebases the response onto the offset the request actually asked for, identically on both platforms. So
  passing `offset: 0` with the whole value answers a Read Blob continuation correctly, and passing the
  request's own offset with an already-sliced value works too. `offset` past the requested offset — which
  would leave the requested bytes missing — rejects with `ERR_RESPONSE_OFFSET`. iOS previously ignored
  the offset entirely
- **Breaking:** `createServer` resolves only once every service is confirmed published, rather than as
  soon as the request was handed to the platform. A resolved promise now means the database really is
  there to advertise
- **Breaking:** `sendNotification` rejects with `PAYLOAD_EXCEEDS_MTU` before transmitting anything when
  the payload exceeds what one notification can carry, and no longer sends a truncated payload. It is
  re-checked if the MTU shrinks while the send is queued. Conversely, `sendResponse` is no longer
  size-checked at all: a read response longer than one PDU is normal ATT, which the central continues
  with a Read Blob request
- **Breaking:** `onDeviceConnected` on iOS fires on the central's first ATT activity of any kind — a
  subscribe, read or write — rather than only on its first subscription, and connections are tracked
  separately from subscriptions. A read/write-only central is therefore reported, where previously it was
  invisible. Its disconnection generally still is not: CoreBluetooth reports none, so the module infers
  one from the loss of the last subscription or from Bluetooth leaving `poweredOn`
- `sendNotification` resolves when the platform reports the notification as delivered, and queues
  sends behind one still in flight instead of letting the platform drop them. A device may have 64 sends
  waiting before `ERR_NOTIFY_QUEUE_FULL`
- `sendNotification` rejects with `ERR_NO_SUBSCRIBER` instead of resolving when nothing is
  subscribed to the characteristic
- **Breaking:** `sendNotification` no longer changes the value a read of the characteristic returns.
  Pushing a value to subscribers and setting the value an ATT Read is answered from are separate
  operations, and `updateCharacteristicValue` is the one documented to do the latter — so a value that
  should be both pushed and readable now needs both calls, in that order. Previously the two were
  entangled, and inconsistently: iOS stored the payload on **every** call, before it had even checked
  that anyone was subscribed, so a failed send still changed what a later read returned; Android stored
  it only below API 33, as an incidental side effect of the deprecated
  `notifyCharacteristicChanged(device, characteristic, confirm)` overload reading its payload from
  `characteristic.getValue()`, while the API 33+ overload takes the payload directly and left the stored
  value alone. The same call therefore changed the readable value depending on the platform *and* on the
  Android version. Only the explicit call does now, on both platforms and every API level. This also
  un-breaks a characteristic configured **without** a `value`: on iOS a single `sendNotification` used to
  give it one, which silently stopped every subsequent read from reaching
  `onCharacteristicReadRequest`. No option is offered to restore the old coupling, because
  `updateCharacteristicValue` already expresses it in one line and an implicit second write would only
  reintroduce the question of whether a failed send should apply it
- **Breaking:** `updateCharacteristicValue` returns `Promise<void>` instead of `void`, and rejects
  with `ERR_CHARACTERISTIC_NOT_FOUND` or `ERR_NO_SERVER` instead of silently doing nothing when the
  UUIDs name no published characteristic. It also now runs on the main queue on iOS, where it
  previously mutated the peripheral manager's state from the JavaScript thread
- **Breaking:** `sendNotification` rejects with `ERR_CONFIRM_UNSUPPORTED` when `confirm` asks for a
  transmission the characteristic does not declare the property for, instead of sending it anyway on
  Android and silently sending the other kind on iOS
- **Breaking:** on Android `requireSubscription` now checks the specific Client Characteristic
  Configuration bit `confirm` selects, so a client that enabled only indications is no longer sent a
  notification. iOS still checks only that the central is subscribed, because CoreBluetooth does not
  report which bit it set
- **Breaking:** on Android the Client Characteristic Configuration descriptor the module publishes for
  every `notify` or `indicate` characteristic now carries permissions derived from that characteristic
  instead of a fixed `PERMISSION_READ | PERMISSION_WRITE`. An encrypted read or write permission raises
  the descriptor's write to `PERMISSION_WRITE_ENCRYPTED`, an MITM one to
  `PERMISSION_WRITE_ENCRYPTED_MITM`, and an encrypted read is mirrored onto the descriptor's read.
  Android enforces permissions per attribute handle and checks nothing at all before sending a
  notification, so a plain descriptor let an unpaired central subscribe to a characteristic whose direct
  read it was correctly refused and then receive every value in cleartext. A characteristic declaring
  only `readable` and `writeable` is unaffected; one declaring an encrypted permission now requires that
  link security before a central can subscribe
- **Breaking:** on iOS a characteristic declaring `notify` or `indicate` alongside `readEncrypted` or
  `writeEncrypted` is published with `.notifyEncryptionRequired` / `.indicateEncryptionRequired` added
  to the property it declared, so only a trusted device can enable the subscription. CoreBluetooth owns
  the Client Characteristic Configuration descriptor and `CBAttributePermissions` guards only a read or
  a write of the value, leaving that property pair as the one gate on subscribing — without it iOS had
  the same hole Android did. Derived from the permissions rather than exposed as new property names, so
  the configuration means the same thing on both platforms and nothing has to branch on `Platform.OS`
- `onNotificationSent` reports the characteristic the notification actually carried
- iOS resends only the payload the transmit queue refused, instead of pushing cached values to every
  subscribed central

### Fixed

- The same failure reported a different `code` on each platform, so branching on one meant branching on
  `Platform.OS` too. Every case now reports the more specific of the two codes on both platforms:
  `sendNotification` with an unknown `deviceId` rejects with `ERR_DEVICE_DISCONNECTED` and with an
  unknown `serviceUuid` or `characteristicUuid` with `ERR_CHARACTERISTIC_NOT_FOUND` (Android flattened
  all three into `ERR_NOTIFY`); Bluetooth being off rejects with `ERR_BLUETOOTH` from `createServer`,
  `startAdvertising`, `sendNotification`, `updateCharacteristicValue` and `disconnectDevice` (Android
  reported `ERR_CREATE_SERVER` or `ERR_NO_SERVER`, and iOS reported `ERR_CHARACTERISTIC_NOT_FOUND` from
  `sendNotification`); a `createServer` cancelled by a concurrent `stopServer` rejects with
  `ERR_NO_SERVER` (Android reported `ERR_CREATE_SERVER`); `sendResponse` looks the request up first, so
  an already-forgotten one reports `REQUEST_NOT_FOUND` rather than the reason the database went away,
  and a vanished central reports `ERR_DEVICE_DISCONNECTED`; and an Android adapter with no BLE
  advertising support rejects with `ERR_UNSUPPORTED` rather than the retryable `ERR_ADVERTISE`.
  `ERR_NOTIFY`, `ERR_CREATE_SERVER`, `ERR_ADVERTISE`, `ERR_RESPONSE` and `ERR_UPDATE_VALUE` remain as
  genuine fallbacks and no longer swallow a specific code
- An automatically acknowledged write did not update the value a later read is answered from on
  Android, so the same central saw its own write reflected on iOS and the stale value on Android. Both
  platforms now store it, replacing the attribute value as `ATT_WRITE_REQ` requires — "the attribute
  value shall be truncated or lengthened to match the length of the Attribute Value parameter" (Vol 3,
  Part F, Section 3.4.5.1). A characteristic configured with `delegate.write` is unaffected: its value
  still belongs to JavaScript until `updateCharacteristicValue` commits it
- A write bearing a non-zero offset overwrote the whole cached value with just that fragment on iOS,
  truncating the attribute to the length of the fragment. The fragment is now spliced in at its offset,
  and one starting past the end of the value is refused with `ATT_ERROR_INVALID_OFFSET` instead of
  being applied
- A malformed UUID string crashed the app on iOS. `CBUUID(string:)` raises an uncatchable
  Objective-C exception for anything but a 16-bit, 32-bit or hyphenated 128-bit spelling, so every UUID
  is validated before it reaches CoreBluetooth
- A characteristic declared with a `value` was published read-only on iOS, and adding it with any other
  property or permission raised "Characteristics with cached values must be read-only". Characteristics
  are now always published with a dynamic value and the initial value served from the module's own
  cache, so any configuration Android accepts works on iOS too
- Android registered services concurrently, although `BluetoothGattServer.addService` documents "do not
  add another service before this callback". They are now queued and added one at a time
- `startAdvertising` immediately after `createServer` failed on iOS, because `CBPeripheralManager.state`
  is `unknown` until its first callback arrives. The call now waits for a definitive state instead of
  sampling it
- `startAdvertising` rejected with `ERR_NO_SERVER` on Android whenever the services were still
  registering — an unawaited `createServer`, or the `poweredOn` event, which is delivered before the
  re-registration it triggers has finished. The call now parks until the database is published, as it
  already did on iOS, and is settled rather than stranded if the registration fails, the server is
  stopped, or Bluetooth goes off. A pending start abandoned because Bluetooth went off now rejects with
  `ERR_BLUETOOTH` rather than `ERR_ADVERTISE`
- Turning Bluetooth off permanently broke the server. The published database is destroyed on both
  platforms, and nothing re-published it; the module now retains the configuration and re-publishes on
  the next transition to `poweredOn`, reporting the intervening subscription losses and disconnections.
  Advertising is still the consumer's to restart
- A read whose offset was past the end of the cached value was handed to a listener the characteristic
  never opted in to, leaving the central to wait out its ATT transaction timeout. It is now answered
  with ATT `0x07` "Invalid Offset". An offset equal to the value's length is in range and answers with an
  empty value
- `sendResponse` consumed a pending request without checking it belonged to the device supplied,
  so one device's response could answer another's request. The owner is validated, and a rejected call
  now leaves the request answerable rather than discarding it
- A request delegated to JavaScript and never answered was retained forever, stalling the central until
  its own 30 s ATT transaction timeout — after which no further request, command, indication or
  notification could be sent on that bearer at all. See `requestTimeoutMs` above
- Notifying a central that had not subscribed resolved as a successful send on both platforms, although
  nothing was transmitted
- Android dropped a notification issued while an earlier one was still in flight, while the promise
  still resolved. Sends are now queued per device and handed over one at a time
- `onNotificationSent` reported a characteristic the notification may not have belonged to.
  `BluetoothGattServerCallback.onNotificationSent` names only the device, so the characteristic is now
  taken from the queue entry the callback actually completes
- iOS resent every subscribed central's cached value when the transmit queue drained, delivering
  unsolicited updates to centrals whose sends had never been refused. Only the refused payloads are
  resent, in order
- `updateCharacteristicValue` mutated the peripheral manager's state from the JavaScript thread on iOS;
  it now runs on the main queue, which is the queue CoreBluetooth delivers its callbacks on
- `stopServer` left services published on iOS. It unpublished only the services CoreBluetooth had
  already acknowledged, so anything still awaiting `didAdd` — or whose `didAdd` reported an error —
  stayed in the shared GATT database and collided with the next `createServer`. It now calls
  `removeAllServices`, clears the peripheral manager's delegate so late callbacks cannot revive the
  torn-down state, and resets the rest
- Android ignored the `preparedWrite` flag and never answered `onExecuteWrite`, so a central
  performing an ATT long or reliable write had its fragments applied immediately and then hung
  waiting for an execute response that never came. Fragments are now buffered per device and applied
  or discarded on execute, and the reassembled value is reported as one write event per attribute.
  iOS is unaffected — CoreBluetooth does not expose prepared writes to the peripheral role
- Android could lose a write to a characteristic that a long or reliable write was committing at the same
  moment. The execute's read-modify-write assembled the fragments onto the value the attribute held when
  it started but only the commit was guarded, so a `updateCharacteristicValue` or a plain write landing
  in between was overwritten by the assembly. The whole read-modify-write is now one critical section.
  Reads of a mirrored value take the same monitor, which they previously did not, so a value written on
  one binder thread is now guaranteed visible to the thread that answers the next read. iOS is unaffected:
  its manager is confined to the main queue
- Android treated an unavailable React context as "permission granted" and carried on into a
  `SecurityException`. `createServer` and `startAdvertising` now reject with `ERR_NO_CONTEXT`
- iOS addressed a stored characteristic value by characteristic UUID alone, while Android stores it on
  the per-service characteristic instance. GATT permits the same characteristic UUID in two services, so
  on iOS the last configured `value` won for both instances, an `updateCharacteristicValue` for one
  changed what a read of the other returned, and an automatically acknowledged write to one clobbered
  the other. Stored values and subscription tracking are now keyed by service **and** characteristic on
  iOS too, and the owning service of a characteristic CoreBluetooth hands back is resolved against the
  published database — `CBCharacteristic.service` is a `weak` reference a torn-down database may already
  have cleared. A read or write whose service genuinely cannot be named is answered with
  `ATT_ERROR_UNLIKELY_ERROR` rather than applied to the wrong attribute, and `onCharacteristicSubscribed`
  / `onCharacteristicUnsubscribed` now carry the right `serviceUuid` when a characteristic UUID appears
  in more than one service
- A write batch touching a characteristic that delegates writes and one that does not applied *neither*
  value, on both platforms: delegation was decided once for the whole batch. A plain characteristic
  written in the same reliable write as a delegated one was told the write succeeded and then kept its
  old value, and every event in the batch carried `responseNeeded: true`, so a handler answering per
  event got `REQUEST_NOT_FOUND` on the second. Delegation is now decided per characteristic on the batch
  paths too, matching the direct write path. The batch stays atomic: the plain characteristics' values
  are held until it is answered with `GATT_SUCCESS` and discarded if it is rejected or left to expire,
  and only the delegated characteristics' events carry `responseNeeded: true`
- **Breaking:** nothing rejected two services declaring the same UUID, or one service declaring the same
  characteristic UUID twice, even though `sendNotification` and `updateCharacteristicValue` address an
  attribute by that pair of UUIDs — Android's `getService` and `getCharacteristic` return the first
  match while iOS kept the last service added, so the same call reached a different attribute on each
  platform. Both cases now throw from `createServer` in the shared TypeScript layer, comparing UUIDs
  after normalisation so a 16-bit alias and its 128-bit expansion count as one, and both platforms
  repeat the check natively. The same characteristic UUID in two *different* services stays legal
- A `sendNotification` carrying both a stale `deviceId` and a mistyped characteristic UUID reported
  `ERR_CHARACTERISTIC_NOT_FOUND` on iOS and `ERR_DEVICE_DISCONNECTED` on Android, so a retry handler did
  opposite things per platform despite each single fault already agreeing. Android now checks the
  address before the connection, as iOS does, and the full order is documented — the address is the
  permanent fault of the two, and no retry fixes it
- `CharacteristicWriteRequestEvent.responseNeeded` was documented as never `true` for a Write Without
  Response. Android implements exactly that, but iOS cannot: `didReceiveWriteRequests:` delivers an ATT
  request and an ATT command through the same callback and `CBATTRequest` exposes no flag telling them
  apart. The types and the documentation now say so instead of promising it
- **Breaking:** `startAdvertising` did not check that a database was actually published, so a
  `createServer` that correctly reported a registration failure was followed by a `startAdvertising`
  that succeeded and exposed a half-built or empty database to scanners. Both platforms now reject with
  `ERR_NO_SERVER` unless the database is published — the same condition `isServerRunning` reports — so
  advertising an unpublished database now fails instead of exposing it. A call that arrives while the
  database is still being published waits for it on iOS and rejects with `ERR_NO_SERVER` on Android, so
  awaiting `createServer` — or retrying until `isServerRunning` — is what behaves the same on both. On
  iOS the services a failed registration did manage to publish are also unpublished again, once every
  callback of that round has arrived, rather than staying in the app's shared GATT database until the
  next `createServer`
- Restarting advertising left the previous advertisement running on Android, because
  `BluetoothLeAdvertiser` keys its advertising sets on the `AdvertiseCallback` instance and the module
  installed a fresh one for every start. Two calls with different `serviceUuids` left the device
  broadcasting both, `stopAdvertising` stopped only the most recent, and after a handful of restarts the
  controller ran out of advertisers — reporting `ERR_ADVERTISE` "Too many advertisers" while several
  stale advertisements were still on the air and `isAdvertising` reported `false`. The superseded
  advertisement is now stopped before the new one starts, as iOS has always done implicitly, since a
  `CBPeripheralManager` holds a single advertisement. A failure belonging to the superseded
  advertisement can also no longer settle the new call's promise
- `isAdvertising` reported `true` on Android after a restart whose new start threw — the adapter having
  been turned off between stopping the superseded advertisement and starting the replacement. Nothing
  cleared the flag until the adapter-state receiver happened to, so the module claimed to be advertising
  while the radio was silent
- A partly delegated write batch could silently undo a newer value. The values it holds for the
  characteristics that did not opt in were committed unconditionally when the batch was answered with
  `GATT_SUCCESS`, so an `updateCharacteristicValue` — or another central's write — that landed while the
  batch was outstanding was overwritten by what the batch had assembled, after resolving successfully.
  A held value is now committed only if that characteristic still holds what it did when the batch was
  assembled, and dropped otherwise; the batch is still answered with the status JavaScript passed. On
  Android a response the stack refuses also puts the held values back, rather than leaving them applied
  for a write the central was never told about

### Removed

- **Breaking:** `GATT_FAILURE`. It was `257`, a GATT *status* rather than an ATT error code, and an ATT
  error code is a single octet — Android narrowed it to its low byte, so it went out on the wire as
  `0x01` "Invalid Handle", and iOS could not represent it at all. Use the `ATT_ERROR_*` constant that
  describes the actual failure; `ATT_ERROR_UNLIKELY_ERROR` (`0x0e`) is the closest general-purpose
  replacement
- **Breaking:** the `MTU_SMALL` error code. An oversized `sendNotification` payload now rejects with
  `PAYLOAD_EXCEEDS_MTU`, which is the same condition under one name, and an oversized `sendResponse` is
  no longer an error at all
- **Breaking:** `ACCESS_FINE_LOCATION` from the Android manifest, which a peripheral does not need and
  every consuming app inherited. Location is a *scanning* concern, and this module never scans. An app
  that also scans must declare it — or `BLUETOOTH_SCAN` with
  `usesPermissionFlags="neverForLocation"` — itself

## [0.1.0] - 2025-05-23

### Added

- GATT server creation with configurable services and characteristics
- BLE advertising with localName, service UUIDs, TX power level, and connectable flag
- Event listeners for device connect/disconnect, read/write requests, notification delivery
- `sendNotification` and `sendResponse` for responding to centrals
- `updateCharacteristicValue` for updating cached characteristic values
- MTU validation with descriptive error codes (`MTU_SMALL`, `PAYLOAD_EXCEEDS_MTU`)
- Runtime permission checks for Android 12+ (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`)
- Auto-response for read requests when characteristic has a cached value
- CCCD descriptor auto-added for notify/indicate characteristics on Android

[Unreleased]: https://github.com/Dishuk/expo-gatt-server/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Dishuk/expo-gatt-server/releases/tag/v0.1.0
