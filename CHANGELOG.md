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

- **`addServerPublicationFailedListener`.** The module re-publishes the services on every transition to
  `poweredOn` / `STATE_ON`, and until now a re-publication that *failed* reported to nobody: by then
  `createServer` had resolved, so no promise was left to reject, and only a `startAdvertising` parked at
  that exact moment would have heard about it. The database really is gone afterwards — `isServerRunning`
  reports `false` and nothing retries — so an application that does not re-advertise from
  `onBluetoothStateChanged` had no way to learn it had stopped being a peripheral. The new event carries
  the code and message the equivalent `createServer` rejection would have carried, and is the signal to
  call `createServer` again. It is deliberately not emitted when Bluetooth is merely turned off, which
  `onBluetoothStateChanged` already reports and the next power-on re-publishes from, nor when a promise
  carried the same failure.


- **Test suites for the native peripherals, and CI that runs everything.** `npm run test:ios` (XCTest,
  via the root `Package.swift`) and `npm run test:android` (JUnit and Robolectric, via `tests/android`)
  cover the logic that is invisible until a peer connects: write assembly, response rebasing, the ATT
  error-code mapping, UUID spelling, MTU arithmetic, the CCCD bits, and the permission derivation that
  stops an unbonded central subscribing to an encrypted characteristic. Several ATT cases are duplicated
  across the two on purpose — the platforms implement those contracts independently, and asserting the
  same inputs produce the same bytes on each is what holds them together. Both run on the host, with no
  device or native app build. The concurrency is deliberately **not** covered; see
  [Development](docs/development.md#testing) for why.

  A further suite reads the three sources that have to agree on a name — `ExpoGattServerModule.ts`,
  `ExpoGattServerModule.swift` and `ExpoGattServerModule.kt` — and compares the method and event names
  directly. Expo resolves those by string at call time, so nothing in a type system saw them, and the
  two integration jobs compile native code against Expo without ever reading the TypeScript
  declaration: a method renamed on both platforms passed every check and broke in a consumer's app.

  The `ios-integration` job pins its Xcode rather than selecting the newest installed. Expo's toolchain
  window is bounded at both ends — SDK 57's prebuilt artefacts need Swift 6.2, and Xcode 26.3's compiler
  fails to type-check `ExpoModulesJSI` — so "newest" let a runner image update change the compiler under
  the job and turn it red on something this repository does not own.

  `.github/workflows/ci.yml` runs all three suites, the linter, the type-checker, both builds and a
  tarball-contents check, plus two jobs that compile each native binding against the real Expo
  toolchain — `android-integration` builds `:expo-gatt-server` after an `expo prebuild`, and
  `ios-integration` builds the `ExpoGattServer` pod target the same way. Those two exist because
  `ExpoGattServerModule.kt` and `ExpoGattServerModule.swift` are the only sources the host-side harnesses
  cannot compile, both importing Expo, and between them they are a third of the native code. The Gradle
  wrapper the Android harness commits is checked against Gradle's published checksums by
  `gradle/actions/wrapper-validation`.

  The example app is linted as well as type-checked. `expo-module lint` hardcodes its target to `src`,
  so `npm run lint` had never reached it — the rules the package fails its own build over were
  unenforced in the one place the guides point a reader at, and it had accumulated real errors while CI
  stayed green.

  The config plugin has a suite of its own, covering the two decisions that only surface in a build:
  that it raises an existing `android.hardware.bluetooth_le` requirement but never relaxes one, and the
  three-way precedence of `bluetoothAlwaysPermission`. `npm run lint` and `npm run typecheck` reach
  `plugin/src` as well as `src`, and Jest is rooted at both — a test placed under `plugin/` would
  previously not have run at all.

  The TypeScript suite was consolidated at the same time, from 910 reported tests to roughly a quarter
  of that, with no loss
  of coverage — verified by reintroducing twelve plausible defects and confirming each still fails the
  suite. The old figure was inflated by two things that added no detection: running every suite once per
  platform to reach three `Platform.OS` conditionals, which are now covered by mocking `Platform` and
  asserting both sides in one run; and cross products such as seven byte-taking entry points × ten
  invalid values, where all seventy cases reached the same predicate. Both patterns are described in
  [Development](docs/development.md#keeping-the-suites-honest) so they do not creep back.

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

- **Breaking (iOS): a delegated long write raises one event per attribute, not one per fragment.**
  `onCharacteristicWriteRequest` now carries the value as assembled at `offset: 0` — the shape Android
  already reported — instead of replaying the `ATT_PREPARE_WRITE_REQ` fragments the central happened to
  split it into. Because those fragments all shared the batch's single `requestId`, a three-fragment
  write to a delegated characteristic raised three events all claiming `responseNeeded`, and the second
  and third `sendResponse` rejected with `REQUEST_NOT_FOUND`. Exactly one event per batch now carries
  `responseNeeded: true`, since Apple answers a write callback once for the whole batch.
- **Breaking: `sendResponse` after `stopServer` rejects with `REQUEST_NOT_FOUND`, not `ERR_NO_SERVER`,
  on both platforms.** `docs/api.md` invites branching on `code` without also branching on
  `Platform.OS`. With no server there are no pending requests — `stopServer` answers and discards them —
  so the lookup could only have failed anyway, which makes a missing request the accurate description on
  either side.
- **Android's per-request tracing is off unless the log tag is turned up.** `Log.d` is not stripped from
  a release build, so tracing every ATT request unconditionally put a connected central's Bluetooth
  address — and, for descriptor traffic, the payload — into the logcat of every app shipping this
  module. The messages are now built only when `adb shell setprop log.tag.ExpoGattServer DEBUG` has been
  set, descriptor payloads are reported by length rather than by value, and warnings and errors are
  unchanged.
- **The published package no longer ships sourcemaps.** `files` ships only `build`, so every emitted map
  pointed at `../src/index.ts`, a path no consumer's install contains — which sends a debugger to a
  missing file rather than to the shipped output. `plugin/build` already emitted none.
- **The `expo` peer range names the SDK this is built against, and `expo-modules-core` is declared.**
  `expo: "*"` claimed support for every SDK ever published, none of which is exercised by anything;
  it is now `>=57.0.0`, which is what the development dependency, the guides and CI all agree on. And
  `src/index.ts` imports `Platform` and `EventSubscription` from `expo-modules-core`, which ships in
  `build/index.js` and `build/index.d.ts` while being declared only as a development dependency — it
  resolved by npm's hoisting rather than by declaration, so under pnpm's isolated layout or Yarn PnP a
  consumer's build failed on an import they never wrote.
- **`npm run lint` fails on warnings.** `eslint` exits 0 on warnings and the Prettier rules are
  registered as warnings, so the formatting configuration was enforced by nothing and CI stayed green
  regardless of it.
- **Breaking: an unrecognised or non-boolean `delegate` flag is now rejected.** Both native layers read
  the flags with a `?: false` fallback, so `delegate: { reed: true }` published a fully automatic
  characteristic: the listener never fired, reads were answered from the cached value, and nothing
  reported a problem. `delegate` was the only characteristic sub-object that reached the native side
  unchecked.
- **Breaking: the config plugin validates its props.** `app.json` is untyped at prebuild time, so
  `bluetoothAlwaysPermission: true` used to reach `Info.plist` as a non-string — which iOS reads as an
  absent key, terminating the app on first Bluetooth use — and `requireBluetoothLeHardware: "false"` was
  truthy enough to filter the app off Google Play. Both now fail the prebuild with a message naming the
  option.
- **iOS advertises a base-range service UUID in its shortest form.** The shared layer expands every UUID
  to 128 bits so both platforms are addressed identically, which costs nothing on Android but made
  `CBUUID` advertise sixteen octets where two would do — enough to push a service UUID out of the 31-byte
  advertisement into the Apple-only overflow area, where a non-Apple central filtering on it stopped
  finding the peripheral.
- **The package now publishes compiled JavaScript.** `main` and `types` pointed at `src/index.ts`, so
  every consumer received raw TypeScript and type-checked this package's source under their own
  compiler settings; the `build` script's output was never shipped at all, and the test suites were.
  `main` is now `build/index.js` with declarations alongside it, `prepublishOnly`
  builds both `build/` and `plugin/build/`, and the suites stay out of the tarball. No API change —
  but anything importing a deep path into `src/` will no longer resolve.

- `sendNotification`'s documented promise contract said the resolution meant the notification had been
  delivered. That is true on Android, which resolves from `onNotificationSent`, but **not on iOS**,
  where the peripheral role has no delivery callback at all and the promise resolves once CoreBluetooth
  accepts the payload for transmission — as the platform table further down the same page already said.
  The behaviour is unchanged and cannot be made to agree; the documentation now states the difference
  wherever the promise is described, and says not to build an application-level acknowledgement on it.

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
  `PERMISSION_WRITE_ENCRYPTED_MITM`. The descriptor's *read* stays unprotected, so a central can always
  discover whether it is subscribed.
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

- **Android crashed the process on a notification longer than 512 octets.** The payload was bounded by
  `ATT_MTU - 3` alone, which reaches 514 on a link that negotiated the maximum ATT_MTU of 517 — while an
  attribute value may hold only 512 (Core Spec Vol 3, Part F, §3.2.9), and
  `notifyCharacteristicChanged` answers a longer one by *throwing* rather than by reporting a status.
  Two of the three paths that hand a queued notification to the stack are the Bluetooth binder thread
  and the main looper, where nothing catches, so the throw took the application down. The bound is now
  the smaller of the two, `getMtu` reports the same figure so a payload sized against it is accepted,
  and the dispatch reports a refusal rather than letting anything escape a callback thread.
- **A stop racing a successful advertising start left Android advertising nothing and reporting
  otherwise.** `stopAdvertising` cleared its state before claiming the `AdvertiseCallback`, which is the
  only thing an in-flight callback tests to recognise itself — so an `onStartSuccess` delivered midway
  through set the flag back to `true`, re-armed the timeout and resolved the start as a *success*, after
  which the stop took the callback and really did stop the advertisement. `isAdvertising` then reported
  an advertisement that was not running for the rest of the process, and the caller had been told the
  opposite of what it asked for. The callback is now claimed first. The trigger is an ordinary React
  unmount: `startAdvertising()` followed by `stopAdvertising()`.
- **Two Android advertising failures left the phone's Bluetooth name changed for good.** `setAdapterName`
  renames the device system-wide, and the rename was undone on three exits but not on the two that
  matter most: a `startAdvertising` that threw because the adapter went off between the check and the
  call, and a start that a stop had already cancelled before the rename was applied. Both now restore it.
- **A teardown that emptied the registration queue was read as a completed publication on Android.**
  "No services left to register" is how the module recognises success, and it is also what an adapter
  power-off or a `stopServer` leaves behind — while the round guard could not tell them apart, because
  the round was only ever advanced when a *new* one started. An `onServiceAdded` delivered in that
  window resolved `createServer` successfully for a database about to be closed, dropped the
  `ERR_BLUETOOTH` the teardown meant to report, and left a stopped manager reporting `isServerRunning`.
  Every teardown now discards its round.
- **A cancelled `createServer` or `startAdvertising` could be disarmed by a call that never reached the
  native side.** The generation that decides which call owns the compensating stop was claimed on entry,
  before the arguments were validated — so a second call rejected by its own validation still counted as
  the most recent one and silently took ownership from the call genuinely in flight. That call then
  rejected saying no database was published, or nothing was on the air, while the opposite was true —
  and on Android for good, since it carries no native epoch of its own. The generation is now claimed at
  the hand-over, and released in a `finally` so a call whose native side rejects does not keep it either.
- **`createServer` left an in-flight `startAdvertising` reporting success.** Both platforms stop
  advertising as part of accepting a new server, so a start still in flight had the radio taken from
  under it and went on to resolve anyway. It is now cancelled, as `stopServer` already did.
- **iOS trimmed a read response from the front for a negative offset.** The check that a response is not
  supplied from *after* the offset the central asked for passes for a negative one — `-4` is not greater
  than `0` — and the positive skip that followed sent the central a value short of its leading octets,
  labelled as the whole attribute, with nothing reporting it on either side. Android had been hardened
  against exactly this and iOS had not, and the suite that exists to hold the two together never passed
  a negative offset. Both now reject it with `ERR_RESPONSE_OFFSET`.
- **A queued notification could wait forever on iOS, and one stalled central blocked every other.**
  `peripheralManagerIsReady(toUpdateSubscribers:)` is the only thing that drains the transmit queue and
  CoreBluetooth does not guarantee it, so a `sendNotification` promise behind a callback that never came
  never settled — an `await` that hung for the life of the process. The queue bound was also shared
  across the whole peripheral rather than per central, so one central that stopped draining filled it
  and then refused `sendNotification` for everybody else with `ERR_NOTIFY_QUEUE_FULL`. Each parked entry
  now expires after 35 s, the same bound Android already applied, and the bound is counted per central.
- **Android could strand a notification in a queue nothing would drain.** The queue was looked up with a
  non-atomic `getOrPut`, so two first sends to one device could each build one and lose whichever landed
  first; and an entry enqueued while a teardown detached the queue was only recovered if the device was
  *still* gone — a central that disconnected and reconnected inside that window left the entry behind
  with no timeout armed and a promise that never settled. The queue is now registered atomically, and an
  entry left in a detached one is settled rather than abandoned.
- **The Android manager kept emitting events after it was stopped.** `stop` documented that it cleared
  the listener and never did, so a manager displaced by a second `createServer` went on reporting late
  disconnects and notification callbacks indistinguishably from the server that replaced it — and after
  the module was destroyed the same path reached `sendEvent` on a torn-down `AppContext`, which throws
  on a binder thread where nothing catches. iOS held its delegate weakly and was unaffected.
- **Android could leak a `BluetoothGattServer`.** `open` registers the adapter-state receiver before it
  checks whether the adapter is usable, so an adapter finishing its power-on in that window had the
  broadcast open a second server over the first — which was then unreferenced, never closed, and still
  serving a live copy of the database. A server already open is now closed before another is opened.
- **A Bluetooth toggle could hang the Android UI thread.** The adapter-state receiver was registered
  without a handler, so the whole server rebuild — `openGattServer`, `addService`, `setName`, and the
  advertising starts released behind it — ran on the main thread, behind a lock held across binder calls
  of its own. A `BroadcastReceiver` has about ten seconds before an ANR. All of it now runs on a looper
  belonging to the manager, created with the receiver and quit with it.
- **Android's manifest hid every consuming app from devices without Bluetooth hardware.** Google Play
  implies `android.hardware.bluetooth` as *required* from the `BLUETOOTH` and `BLUETOOTH_ADMIN`
  permissions, which is exactly the filtering the neighbouring `bluetooth_le` declaration exists to
  prevent. It is now declared explicitly as not required; an app that needs it still raises it, since
  `android:required` merges by OR.
- **The two platforms disagreed about UUIDs and timeouts at the native boundary.** Android had no
  short-form UUID expansion of its own, so a direct native caller passing `"180D"` succeeded on iOS and
  threw here — the divergence the shared layer's `normalizeUuid` exists to remove, unrepeated on the
  platform that needs it — and `java.util.UUID.fromString` silently zero-pads short groups, so
  `"180d-0-1000-8000-00805f9b34fb"` parsed as a different UUID than it reads as. iOS meanwhile took the
  advertising timeout on trust; Swift bridges `Bool` to `NSNumber` where Kotlin's `Boolean` is not a
  `Number`, so `timeoutMs: true` threw on Android and, on iOS, resolved and then stopped the
  advertisement a millisecond later. Both are now checked natively on both platforms.
- **Two `startAdvertising` calls parked behind one publication both reached the iOS radio.** Everyone
  waiting is released in a single turn, and `peripheralManagerDidStartAdvertising` names no particular
  call — so one callback settled whichever completion happened to be installed, reporting the outcome of
  one advertisement against the promise of another. The previous advertisement is now taken off the air
  before the replacement goes on it.
- **A mistyped configuration key was ignored rather than reported.** Every native parser reads the keys
  it knows and ignores the rest, so `delegat` published a characteristic as fully automatic, `descriptor`
  published none, `serviceUUIDs` advertised no service UUIDs — leaving a central filtering on one unable
  to find the peripheral — and `requestTimeoutMS` silently kept the default. `delegate` was the only
  object guarded against this. Every configuration object now rejects a key nothing below would read.


- **A `stopServer` issued while `createServer` was still in flight was ignored, on both platforms.**
  `stopServer` is a synchronous `Function`, so it runs on the JavaScript thread the moment it is called,
  while `createServer` runs later on Expo's worker queue — so the stop reached the native side first,
  found no server to stop, and the create behind it published the whole database anyway. This is the
  ordinary React case: an effect that sets a server up and returns a teardown, unmounted before the
  create settles. The ordering is now recorded on the JavaScript thread, the way `stopAdvertising`
  already was, and a create the application abandoned rejects with `ERR_NO_SERVER` and stops the server
  it published. On Android a stop landing between the manager being installed and its `open` also used
  to leave a `BluetoothGattServer` and a registered broadcast receiver behind with nothing holding a
  reference to either; `open` now refuses a manager that has already been stopped.
- **Android marked every delegated attribute of a reliable write as needing a response.** An execute is
  a single ATT request, and one pending request stands for the whole batch — so a batch touching two
  `delegate.write` characteristics raised two events both carrying `responseNeeded: true` and the same
  `requestId`, and the second `sendResponse` rejected with `REQUEST_NOT_FOUND` after the first had
  already answered the execute. Exactly one attribute is now marked, which is what iOS did and what the
  documentation described.
- **Answering a request after the server stopped reported a different code on each platform.** iOS
  reported `REQUEST_NOT_FOUND` and Android `ERR_NO_SERVER`, for the same situation, on the path a
  delegated handler resolving after unmount takes. Both now report `REQUEST_NOT_FOUND`, which is what
  the API reference already documented for both.
- **The bound on an in-flight notification raced the Bluetooth stack instead of outliving it.** It was
  set to exactly the ATT transaction timeout and armed before the send rather than after, so an
  unconfirmed indication expired here first: the real `onNotificationSent`, carrying the genuine failure
  status, arrived to an entry already settled and was discarded, and the recovery pumped the next entry
  while the stack still considered the previous one in flight — which it refuses, rejecting the whole
  queued backlog in one sweep.
- **Android left the open completion armed when opening the server threw.** `registerReceiver` and
  `openGattServer` are binder calls that can fail; an escaping exception rejected the caller's promise
  at the binding while leaving the publication `IN_PROGRESS` with no bound, so every later
  `startAdvertising` parked forever and the next `stopServer` settled the already-rejected promise a
  second time.
- **`android.setAdapterName` could leave the phone's system-wide Bluetooth name changed for good.** The
  restore ran only from `stopAdvertising` and the adapter coming back on, so an `AdvertiseConfig.timeoutMs`
  elapsing, and a start that failed after the rename had been applied, both ended with nothing on the air
  and the device still renamed. Both now restore it. A `stopServer` issued while the adapter is off still
  cannot, which is now stated plainly rather than described as a retry that will eventually happen.
- **iOS sized and addressed notifications with the `CBCentral` captured when the central subscribed.**
  CoreBluetooth may vend a distinct instance per callback and reads `maximumUpdateValueLength` from
  whichever is current, which is why every other read of it refreshes first — so a central that
  negotiated a larger MTU after subscribing had `getMtu` report the new budget while `sendNotification`
  rejected the very payload it had just been told would fit.
- **iOS never resolved `startAdvertising`, and never drained a backed-up notification queue.** Two
  `CBPeripheralManagerDelegate` methods were spelled as ordinary delegate callbacks rather than as the
  selectors CoreBluetooth dispatches — `peripheralManager(_:didStartAdvertising:)` for
  `peripheralManagerDidStartAdvertising(_:error:)`, and `peripheralManagerIsReady(_:)` for
  `peripheralManagerIsReady(toUpdateSubscribers:)`. Both requirements are optional, so both compiled
  without an error or even a warning, and neither was ever called. The radio advertised but the promise
  never settled; and the first `updateValue` the transmit queue refused parked an entry that nothing
  could release, so after 64 queued sends every further one rejected `ERR_NOTIFY_QUEUE_FULL` for the
  life of that connection. `tests/swift/DelegateConformanceTests.swift` now asserts the manager responds
  to every selector it means to implement, which is the only check that catches this class of mistake.
- **iOS silently discarded every configured characteristic and descriptor `value`.** They were decoded
  with `map["value"] as? [Int]`, but expo-modules-core converts an untyped `[String: Any]` argument
  through `JavaScriptValue.getAny()`, which maps every JavaScript number to `Double` — so the cast
  never succeeded. A characteristic lost its cached value and fell through to the delegated read path,
  stalling reads for `requestTimeoutMs` in an app that had no listener because it had configured a
  value; a descriptor published empty, and a `0x2904` Presentation Format descriptor published at zero
  length was rejected by CoreBluetooth, failing the whole `createServer` with `ERR_CREATE_SERVER`. The
  parsing has moved to `ios/GattConfigurationParsing.swift` so `swift test` can reach it — it was
  previously covered by nothing, on the mistaken grounds that the TypeScript suite validated the same
  configuration.
- **Android's `stopServer` left a central's ATT bearer wedged.** A delegated read or write still
  awaiting `sendResponse` was dropped rather than answered, so the central waited out its own 30 s
  transaction timeout — after which no further request, notification or indication may be sent on that
  bearer at all. `stopServer` disconnects nobody, so those links stay up. It now answers each one with
  `ATT_ERROR_UNLIKELY_ERROR` before closing the server, which is what iOS already did and what
  `docs/api.md` already claimed for both platforms.
- **A cancelled `startAdvertising` could take a newer advertisement off the air.** The compensating
  `stopAdvertising` a cancelled start issues is not addressed to a particular advertisement, so in
  `start(A); stop(); await start(B);` the abandoned start A stopped B — after B had already resolved.
  Nothing was advertising and no promise reported a failure. Only the most recent start now compensates.
- **A `createServer` still parsing could outlive the `stopServer` meant to cancel it on iOS.**
  `createServer` is asynchronous and `stopServer` synchronous, so the pair could reach the main queue in
  the opposite order — the stop finding no manager, the create then publishing the database with no
  handle left to remove it. This is the hazard `advertisingStopEpoch` already covered for advertising;
  `createServer` now has the same guard, and rejects with `ERR_NO_SERVER`.
- **A registration round that timed out on iOS left its services published.** The deferred unpublish
  waited for every service of the round to report, which on the timeout path can never happen — so
  `removeAllServices` never ran and the services stayed discoverable while the module reported no
  server.
- **Android could open two GATT servers at once.** `createServer`, `stopServer` and the adapter-state
  broadcasts run on three different threads with nothing serialising them, so a `createServer` racing a
  `STATE_ON` could open two servers against one shared callback — leaking one for the life of the
  process while it still served a live copy of the database. The server lifecycle is now serialised, and
  each round's callback carries its own identity so a late acknowledgement from a discarded round can no
  longer advance or fail the round now running.
- **A publication bound could overwrite a database that had just published.** The timeout read the
  publication state and wrote it in two steps, so a round completing in between was recorded as
  `FAILED` — leaving `isServerRunning` false and every later `startAdvertising` rejecting
  `ERR_NO_SERVER` for a database that was in fact published.
- **An in-flight notification had no bound on Android.** A `notifyCharacteristicChanged` the stack
  accepted but never reported as sent wedged that device's queue permanently. Every other asynchronous
  wait in the module was already bounded; this one now is too.
- **A partially delegated execute committed its plain descriptor values before it could be refused.**
  Characteristic values and CCCD changes were correctly withheld until JavaScript answered; ordinary
  descriptors were not, so a rejected reliable write left one holding the new value.
- **Concurrent CCCD writes could emit the wrong subscribe/unsubscribe events.** The previous
  subscription state was sampled outside the update that replaced it, so two enabling writes arriving on
  two ATT bearers could both report a subscribe.
- **A `stopAdvertising` could be silently overtaken by the `startAdvertising` it was meant to cancel.**
  `stopAdvertising` is synchronous, so its body runs on the JavaScript thread the moment it is called,
  while `startAdvertising` is asynchronous and its native body runs later on Expo's own worker queue —
  so an un-awaited start issued *first* could reach the peripheral *after* the stop behind it, read the
  stop's own generation counter as its baseline, pass its cancellation check and put the radio on the air
  after the application had explicitly asked for the opposite. Neither platform is told which call came
  first; JavaScript is single-threaded, so the shared layer now carries that order across and stops the
  advertisement again if a stop was issued while a start was in flight. The start rejects with
  `ERR_ADVERTISE`, the code a natively-cancelled start already reports. `stopServer` cancels a pending
  start the same way, since it stops advertising too.

- **iOS dropped a central's outstanding ATT requests when the server stopped, instead of answering them.**
  `stopServer` disconnects nobody, so a delegated read or write still awaiting `sendResponse` belonged to a
  live bearer — and leaving it unanswered stalls that bearer until the central's own 30 s ATT transaction
  timeout expires, after which no further request, notification or indication may be sent on it at all
  (Core Spec Vol 3, Part F, §3.3.3). It was the one teardown path not following the rule the rest of the
  file is built around; both platforms now answer with `ATT_ERROR_UNLIKELY_ERROR` before unpublishing.

- **iOS cancelled unrelated in-flight requests when a central unsubscribed.** CoreBluetooth reports a
  cleared Client Characteristic Configuration and a vanished central through the same callback, so losing
  the last subscription is the only disconnect signal the peripheral role has — but treating it as one also
  answered every pending request for that central with `ATT_ERROR_UNLIKELY_ERROR` and made the matching
  `sendResponse` reject with `REQUEST_NOT_FOUND`. A central that simply stopped streaming while a delegated
  write was in JavaScript's hands therefore had that write killed on a live connection. The disconnection is
  still reported, but an inferred one no longer ends anything: a request whose central really has gone
  expires on its own via `requestTimeoutMs`. A subscription whose owning service could not be named is also
  no longer left behind in `getConnectedDevices` for the life of the manager.

- **A registration round that was never acknowledged hung `createServer` forever.** `onServiceAdded` on
  Android and `didAdd` on iOS are the only things that advance a publication, and neither platform
  guarantees one arrives — nor passes anything identifying which round a callback belongs to, so an
  acknowledgement left over from a round an adapter power cycle discarded cannot be told from the current
  round's. Either way the wait was unbounded, taking `createServer`'s promise and every caller parked in
  `startAdvertising` with it for the life of the process. Both platforms now bound a round at 30 s and
  report `ERR_CREATE_SERVER`, which is retryable. Registration is local bookkeeping that completes in
  milliseconds, so nothing healthy comes close.

- **A Read Blob of a descriptor was answered with the whole value again on Android, at every offset.**
  `onDescriptorReadRequest` handed the stack the complete value together with the request's offset, and
  the stack copies the value into the response PDU verbatim rather than slicing it — so a central
  continuing a descriptor longer than one PDU reassembled a repeated prefix, and an offset past the end
  was answered with success instead of `ATT_ERROR_INVALID_OFFSET`. A `0x2901` Characteristic User
  Description over `ATT_MTU - 1` octets is the realistic case. Descriptor reads now slice and
  bounds-check exactly as characteristic reads already did, through the one shared function both paths
  now use.

- **A `delegate` configured on one service reached the same characteristic UUID in another.** The
  delegation lookup fell back to a characteristic-UUID-only map whenever the exact
  service-and-characteristic address had no entry, rather than only when the owning service could not be
  identified at all. GATT permits the same characteristic UUID in two services and this module accepts
  it, so the second service's characteristic — which never opted in — had its Write Without Response
  silently discarded and its reads handed to a listener that was never going to answer them, stalling
  each one until the request timeout. iOS resolved the exact address all along.

- **A long write to iOS truncated any part of the attribute it did not cover, while Android kept it.**
  CoreBluetooth runs the queued-write procedure below the app layer and delivers its result through the
  same delegate callback as an ordinary write, with no flag telling the two apart — so every fragment at
  offset 0 was assembled as a whole-value replacement. Writing 4 octets of an 8-octet attribute left 4
  octets on iOS and 8 on Android. The batch's shape is now what decides: more than one request, or any
  request at a non-zero offset, is beyond what a single `ATT_WRITE_REQ` can produce and is assembled as
  a queued write, preserving the octets past the fragment as Android does. A lone part at offset 0 stays
  genuinely ambiguous — identical in shape to an unqueued write — and is still assembled as one, which
  is now stated rather than accidental.

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
  database is still being published parks until it is, on both platforms — see the entry above — so
  `startAdvertising` is safe immediately after an unawaited `createServer`. On
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
- A server created while Bluetooth was off stayed inert for good on Android. `createServer` rejected
  with `ERR_BLUETOOTH` before registering the adapter-state receiver or retaining the service
  configuration, so the re-publication the module documents on the next `poweredOn` never happened, no
  `onBluetoothStateChanged` event ever arrived to say Bluetooth had returned, and a `startAdvertising`
  issued once it had came back parked on a registration round that nothing would ever start — a promise
  that never settled either way. Both are now installed before the adapter is checked, so the rejection
  leaves behind exactly what iOS leaves behind: a server that publishes itself when Bluetooth returns
- A central could be sent values from a characteristic it had never subscribed to on Android, where two
  services declare the same characteristic UUID — which the specification permits and this module
  accepts. Client Characteristic Configuration state was keyed by characteristic UUID alone, so
  subscribing to one service's characteristic marked the other service's namesake subscribed too: a
  `sendNotification` naming the second passed the `requireSubscription` check and went out over the air,
  since `notifyCharacteristicChanged` does not consult the descriptor itself. Reads of the descriptor
  answered with the wrong instance's bits, the second subscription raised no
  `onCharacteristicSubscribed`, and unsubscribing from either ended both. The configuration is now keyed
  by service and characteristic, as it already was on iOS, and an unsubscribe reported on teardown names
  the service the client actually configured rather than the first one declaring that UUID
- Every attribute value written by a central or by `updateCharacteristicValue` reverted to the value the
  configuration declared whenever Bluetooth was power-cycled on Android, while iOS kept them. The
  adapter going down invalidates the server, so the services are rebuilt from the configuration to
  re-register them — and the rebuilt characteristics carried the configured initial values again. The
  values the published characteristics hold are now carried across the rebuild, so a power cycle costs
  the connections and the subscriptions, as documented, but not the contents of the database

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
