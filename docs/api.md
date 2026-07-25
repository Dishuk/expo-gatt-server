# API Reference

Complete reference for all exported functions, types, events, and constants.

- [Functions](#functions)
  - [createServer](#createserver)
  - [startAdvertising](#startadvertising)
  - [stopAdvertising](#stopadvertising)
  - [sendNotification](#sendnotification)
  - [sendResponse](#sendresponse)
  - [updateCharacteristicValue](#updatecharacteristicvalue)
  - [getMtu](#getmtu)
  - [stopServer](#stopserver)
- [Event Listeners](#event-listeners)
  - [addDeviceConnectedListener](#adddeviceconnectedlistener)
  - [addDeviceDisconnectedListener](#adddevicedisconnectedlistener)
  - [addCharacteristicReadRequestListener](#addcharacteristicreadrequestlistener)
  - [addCharacteristicWriteRequestListener](#addcharacteristicwriterequestlistener)
  - [addNotificationSentListener](#addnotificationsentlistener)
  - [addMtuChangedListener](#addmtuchangedlistener)
  - [addCharacteristicSubscribedListener](#addcharacteristicsubscribedlistener)
  - [addCharacteristicUnsubscribedListener](#addcharacteristicunsubscribedlistener)
- [Types](#types)
- [Constants](#constants)
- [Error Codes](#error-codes)

## Functions

### createServer

```typescript
createServer(services: GattServiceConfig[]): Promise<void>
```

Initialize the native BLE GATT server with the given services and characteristics.

| Parameter | Type | Description |
|-----------|------|-------------|
| `services` | `GattServiceConfig[]` | Array of service definitions |

**Throws** if Bluetooth permission is not granted (iOS: authorization check, Android: `BLUETOOTH_CONNECT` runtime permission).

Must be called before `startAdvertising`. Call `stopServer` before calling `createServer` again.

---

### startAdvertising

```typescript
startAdvertising(config?: AdvertiseConfig): Promise<void>
```

Begin BLE advertisement. The device becomes visible to nearby scanners.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `config.localName` | `string` | -- | Local name to advertise. **iOS only** -- see below |
| `config.serviceUuids` | `string[]` | -- | Service UUIDs to advertise |
| `config.includeTxPowerLevel` | `boolean` | `false` | Include TX power level |
| `config.connectable` | `boolean` | `true` | Accept incoming connections |
| `config.android.includeDeviceName` | `boolean` | `localName !== undefined` | Include the device's own Bluetooth name in the scan response |
| `config.android.setAdapterName` | `boolean` | `false` | Rename the device's Bluetooth adapter to `localName` |

**Throws** if Bluetooth is not powered on (iOS) or `BLUETOOTH_ADVERTISE` permission is missing (Android). `android.setAdapterName` additionally requires `BLUETOOTH_CONNECT` on API 31+, and rejects with `ERR_ADVERTISE` when no `localName` is supplied.

#### The advertised local name

`localName` is honoured verbatim on iOS: it becomes `CBAdvertisementDataLocalNameKey`, one of the two advertisement keys `CBPeripheralManager.startAdvertising` supports.

**Android has no per-advertisement local name.** `AdvertiseData.Builder` exposes only `setIncludeDeviceName(boolean)`, and the name that flag includes is the *adapter's* -- `BluetoothLeAdvertiser` sizes the field from `BluetoothAdapter.getNameLengthForAdvertise()`. No public API writes an arbitrary Local Name into an advertisement. Android therefore has two options, neither of which advertises `localName` as given:

- **`android.includeDeviceName`** (the default whenever `localName` is set) advertises the name the device already has, in the scan response. Nothing is mutated.
- **`android.setAdapterName`** renames the adapter to `localName` so scanners see the requested string. This changes the phone's **system-wide** Bluetooth name -- visible in the device's own Bluetooth settings and to every peer, over Classic as well as LE. The module records the previous name and restores it on `stopAdvertising`, `stopServer`, or module destruction, but restoration is best-effort: `BluetoothAdapter.setName` fails while the adapter is off, and a process killed while advertising never runs it. Prefer `includeDeviceName` unless the exact advertised name genuinely matters.

Earlier versions renamed the adapter unconditionally whenever `localName` was set, and never restored it.

---

### stopAdvertising

```typescript
stopAdvertising(): void
```

Stop BLE advertisement. Does not disconnect existing connections or remove services.

---

### sendNotification

```typescript
sendNotification(
  deviceId: string,
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
  confirm?: boolean,
  options?: SendNotificationOptions,
): Promise<void>
```

Send a notification or indication to a connected central.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `deviceId` | `string` | -- | Target device identifier |
| `serviceUuid` | `string` | -- | Service containing the characteristic |
| `characteristicUuid` | `string` | -- | Characteristic to update |
| `value` | `number[]` | -- | Byte array payload |
| `confirm` | `boolean` | `false` | `true` for indication (acknowledged), `false` for notification |
| `options.requireSubscription` | `boolean` | `true` | Refuse the send when the device has not subscribed |

Rejects with `ERR_NO_SUBSCRIBER` when the target device has not enabled notifications or
indications on the characteristic. Wait for
[`addCharacteristicSubscribedListener`](#addcharacteristicsubscribedlistener) before streaming.

Passing `requireSubscription: false` sends anyway on Android, where the platform transmits without
consulting the Client Characteristic Configuration descriptor. It changes nothing on iOS:
`updateValue(_:for:onSubscribedCentrals:)` "ignores any centrals that haven't subscribed to the
characteristic's value", so there is no send to force and `ERR_NO_SUBSCRIBER` is still reported.

The mirrored characteristic value is updated whether or not the notification could be sent, so a
subsequent read still serves the latest value.

The returned promise resolves once the platform reports the notification as delivered, not when the
call is handed to the Bluetooth stack. Calls made while an earlier notification for the same device
is still in flight are queued in order and sent as the link drains, so awaiting the promise paces a
stream against the connection instead of overrunning it.

> **Android:** the platform allows one outstanding notification per device -- "when multiple
> notifications are to be sent, an application must wait for this callback to be received before
> sending additional notifications"
> ([`onNotificationSent`](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onNotificationSent(android.bluetooth.BluetoothDevice,%20int))).
> Sends beyond that used to be dropped by the stack while the promise still resolved.

> **iOS:** a payload CoreBluetooth's transmit queue cannot take is held and resent when
> [`peripheralManagerIsReady(toUpdateSubscribers:)`](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanagerdelegate/peripheralmanagerisready(toupdatesubscribers:))
> reports space. Only the refused payloads are resent, in order -- an unrelated central never
> receives an unsolicited update because another central's send was throttled.

**Throws** `PAYLOAD_EXCEEDS_MTU` when `value` is longer than the link can carry in one notification, checked **before** anything is transmitted -- nothing is sent and the payload is not truncated. Size payloads against [`getMtu`](#getmtu)`.maxNotificationPayload` to avoid it. Also throws `ERR_NOTIFY` when the stack refuses or fails the send, `ERR_NOTIFY_QUEUE_FULL` when too many sends are already waiting for the same device, `ERR_DEVICE_DISCONNECTED` when the central is not connected or goes away before a queued notification is delivered, and `ERR_NO_SUBSCRIBER` when it has not enabled notifications or indications on the characteristic.

---

### sendResponse

```typescript
sendResponse(
  deviceId: string,
  requestId: number,
  status: number,
  offset: number,
  value: number[],
): Promise<void>
```

Respond to a characteristic read request forwarded from the native layer.

| Parameter | Type | Description |
|-----------|------|-------------|
| `deviceId` | `string` | Requesting device identifier |
| `requestId` | `number` | Request ID from the read event |
| `status` | `number` | `GATT_SUCCESS` or an `ATT_ERROR_*` code |
| `offset` | `number` | The offset within the attribute at which `value` begins |
| `value` | `number[]` | Response byte array, starting at `offset` |

`offset` says where `value` begins within the attribute, and the response is rebased onto the offset
the request actually asked for. So both of these are correct and equivalent, on both platforms:

```typescript
// Pass the whole value and let the module take the part the request asked for.
await sendResponse(deviceId, requestId, GATT_SUCCESS, 0, wholeValue);

// Or slice it yourself and say where the slice starts.
await sendResponse(deviceId, requestId, GATT_SUCCESS, event.offset, wholeValue.slice(event.offset));
```

**Throws** `REQUEST_NOT_FOUND` if the request ID is invalid or already responded to,
`REQUEST_DEVICE_MISMATCH` if the request belongs to a different device than `deviceId`, and
`ERR_RESPONSE_OFFSET` if `offset` is past the offset the request asked for -- which would leave the
requested bytes missing from the response.

`value` is **not** size-checked against the MTU. An `ATT_READ_RSP` carries at most `ATT_MTU - 1`
octets and the central finishes a longer value with a Read Blob request, which arrives as another
read request bearing an offset -- so answering with more than fits is normal ATT, not an error.

---

### updateCharacteristicValue

```typescript
updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
): void
```

Update the cached value of a characteristic. Subsequent read requests from centrals are auto-responded by the native layer using this value.

Does **not** send a notification. Use `sendNotification` to push updates to subscribed centrals.

---

### getMtu

```typescript
getMtu(deviceId: string): Promise<DeviceMtu>
```

Read the current ATT MTU for a connected device, so payloads can be sized before they are sent.

The unit is the **ATT MTU in octets** -- the same thing the Bluetooth Core Specification and the
Android platform call "MTU". `maxNotificationPayload` is the number to size a `sendNotification`
payload against; it is `mtu - 3`, the maximum Attribute Value length of an `ATT_HANDLE_VALUE_NTF`
PDU.

A device that has not negotiated an MTU reports the specification default of `23` rather than
failing, because that default is what the link actually carries until a negotiation happens.

**Throws** `ERR_DEVICE_DISCONNECTED` when the device is not connected, `ERR_NO_SERVER` when no
server exists.

| Platform | `mtu` | `maxNotificationPayload` |
|----------|-------|--------------------------|
| Android | Exact, from `BluetoothGattServerCallback.onMtuChanged` | Derived as `mtu - 3` |
| iOS | Derived as `maximumUpdateValueLength + 3` | Exact, from `CBCentral.maximumUpdateValueLength` |

CoreBluetooth exposes only a payload length, never an MTU, so on iOS `mtu` is reconstructed by
adding the three header octets back. Apple does not document that identity, so prefer
`maxNotificationPayload` on iOS where the figure is exact.

---

### stopServer

```typescript
stopServer(): void
```

Shut down the GATT server. Removes all services, disconnects peripherals, and releases native resources. Call this in cleanup or when done with BLE.

## Event Listeners

All listeners return a `Subscription` object with a `.remove()` method. Call `.remove()` to unsubscribe.

### addDeviceConnectedListener

```typescript
addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): Subscription
```

Fired when a central connects to the server.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier (UUID on iOS, MAC address on Android) |
| `event.name` | `string?` | Device name, if available |

> **iOS behavior:** The connected event fires on first characteristic subscription (not on raw connection), because CoreBluetooth's peripheral manager API does not expose a connection-level callback.

---

### addDeviceDisconnectedListener

```typescript
addDeviceDisconnectedListener(
  listener: (event: DeviceDisconnectedEvent) => void,
): Subscription
```

Fired when a central disconnects.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier |

---

### addCharacteristicReadRequestListener

```typescript
addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
): Subscription
```

Fired when a central reads a characteristic that has no cached value. Respond with `sendResponse`.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Requesting device |
| `event.requestId` | `number` | Use in `sendResponse` |
| `event.serviceUuid` | `string` | Service UUID |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Read offset |

> **Note:** If the characteristic has a cached value (from `value` in config or `updateCharacteristicValue`), the native layer auto-responds and this listener is not called.

> **Note:** A read whose offset is past the end of the cached value is answered directly with ATT
> error `0x07` "Invalid Offset" (Core Specification, Vol 3, Part F, Section 3.4.1.1) and does not
> reach this listener. An offset equal to the value's length is in range and answers with an empty
> value, as the specification intends for a Read Blob that has consumed the whole attribute.

---

### addCharacteristicWriteRequestListener

```typescript
addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
): Subscription
```

Fired when a central writes to a characteristic.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Writing device |
| `event.requestId` | `number` | Request identifier |
| `event.serviceUuid` | `string` | Service UUID |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Write offset |
| `event.value` | `number[]` | Written byte array |
| `event.responseNeeded` | `boolean` | Whether the central expects an acknowledgment |

> **Android:** Write responses are auto-sent by the native layer when `responseNeeded` is true.

---

### addNotificationSentListener

```typescript
addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
): Subscription
```

Fired after a notification or indication is delivered (or fails).

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Target device |
| `event.characteristicUuid` | `string` | Notified characteristic |
| `event.status` | `number` | `0` for success |

`characteristicUuid` always identifies the characteristic this particular notification carried, so
notifying several characteristics, or several devices, reports each one correctly.

---

### addMtuChangedListener

```typescript
addMtuChangedListener(
  listener: (event: MtuChangedEvent) => void,
): Subscription
```

Fired when a connection's MTU changes, or when it is observed for the first time. The event carries
the same fields as [`getMtu`](#getmtu).

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | The device whose MTU changed |
| `event.mtu` | `number` | ATT MTU in octets |
| `event.maxNotificationPayload` | `number` | `mtu - 3`; size `sendNotification` payloads against this |

Android delivers this from `BluetoothGattServerCallback.onMtuChanged`, as the change happens. iOS
has no MTU callback at all, so the value is sampled whenever the central produces ATT activity -- a
subscribe, read or write -- and the event fires when it differs from the value last seen. On iOS a
change therefore surfaces at the next activity rather than the moment it happens, and the first
event for a device arrives alongside `onDeviceConnected`.

---

### addCharacteristicSubscribedListener

```typescript
addCharacteristicSubscribedListener(
  listener: (event: CharacteristicSubscribedEvent) => void,
): Subscription
```

Fired when a central enables notifications or indications on a characteristic. This is the signal
to start streaming -- before it arrives the central receives nothing.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Subscribing device |
| `event.serviceUuid` | `string` | Owning service, or `''` when the platform could not identify it |
| `event.characteristicUuid` | `string` | Subscribed characteristic |

On Android the event is driven by a write to the characteristic's Client Characteristic
Configuration descriptor (Bluetooth Core Specification, Vol 3, Part G, Section 3.3.3.3), tracked
per client as the specification requires. On iOS it comes from
[`peripheralManager(_:central:didSubscribeTo:)`](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanagerdelegate/peripheralmanager(_:central:didsubscribeto:)).

Whether the central asked for notifications or indications is not reported: CoreBluetooth does not
expose the distinction, so it cannot be surfaced consistently. Switching between the two does not
emit a further event -- the central stays subscribed throughout.

---

### addCharacteristicUnsubscribedListener

```typescript
addCharacteristicUnsubscribedListener(
  listener: (event: CharacteristicUnsubscribedEvent) => void,
): Subscription
```

Fired when a central stops receiving updates for a characteristic -- the signal to stop streaming.
Also emitted for every subscription a central still held when it disconnects, and when Bluetooth is
turned off and the published database is dropped.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Unsubscribing device |
| `event.serviceUuid` | `string` | Owning service, or `''` when the platform could not identify it |
| `event.characteristicUuid` | `string` | Characteristic no longer subscribed |

## Types

### GattServiceConfig

```typescript
interface GattServiceConfig {
  uuid: string;
  characteristics: GattCharacteristicConfig[];
}
```

### GattCharacteristicConfig

```typescript
interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  value?: number[];
}
```

### CharacteristicProperty

```typescript
type CharacteristicProperty = 'read' | 'write' | 'writeNoResponse' | 'notify' | 'indicate';
```

### CharacteristicPermission

```typescript
type CharacteristicPermission = 'readable' | 'writeable';
```

### AdvertiseConfig

```typescript
interface AdvertiseConfig {
  localName?: string;
  serviceUuids?: string[];
  includeTxPowerLevel?: boolean;
  connectable?: boolean;
  android?: AndroidAdvertiseOptions;
}
```

### AndroidAdvertiseOptions

```typescript
interface AndroidAdvertiseOptions {
  includeDeviceName?: boolean;
  setAdapterName?: boolean;
}
```

### DeviceConnectedEvent

```typescript
interface DeviceConnectedEvent {
  deviceId: string;
  name?: string;
}
```

### DeviceDisconnectedEvent

```typescript
interface DeviceDisconnectedEvent {
  deviceId: string;
}
```

### CharacteristicReadRequestEvent

```typescript
interface CharacteristicReadRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
}
```

### CharacteristicWriteRequestEvent

```typescript
interface CharacteristicWriteRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
  value: number[];
  responseNeeded: boolean;
}
```

### NotificationSentEvent

```typescript
interface NotificationSentEvent {
  deviceId: string;
  characteristicUuid: string;
  status: number;
}
```

### DeviceMtu / MtuChangedEvent

```typescript
interface DeviceMtu {
  deviceId: string;
  /** ATT MTU in octets, including the 3-octet ATT header. Defaults to 23 before negotiation. */
  mtu: number;
  /** Octets that fit in one notification or indication: `mtu - 3`. */
  maxNotificationPayload: number;
}

type MtuChangedEvent = DeviceMtu;
```

### CharacteristicSubscribedEvent / CharacteristicUnsubscribedEvent

```typescript
interface CharacteristicSubscribedEvent {
  deviceId: string;
  serviceUuid: string;
  characteristicUuid: string;
}
```

## Constants

An ATT error code is a single octet (Bluetooth Core Specification 5.4, Vol 3, Part F, Table 3.4).
`sendResponse` rejects anything outside `0`–`255`.

| Constant | Value | Description |
|----------|-------|-------------|
| `GATT_SUCCESS` | `0x00` | Operation completed successfully |
| `ATT_ERROR_INVALID_HANDLE` | `0x01` | The attribute handle given was not valid on this server |
| `ATT_ERROR_READ_NOT_PERMITTED` | `0x02` | The attribute cannot be read |
| `ATT_ERROR_WRITE_NOT_PERMITTED` | `0x03` | The attribute cannot be written |
| `ATT_ERROR_INVALID_PDU` | `0x04` | The attribute PDU was invalid |
| `ATT_ERROR_INSUFFICIENT_AUTHENTICATION` | `0x05` | Authentication is required first |
| `ATT_ERROR_REQUEST_NOT_SUPPORTED` | `0x06` | The server does not support the request |
| `ATT_ERROR_INVALID_OFFSET` | `0x07` | Offset was past the end of the attribute |
| `ATT_ERROR_INSUFFICIENT_AUTHORIZATION` | `0x08` | Authorization is required first |
| `ATT_ERROR_PREPARE_QUEUE_FULL` | `0x09` | Too many prepare writes have been queued |
| `ATT_ERROR_ATTRIBUTE_NOT_FOUND` | `0x0a` | No attribute found in the given handle range |
| `ATT_ERROR_ATTRIBUTE_NOT_LONG` | `0x0b` | The attribute cannot be read with a read blob request |
| `ATT_ERROR_INSUFFICIENT_ENCRYPTION_KEY_SIZE` | `0x0c` | The link's encryption key size is too short |
| `ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH` | `0x0d` | The value length is invalid for the operation |
| `ATT_ERROR_UNLIKELY_ERROR` | `0x0e` | The request could not be completed |
| `ATT_ERROR_INSUFFICIENT_ENCRYPTION` | `0x0f` | Encryption is required first |
| `ATT_ERROR_UNSUPPORTED_GROUP_TYPE` | `0x10` | The attribute type is not a supported grouping attribute |
| `ATT_ERROR_INSUFFICIENT_RESOURCES` | `0x11` | Insufficient resources to complete the request |

Codes above `0x11` are not exposed: iOS can only transmit what `CBATTError.Code` models, which
stops at `0x11`, so the specification's `0x12`, `0x13`, application (`0x80`–`0x9F`) and profile
(`0xE0`–`0xFF`) ranges have no iOS representation and would arrive as Unlikely Error.

## Error Codes

Errors thrown by `sendNotification` and `sendResponse` include a `code` property:

| Code | Description |
|------|-------------|
| `PAYLOAD_EXCEEDS_MTU` | A `sendNotification` payload is longer than one notification can carry (`mtu - 3`). Checked before transmitting, so nothing was sent. The message says when the link is still at the default ATT MTU of 23. |
| `REQUEST_NOT_FOUND` | The `requestId` does not match any pending read request. |
| `REQUEST_DEVICE_MISMATCH` | The `requestId` is pending, but for a different device than the `deviceId` supplied. |
| `ERR_RESPONSE_OFFSET` | The `offset` given to `sendResponse` is past the offset the request asked for, so the requested bytes would be missing. |
| `ERR_NOTIFY` | The Bluetooth stack refused the notification, or reported it as undelivered. |
| `ERR_NOTIFY_QUEUE_FULL` | Too many notifications are already queued for the device. Await earlier sends before queueing more. |
| `ERR_DEVICE_DISCONNECTED` | The central disconnected, or unsubscribed, before a queued notification could be delivered. |
| `ERR_CHARACTERISTIC_NOT_FOUND` | The characteristic is not part of the published GATT database. |
| `ERR_NO_SUBSCRIBER` | The device has not enabled notifications or indications on the characteristic. |
