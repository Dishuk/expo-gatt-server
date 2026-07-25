# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `onCharacteristicSubscribed` / `onCharacteristicUnsubscribed` events, with
  `addCharacteristicSubscribedListener` and `addCharacteristicUnsubscribedListener`
- Per-device, per-characteristic Client Characteristic Configuration tracking on Android, as the
  Bluetooth specification requires
- `sendNotification` option `requireSubscription` for sending without a subscription on Android
- Error codes `ERR_NO_SUBSCRIBER`, `ERR_NOTIFY_QUEUE_FULL`, `ERR_DEVICE_DISCONNECTED`,
  `ERR_CHARACTERISTIC_NOT_FOUND`
- `AdvertiseConfig.android` with `includeDeviceName` and `setAdapterName`

### Changed

- **Breaking:** Android no longer renames the device's Bluetooth adapter when `localName` is set.
  Android has no per-advertisement local name, so the device's own name is advertised instead. Set
  `android.setAdapterName` to opt back in to the rename, which is now undone when advertising stops

- `sendNotification` resolves when the platform reports the notification as delivered, and queues
  sends behind one still in flight instead of letting the platform drop them
- `sendNotification` rejects with `ERR_NO_SUBSCRIBER` instead of resolving when nothing is
  subscribed to the characteristic
- `onNotificationSent` reports the characteristic the notification actually carried
- iOS resends only the payload the transmit queue refused, instead of pushing cached values to every
  subscribed central

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
