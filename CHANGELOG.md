# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
