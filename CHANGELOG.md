# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- `sendNotification` option `requireSubscription` for sending without a subscription on Android
- Error codes `ERR_NO_SUBSCRIBER`, `ERR_NOTIFY_QUEUE_FULL`, `ERR_DEVICE_DISCONNECTED`,
  `ERR_CHARACTERISTIC_NOT_FOUND`, `ERR_CONFIRM_UNSUPPORTED`
- `AdvertiseConfig.android` with `includeDeviceName` and `setAdapterName`
- `AdvertiseConfig` options `mode`, `txPowerLevel`, `timeoutMs`, `manufacturerData` and
  `serviceData`, with the types `AdvertisingMode`, `AdvertisingTxPower`, `ManufacturerDataEntry` and
  `ServiceDataEntry`
- Error code `ERR_UNSUPPORTED`, for advertising options iOS cannot express

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
- **Breaking:** the Android manifest no longer declares `ACCESS_FINE_LOCATION`, which a peripheral
  does not need and every consuming app inherited. Apps that also scan must declare it themselves
- **Breaking:** `android.hardware.bluetooth_le` is declared `required="false"`, so the module no
  longer filters consuming apps off Google Play on non-BLE devices. Declare it `required="true"` in
  your own manifest to restore the old behaviour
- **Breaking:** iOS rejects `manufacturerData`, `serviceData` and `connectable: false` with
  `ERR_UNSUPPORTED` instead of ignoring them; `mode`, `txPowerLevel` and `includeTxPowerLevel` are
  still ignored there but now log a warning

- `sendNotification` resolves when the platform reports the notification as delivered, and queues
  sends behind one still in flight instead of letting the platform drop them
- `sendNotification` rejects with `ERR_NO_SUBSCRIBER` instead of resolving when nothing is
  subscribed to the characteristic
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
- `onNotificationSent` reports the characteristic the notification actually carried
- iOS resends only the payload the transmit queue refused, instead of pushing cached values to every
  subscribed central

### Fixed

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
- Android treated an unavailable React context as "permission granted" and carried on into a
  `SecurityException`. `createServer` and `startAdvertising` now reject with `ERR_NO_CONTEXT`

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
