# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - unreleased

> ### Read this before upgrading
>
> **This release reworks the public API with many breaking changes** (marked **Breaking** below):
>
> - `GATT_FAILURE` and `MTU_SMALL` error codes are removed; use `ATT_ERROR_*` constants and `PAYLOAD_EXCEEDS_MTU` instead
> - `createServer` and `startAdvertising` now reject when cancelled by `stopServer` or `stopAdvertising` (e.g., React unmount teardown)
> - Package entry point moves from `./src/index.ts` to `./build/index.js`; deep imports to `src/` no longer resolve
> - Event payloads report UUIDs in lowercase 128-bit form; string comparisons with short/uppercase forms will fail
> - `sendNotification` now rejects when no subscriber, when characteristic lacks the `confirm` property, or when payload exceeds MTU
> - `sendNotification` no longer sets the read value; call `updateCharacteristicValue` separately if needed
> - `updateCharacteristicValue` returns a promise and rejects on unknown characteristic
> - `CharacteristicWriteRequestEvent.responseNeeded` now indicates module is waiting, not that central requested acknowledgement
> - Misspelled property/permission names throw; unknown keys in options objects throw; repeated descriptor UUIDs throw
> - `createServer` requires `services` array (not `undefined`); each service requires `characteristics` array
> - Characteristic/descriptor values bounded at `MAX_ATTRIBUTE_VALUE_LENGTH` (512 octets)
> - Android stops renaming adapter unless `android.setAdapterName` is set; `ACCESS_FINE_LOCATION` permission removed; `android.hardware.bluetooth_le` no longer required
> - `expo` peer dependency narrows to `>=57.0.0`; apps on SDK 51–56 no longer resolve this package; `expo-modules-core` now required, and the unused `react` / `react-native` peers are gone
> - Node 20.19.4 is the minimum, matching React Native 0.86

### Added

- **`Uint8Array` is accepted anywhere bytes are taken.** Byte parameters now accept `Bytes` type (`number[] | Uint8Array`); event payloads still return `number[]`
- **`addServerPublicationFailedListener`.** Emitted when a re-publication fails after Bluetooth power cycle; carries the code the equivalent `createServer` rejection would (usually `ERR_CREATE_SERVER`); signals to call `createServer` again
- **Test suites and CI.** `npm run test:ios` and `npm run test:android` cover write assembly, ATT error-code mapping, UUID spelling, CCCD bits, and permission derivation; host-side suite validates method/event names match across `ExpoGattServerModule.ts`, `.swift`, and `.kt`; TypeScript suite consolidated to 1/4 of prior test count with full coverage retained
- **Expo config plugin.** Add `"plugins": ["expo-gatt-server"]` to `app.json`; optional properties: `bluetoothAlwaysPermission`, `bluetoothPeripheralBackgroundMode`, `requireBluetoothLeHardware`
- **`isSupported`.** Package now imports optionally; no longer throws on web/Expo Go; calls reject with platform-specific error instead
- **`getConnectedDevices` and type `ConnectedDevice`.** Enumerates connected centrals tracked by module (not OS-level GATT_SERVER profile)
- **`disconnectDevice`.** **Android only**; iOS rejects with `ERR_UNSUPPORTED`
- **`isServerRunning` and `isAdvertising`.** Query database and radio state
- **`getBluetoothState`, `onBluetoothStateChanged` event, types `BluetoothState` and `BluetoothStateChangedEvent`.** Normalised adapter state across platforms; event delivered only while server exists
- **`getMtu`, `onMtuChanged` event, types `DeviceMtu` and `MtuChangedEvent`.** `maxNotificationPayload` = `min(mtu - 3, 512)` octets
- **`GattCharacteristicConfig.delegate` and type `CharacteristicDelegateConfig`.** `delegate.read` and `delegate.write` control delegation of read/write requests
- **`ATT_ERROR_*` constants.** One per ATT error code `0x01` to `0x11` (Core Spec Vol 3, Part F, Table 3.4)
- **`EventSubscription` type.** Re-exported from `expo-modules-core`; all `add*Listener` functions typed with it
- **Encrypted/authenticated permissions.** `readEncrypted`, `readEncryptedMitm`, `writeEncrypted`, `writeEncryptedMitm`, `writeSigned`, `writeSignedMitm`; MITM variants reject on iOS with `ERR_UNSUPPORTED`
- **`CharacteristicProperty` values.** `broadcast`, `signedWrite`, `extendedProperties` (latter two reject on iOS)
- **`GattServiceConfig.type` and `GattServiceType`.** For secondary services
- **`GattCharacteristicConfig.descriptors` and `GattDescriptorConfig`.** Custom descriptors beyond automatic CCCD; iOS accepts only 0x2901 and 0x2904
- **`CLIENT_CHARACTERISTIC_CONFIGURATION_UUID` constant**
- **`createServer` option `requestTimeoutMs`, constants `ATT_TRANSACTION_TIMEOUT_MS` and `DEFAULT_REQUEST_TIMEOUT_MS`.** Delegated requests timeout to `ATT_ERROR_UNLIKELY_ERROR` after 10 s by default
- **`onCharacteristicSubscribed` / `onCharacteristicUnsubscribed` events** with listener functions
- **Per-device, per-characteristic CCCD tracking on Android**
- **`sendNotification` option `requireSubscription`.** For forcing sends on Android without subscription; no effect on iOS
- **Error codes.** `ERR_NO_SUBSCRIBER`, `ERR_NOTIFY_QUEUE_FULL`, `ERR_DEVICE_DISCONNECTED`, `ERR_CHARACTERISTIC_NOT_FOUND`, `ERR_CONFIRM_UNSUPPORTED`, `REQUEST_DEVICE_MISMATCH`, `ERR_RESPONSE_OFFSET`, `ERR_NO_CONTEXT`, `ERR_DISCONNECT`
- **`AdvertiseConfig.android` properties.** `includeDeviceName` and `setAdapterName`
- **`AdvertiseConfig` options.** `mode`, `txPowerLevel`, `timeoutMs` (bounded at 180000), `manufacturerData`, `serviceData` with types `AdvertisingMode`, `AdvertisingTxPower`, `ManufacturerDataEntry`, `ServiceDataEntry`; `timeoutMs` emulated on iOS with timer calling `stopAdvertising`
- **`ERR_UNSUPPORTED` error code.** For iOS-unsupported configuration/advertising options
- **Short-form UUIDs.** Accept 4-digit (16-bit) or 8-digit (32-bit) hex forms alongside hyphenated 128-bit
- **`example/` app, unit tests in `src/__tests__/`, `LICENSE` file**

### Changed

- **Native managers split into focused collaborators.** No behaviour or API change. `GattServerManager` kept publication and ATT routing; advertising, notification queueing, delegated requests, attribute values, per-client CCCD state and (Android) queued writes moved to one class each — `AdvertisingCoordinator`/`AdvertisingController`, `NotificationQueue`/`NotificationDispatcher`, `PendingRequestStore`, `AttributeStore`, `SubscriptionRegistry`, `PreparedWriteQueue`. The Swift write arithmetic moved from methods on the manager to free functions in `WriteBatch.swift`, so the host suite no longer builds a manager to reach it. Kotlin 2495 → 1429 lines, Swift 1533 → 916
- **Breaking: `sendNotification` signature.** Now `sendNotification(deviceId, serviceUuid, characteristicUuid, value, options?)` with `confirm` and `requireSubscription` in options object
- **Breaking (iOS): delegated long writes.** `onCharacteristicWriteRequest` fires once per attribute with assembled value, not per fragment; one event per batch carries `responseNeeded: true`
- **Breaking: `sendResponse` error after `stopServer`.** Rejects with `REQUEST_NOT_FOUND` (not `ERR_NO_SERVER`) on both platforms
- **Android per-request tracing.** Logs only when `adb shell setprop log.tag.ExpoGattServer DEBUG` is set; descriptor payloads reported by length only
- **No sourcemaps in package.** Sourcemaps removed from shipped output
- **Breaking: peer dependencies.** `expo: ">=57.0.0"` (was `*`); `expo-modules-core` now required as peer dependency
- **`npm run lint` fails on warnings.** Prettier rules now enforced
- **Breaking: `delegate` flag validation.** Misspelled flags like `delegate: { reed: true }` now throw instead of silently failing
- **Breaking: config plugin validation.** Invalid types (e.g., `bluetoothAlwaysPermission: true` or `requireBluetoothLeHardware: "false"`) now fail prebuild
- **iOS UUID advertising.** Service UUIDs now advertised in shortest form (16-bit when possible)
- **Package now ships compiled JavaScript.** `main` points to `build/index.js`; deep imports to `src/` no longer resolve
- **`sendNotification` promise semantics.** Documented: Android resolves on `onNotificationSent`, iOS once CoreBluetooth accepts for transmission (cannot guarantee delivery)
- **UUID expansion.** 16/32-bit short forms expanded to 128-bit Bluetooth Base UUID in TypeScript layer; both platforms now accept all three forms
- **Breaking: UUID case normalization.** Event payloads report UUIDs as lowercase 128-bit form
- **Breaking: empty characteristic values.** `value: []` now means zero-length cached value on iOS (was delegated); omit `value` to delegate
- **Breaking: property/permission validation.** Misspelled characteristic property/permission names throw instead of silent drop
- **Breaking: Android adapter naming.** No longer renames adapter with `localName`; set `android.setAdapterName` to opt in; rename undone on stop
- **Breaking: Android default mode.** Default advertising mode is `lowPower` (was `lowLatency`); pass `mode: 'lowLatency'` for old behavior
- **Breaking: `android.hardware.bluetooth_le`.** Now declared `required="false"` (was `true`); pass config plugin's `requireBluetoothLeHardware` to restore
- **Breaking: iOS unsupported options.** `manufacturerData`, `serviceData`, `connectable: false` reject with `ERR_UNSUPPORTED` (were silently ignored)
- **Breaking: `CharacteristicWriteRequestEvent.responseNeeded`.** Indicates module awaiting JavaScript response (not central's acknowledgement request); `requestId` is real (not hardcoded `0`)
- **Breaking: byte validation.** Bytes outside `0–255` in `number[]` throw instead of truncate/clamp
- **Breaking: `sendResponse` validation.** Out-of-range `status` or `offset` throws
- **Breaking: `sendResponse` offset handling.** `offset` now rebased to request's offset; `offset` past request range rejects with `ERR_RESPONSE_OFFSET`
- **Breaking: `createServer` timing.** Resolves only after all services confirmed published
- **Breaking: `startAdvertising` type validation.** `localName`, `connectable`, `includeTxPowerLevel`, `android.includeDeviceName`, `android.setAdapterName` type-checked; wrong types throw
- **Breaking: `sendNotification` payload size.** Rejects with `PAYLOAD_EXCEEDS_MTU` before transmit if payload too large; `sendResponse` no longer size-checked
- **Breaking: `onDeviceConnected` on iOS.** Fires on first ATT activity (subscribe/read/write), not just first subscribe; connections tracked separately from subscriptions
- **`sendNotification` queuing.** Queues sends per device (max 64 waiting); resolves semantics differ per platform and cannot be unified
- **`sendNotification` subscription check.** Rejects with `ERR_NO_SUBSCRIBER` (not resolve) when no subscriber
- **Breaking: `sendNotification` decoupling.** No longer changes readable value; call `updateCharacteristicValue` separately if needed
- **Breaking: `updateCharacteristicValue` semantics.** Returns `Promise<void>`; rejects on unknown characteristic; runs on main queue on iOS
- **Breaking: `sendNotification` property validation.** Rejects with `ERR_CONFIRM_UNSUPPORTED` if `confirm` property not declared
- **Breaking: Android `requireSubscription` bit check.** Checks specific CCCD bit (not just subscription state); iOS checks subscription only
- **Breaking: Android CCCD permissions.** Descriptor write permission derived from characteristic (encrypted/MITM characteristic requires encrypted subscription)
- **Breaking: iOS encrypted subscriptions.** Adds `.notifyEncryptionRequired` or `.indicateEncryptionRequired` to property when encryption needed
- **`onNotificationSent` report.** Now reports correct characteristic
- **iOS retry behavior.** Resends only refused payloads (not cached values to all centrals)

### Fixed

- **iOS publication credit tracking.** Stale `didAdd` acknowledgement could swallow next round's callback, stalling 30 s; now tracked as count not flag
- **Non-integer byte crashes.** `sendNotification`, `sendResponse`, `updateCharacteristicValue` now accept `Double` and narrow to valid bytes (iOS trapped on `NaN`/infinity, Android silently converted to `0x00`)
- **Android unqueued write size.** Write now validated against 512-octet limit (was only checked for queued writes)
- **Android overlapping advertise start.** Timeout bound can no longer be evicted by displaced start; adapter name restore only happens during ownership
- **iOS unbounded createServer waits.** Now bounded at 30 s when adapter is `unknown` or already `resetting`
- **iOS `maxNotificationPayload` bound.** Now correctly limited to `min(mtu - 3, 512)` (was uncapped)
- **Android stop racing onStartSuccess.** Callback re-tests ownership after state publish; stop can't re-enable flag
- **Android notification retry threading.** Retry now posts to lifecycle thread (not main/binder thread)
- **iOS duplicate descriptor UUID.** Now rejects in TypeScript and natively; iOS no longer crashes on `NSInternalInconsistencyException`
- **iOS `sendResponse` requestId crash.** All integer args now `Double` type narrowed once, not `Int(double.rounded())` which traps on `NaN`
- **iOS coalesced writes.** Procedure now detected by offset alone (not fragment count), preserving stale bytes correctly
- **iOS write deduplication.** Long writes reported once per attribute; coalesced writes now raise one event each
- **Error code consistency.** `onServerPublicationFailed`, `sendResponse` failures, and timeout cases now report same codes on both platforms
- **iOS late service registration.** Services now added serially; stale `didAdd` acks now correctly skipped
- **Android round teardown.** Failed round no longer overwrites explicit teardown state
- **`startAdvertising` timeout.** Both platforms now bound at 30 s; timeout stops advertisement and rejects promise
- **iOS createServer timeout.** Bound now outlives round discard (persists across Bluetooth `resetting`)
- **iOS oversized advertisement.** Now measured and rejected with `ERR_ADVERTISE` (was silently truncated/overflowed)
- **Attribute value bounds.** App-set values now bounded at `MAX_ATTRIBUTE_VALUE_LENGTH` (512); previously unbounded
- **Android config array errors.** Malformed entries now rejected (were silently dropped)
- **`createServer` undefined services.** Non-array now rejected (was coerced to `[]`)
- **Service/characteristic array requirement.** `characteristics` now required for each service (was coerced)
- **`sendNotification` option validation.** Unknown keys now rejected; `requireSubscription` type-checked
- **Android ordinary teardown.** No longer emits `onServerPublicationFailed` for normal stop
- **Cancelled `createServer` advertisement.** Compensating stop now skips epoch bump, preventing phantom advertisements
- **Android notification queue retry.** Busy error now re-queues entry (not fails all queued sends)
- **Android handler teardown.** Waiters now released via main looper (not local handler that may be quit)
- **Android service registration race.** Round check, queue read, and state update now atomic under lock
- **Queued write size Android.** Now rejected whole if assembled value exceeds 512 octets
- **iOS momentary resetting.** `removeAllServices` now called before re-adding (prevents "already added" errors)
- **iOS short timeout.** Timer now armed when advertisement reaches air (not when start issued)
- **iOS config array parse.** Type cast `as? [[String: Any]]` now validates each element
- **Android superseded start.** Timeout/rename only apply while current call owns radio
- **Build script watch mode.** `prepare` and `prepublishOnly` now use `scripts/build-package.js` (not tsc watcher)
- **Config plugin unknown options.** Now rejected (`requireBluetoothLEHardware` typo no longer silently ignored)
- **Android initial adapter state.** Now reported on server open and central connect (was only on change)
- **Android 512-byte notification.** Now bounded correctly; dispatch reports refusal (not exception escape)
- **Android stop racing start.** Callback claimed before state publish; re-test after publish
- **Android adapter rename restore.** Now restored on all exits (timeout and pre-rename failure)
- **Android empty queue teardown.** No longer misread as publication success; every teardown discards round
- **Generation ownership.** Claimed at hand-over (not entry); released in `finally` for validation-failed calls
- **Overlapping create/advertise.** `startAdvertising` during `createServer` now cancelled when new database created
- **iOS negative offset.** Now rejected with `ERR_RESPONSE_OFFSET` (was silently trimmed)
- **iOS notification queue per-central.** Now 35-second timeout per central (was shared across whole peripheral)
- **Android atomic queue lookup.** Queue now registered atomically; orphaned entry settled (not abandoned)
- **Android manager cleanup.** Listener now properly cleared on stop (no late events on torn-down app context)
- **Android server leak.** Existing server now closed before opening new one
- **Android UI thread hang.** Receiver now registered with manager's looper (not main thread)
- **Android manifest Bluetooth filter.** Now explicitly `required="false"` (was filtering apps on Google Play)
- **UUID and timeout validation.** Both platforms now validate natively (iOS timeout as NSNumber, Android UUID form)
- **iOS stale advertisement callback.** Previous ad now stopped before replacement starts
- **iOS configuration key validation.** Misspelled keys now rejected (were silently ignored)
- **Concurrent stop/create.** Order now recorded on JavaScript thread; abandoned create rejects and stops server
- **Android reliable write delegation.** Only one attribute per batch marked `responseNeeded` (was all delegated attrs)
- **Post-stop response code.** Both platforms now report `REQUEST_NOT_FOUND` (was iOS `REQUEST_NOT_FOUND`, Android `ERR_NO_SERVER`)
- **Notification timeout racing.** Timeout now armed after send accepted (not before); expires after 35 s
- **Android open exception handling.** Failed `registerReceiver` or `openGattServer` now clears completion bound
- **Android adapter rename permanence.** Now restored on timeout and failed start (was only on success/stop)
- **iOS MTU-dependent sends.** Now re-samples current `CBCentral` each send (not stale ref from subscribe)
- **iOS delegate method naming.** Fixed selector names `peripheralManagerDidStartAdvertising` and `peripheralManagerIsReady(toUpdateSubscribers:)` (were misspelled)
- **iOS value parsing.** Characteristic/descriptor `value` now parsed correctly (was `Double` cast to `Int`, failing)
- **Android stopServer pending requests.** Now answered with `ATT_ERROR_UNLIKELY_ERROR` (was dropped)
- **Cancelled advertise compensation.** Only most recent start now compensates (earlier abandon won't stop newer)
- **iOS createServer/stopServer race.** Now guarded like advertising with epoch (stops can't be overtaken)
- **iOS registration timeout unpublish.** `removeAllServices` now called (was waiting for impossible acks)
- **Android concurrent server opens.** Now serialized with per-round callback identity (was opening two)
- **Publication state race.** Timeout now atomic with state check (was separate steps)
- **Android in-flight notification timeout.** Now bounded (was unbounded, wedging queue)
- **Partially delegated execute.** Plain descriptor values now withheld pending response (were committed early)
- **Concurrent CCCD writes.** Subscription state now sampled under update (not outside)
- **Stop overtaking start.** Stop now re-issued if start-in-flight at stop time (carries order across bridge)
- **iOS server stop unanswered requests.** Now answered with `ATT_ERROR_UNLIKELY_ERROR` before unpublish (was dropped)
- **iOS inferred disconnect.** Unsubscribe no longer cancels unrelated pending requests; expires via `requestTimeoutMs`
- **Unbounded registration round.** Now bounded at 30 s; both platforms
- **Android descriptor read blob.** Now sliced correctly; past-end offset rejected with `ATT_ERROR_INVALID_OFFSET`
- **Delegate characteristic lookup.** Now service-specific (was falling back to UUID-only map)
- **iOS long write assembly.** Decided by offset presence (not fragment count); preserves stale bytes like Android
- **Error code specificity.** All failures now report most specific code (iOS/Android no longer report different codes for same fault)
- **Android auto-ack write.** Value now stored immediately (was stale on iOS, not updated on Android)
- **iOS offset-based write.** Fragment now spliced at offset (was overwriting whole value)
- **Malformed UUID on iOS.** Now pre-validated before `CBUUID(string:)` (avoids uncatchable exception)
- **iOS cached value writability.** Characteristic with `value` now published dynamic (was read-only); initial value served from module cache
- **Android service registration.** Now serialized (was concurrent despite docs saying not to)
- **iOS immediate advertise after create.** Now waits for definitive adapter state (not sampling `unknown`)
- **Android advertise after create.** Now parks waiting for database (was rejecting with `ERR_NO_SERVER`)
- **Bluetooth power cycle.** Configuration now retained and re-published on next `poweredOn`; was lost
- **Offset past end read.** Now answered with ATT `0x07` (was delegated to listener); offset=length is valid (empty value)
- **Cross-device sendResponse.** Request owner now validated (one device can't answer another's request)
- **Unanswered delegated request.** Now expires via `requestTimeoutMs` (was retained forever)
- **Unsubscribed notification.** Now rejected (was resolving as success)
- **Android overlapping sends.** Now queued per device (was dropping while in-flight)
- **`onNotificationSent` characteristic.** Now from queue entry (was arbitrary)
- **iOS transmit queue resend.** Now only refused payloads in order (was resending all cached values)
- **iOS `updateCharacteristicValue` threading.** Now runs on main queue (was mutating from JS thread)
- **iOS `stopServer` services.** Now all unpublished via `removeAllServices`; delegate cleared
- **Android prepared write flag.** Now respected; fragments buffered and applied on execute
- **Android concurrent execute.** Read-modify-write now atomic; reads take same monitor
- **Android unavailable context.** Now rejects with `ERR_NO_CONTEXT` (was treating as permission granted)
- **iOS multi-service UUID.** Values/subscriptions now keyed by service+characteristic (was UUID-only)
- **Batch write delegation.** Now per-characteristic (was decided once for whole batch); batch atomicity preserved
- **Duplicate service/characteristic UUID.** Now rejected on both platforms; different services can share UUID
- **sendNotification error check order.** Now checks deviceId before connection (Android now matches iOS)
- **Write Without Response semantics.** Documented that iOS can't distinguish from Write With Response
- **Unpublished advertise.** Now rejects with `ERR_NO_SERVER` (was exposing half-built database)
- **Android stale advertisements.** Superseded ad now stopped before start (was leaving multiple ads on air)
- **Android stop/restart flag.** Flag no longer revived if adapter off mid-restart
- **Partly delegated batch race.** Held values now committed only if unchanged from assembly time (not unconditionally)
- **Offline createServer.** Now retains config; listener/receiver installed before check (publishes on power-on)
- **Duplicate CCCD keying.** Now service+characteristic (was UUID-only on Android), preventing wrong-service sends
- **Bluetooth power cycle persistence.** Attribute values now carried across rebuild (was reverted to config)

### Removed

- **Breaking: `GATT_FAILURE`.** Was `257` (GATT status not ATT code); use `ATT_ERROR_*` constants instead (e.g., `ATT_ERROR_UNLIKELY_ERROR` for general failures)
- **Breaking: `MTU_SMALL` error code.** Use `PAYLOAD_EXCEEDS_MTU` instead; oversized `sendResponse` no longer an error
- **Breaking: `ACCESS_FINE_LOCATION` permission.** Removed from manifest (peripheral doesn't scan); apps that scan must declare it themselves
- **`react` and `react-native` peer dependencies.** Neither is imported by the package; `expo >=57.0.0` already states the runtime

## [0.1.0] - 2026-05-23

### Added

- GATT server with configurable services and characteristics
- BLE advertising with localName, service UUIDs, TX power, connectable flag
- Event listeners for device connect/disconnect, read/write requests, notification delivery
- `sendNotification`, `sendResponse`, `updateCharacteristicValue` APIs
- MTU validation with descriptive error codes
- Runtime permission checks for Android 12+ (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`)
- Auto-response for cached read values
- CCCD descriptor auto-added for notify/indicate characteristics on Android

[0.2.0]: https://github.com/Dishuk/expo-gatt-server/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Dishuk/expo-gatt-server/releases/tag/v0.1.0
