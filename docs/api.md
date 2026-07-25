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
createServer(services: GattServiceConfig[], options?: CreateServerOptions): Promise<void>
```

Initialize the native BLE GATT server with the given services and characteristics.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `services` | `GattServiceConfig[]` | -- | Array of service definitions |
| `options.requestTimeoutMs` | `number` | `10000` | How long a delegated request may go unanswered before the module answers it itself |

**Throws** if Bluetooth permission is not granted (iOS: authorization check, Android: `BLUETOOTH_CONNECT` runtime permission).

Must be called before `startAdvertising`. Call `stopServer` before calling `createServer` again.

#### Unanswered requests

A characteristic configured with `delegate.read` or `delegate.write` hands its ATT request to
JavaScript and waits for [`sendResponse`](#sendresponse). If the handler never responds -- it threw,
it awaited something that never settled, the listener was removed -- the request would otherwise be
retained forever and the central would sit blocked on it.

The Bluetooth Core Specification gives the central 30 seconds: "a transaction not completed within 30
seconds shall time out. Such a transaction shall be considered to have failed [...] No more Attribute
Protocol requests, commands, indications or notifications shall be sent to the target device on this
ATT bearer" -- recovering costs a whole new bearer (Vol 3, Part F, Section 3.3.3). One unanswered
request therefore poisons every later read, write **and notification** on that connection.

So the module answers first. After `requestTimeoutMs` an unanswered request is completed with
`ATT_ERROR_UNLIKELY_ERROR` (`0x0e`) and forgotten. The default of 10000 ms leaves the central 20
seconds of margin, so it receives a real error response and the bearer stays usable. A later
`sendResponse` for that request rejects with `REQUEST_NOT_FOUND`.

Raise it for handlers that legitimately take longer, but it must stay below the 30000 ms transaction
timeout -- past that the central has already given up, so a response could never arrive in time.
Values from `30000` upward are rejected. Set `0` to disable the timeout and restore the unbounded
wait.

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
| `config.includeTxPowerLevel` | `boolean` | `false` | Include TX power level. Android only |
| `config.connectable` | `boolean` | `true` | Accept incoming connections. `false` is Android only |
| `config.mode` | `AdvertisingMode` | `'lowPower'` | Discovery latency against battery. Android only |
| `config.txPowerLevel` | `AdvertisingTxPower` | `'medium'` | Radio transmit power, i.e. range. Android only |
| `config.timeoutMs` | `number` | `0` | Stop advertising by itself after this many ms; `0` means no limit |
| `config.manufacturerData` | `ManufacturerDataEntry[]` | `[]` | Manufacturer Specific Data. Android only |
| `config.serviceData` | `ServiceDataEntry[]` | `[]` | Service Data. Android only |
| `config.android.includeDeviceName` | `boolean` | `localName !== undefined` | Include the device's own Bluetooth name in the scan response |
| `config.android.setAdapterName` | `boolean` | `false` | Rename the device's Bluetooth adapter to `localName` |

**Throws** if Bluetooth is not powered on (iOS) or `BLUETOOTH_ADVERTISE` permission is missing (Android). `android.setAdapterName` additionally requires `BLUETOOTH_CONNECT` on API 31+, and rejects with `ERR_ADVERTISE` when no `localName` is supplied.

#### Platform support for advertising options

`CBPeripheralManager.startAdvertising` supports exactly two advertisement keys in the peripheral role -- `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey` -- and silently ignores everything else. This module does not pass that silence on:

| Option | Android | iOS |
|--------|---------|-----|
| `localName` | Adapter name only -- see below | Native |
| `serviceUuids` | Native | Native |
| `timeoutMs` | Native (`AdvertiseSettings.setTimeout`) | **Emulated** by a module timer that calls `stopAdvertising` |
| `mode`, `txPowerLevel`, `includeTxPowerLevel` | Native | **Ignored, with a `console.warn`** |
| `manufacturerData`, `serviceData` | Native | **Rejected** with `ERR_UNSUPPORTED` |
| `connectable: false` | Native | **Rejected** with `ERR_UNSUPPORTED` |

The split is deliberate. `mode`, `txPowerLevel` and `includeTxPowerLevel` are hints about radio behaviour: the advertisement still means the same thing and a peer still finds it, so rejecting them would force every cross-platform app to branch on `Platform.OS` purely to tune Android battery use. `manufacturerData`, `serviceData` and `connectable: false` change what a scanner *observes* -- a central filtering on manufacturer data would never find a peripheral whose manufacturer data was quietly dropped -- so they fail loudly instead.

`mode` and `txPowerLevel` now default to Android's own platform defaults (`ADVERTISE_MODE_LOW_POWER`, `ADVERTISE_TX_POWER_MEDIUM`). Earlier versions hardcoded `ADVERTISE_MODE_LOW_LATENCY`, which the platform documents as having "the highest power consumption" and as something that "should not be used for continuous background advertising". Pass `mode: 'lowLatency'` to get the old behaviour back.

`serviceUuids`, `manufacturerData` and `serviceData` all go in the advertisement itself, where a passive scanner sees them, and share its 31-byte budget; the device name and TX power go in the scan response so they do not compete for it. An over-budget advertisement rejects with `ERR_ADVERTISE` ("Advertise data too large").

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

#### `confirm`: notification or indication

An indication is acknowledged -- the central must reply with an `ATT_HANDLE_VALUE_CFM` and "no
further indications to this client shall occur until the confirmation has been received by the
server" (Vol 3, Part F, Section 3.4.7.2). A notification is fire-and-forget (Section 3.4.7.1).

The characteristic must declare the property that matches, or the call rejects with
`ERR_CONFIRM_UNSUPPORTED`:

| `confirm` | Required `properties` entry |
|-----------|------------------------------|
| `true` | `'indicate'` |
| `false` | `'notify'` |

The specification permits each transmission only when its property bit is set (Vol 3, Part G,
Table 3.5), and a client may set the matching Client Characteristic Configuration bit "only [...] if
the characteristic's properties have the [notify/indicate] bit set" (Table 3.11) -- so a mismatch is
something no client could legitimately have asked for. Neither platform checks this itself:
`BluetoothGattServer.notifyCharacteristicChanged` sends whatever `confirm` says, and CoreBluetooth
has no `confirm` parameter to check.

> **iOS never receives the flag.**
> [`updateValue(_:for:onSubscribedCentrals:)`](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/updatevalue(_:for:onsubscribedcentrals:))
> takes no confirm parameter; CoreBluetooth derives notification versus indication from the declared
> properties alone. Because the property check above is enforced on both platforms, a characteristic
> declaring exactly one of `notify` and `indicate` behaves identically either side. A characteristic
> declaring **both** is the one case iOS cannot honour -- Android sends what `confirm` asks for,
> iOS sends whatever CoreBluetooth chooses. Declare only the property you intend to use if the
> distinction matters.

#### `requireSubscription`

Rejects with `ERR_NO_SUBSCRIBER` when the target device has not enabled the *specific* transmission
`confirm` selects. Wait for
[`addCharacteristicSubscribedListener`](#addcharacteristicsubscribedlistener) before streaming.

On Android this is checked against that device's own Client Characteristic Configuration bits: bit 0
enables notifications, bit 1 enables indications (Vol 3, Part G, Table 3.11), and "when a bit is set,
that action shall be enabled, otherwise it will not be used" (Section 3.3.3.3). A client that enabled
only indications is therefore no longer sent a notification. Passing `requireSubscription: false`
sends anyway, since the platform transmits without consulting the descriptor.

iOS cannot make the distinction at all -- CoreBluetooth reports a subscription without saying which
bit the central set -- so it is checked as "subscribed at all". `false` changes nothing there:
`updateValue(_:for:onSubscribedCentrals:)` "ignores any centrals that haven't subscribed to the
characteristic's value", so there is no send to force and `ERR_NO_SUBSCRIBER` is still reported.

It never relaxes the `confirm` property check, which applies on both platforms regardless.

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
): Promise<void>
```

Update the cached value of a characteristic. Subsequent read requests from centrals are auto-responded by the native layer using this value.

Does **not** send a notification. Use `sendNotification` to push updates to subscribed centrals.

**Rejects** with `ERR_CHARACTERISTIC_NOT_FOUND` when the pair of UUIDs names nothing in the published
GATT database, and with `ERR_NO_SERVER` when no server exists. On iOS it also rejects with
`ERR_BLUETOOTH` while Bluetooth is not powered on, because the published database only exists then --
"the powered off state clears the local database".

Both failures used to be silent no-ops on both platforms, so a mistyped UUID looked identical to a
successful update while the characteristic went on serving its old value.

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

Pending work is settled rather than abandoned: an unresolved `createServer` rejects with
`ERR_NO_SERVER`, queued notifications reject, unanswered delegated requests are dropped, and
advertising stops (restoring the adapter name on Android if `android.setAdapterName` changed it).
Both platforms unpublish the whole database and stop listening for adapter state, so a later
`createServer` starts from an empty GATT database rather than colliding with the previous one.

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

#### Long writes and reliable writes

A central writing a value longer than one `ATT_WRITE_REQ` can carry uses the queued-write procedure:
a run of `ATT_PREPARE_WRITE_REQ` PDUs each carrying a fragment and its offset, then a single
`ATT_EXECUTE_WRITE_REQ` that applies or cancels the lot (Vol 3, Part F, Section 3.4.6; Vol 3, Part G,
Sections 4.9.4 and 4.9.5).

On **Android** the module buffers the fragments per device, echoing each one back in its prepare
response as the specification requires, and applies nothing until the execute arrives -- "the server
shall not change the value of the attribute until an `ATT_EXECUTE_WRITE_REQ` PDU is received". On
execute:

- **Flag `0x01`** -- fragments are assembled onto each attribute's current value in the order they
  were received, then applied atomically. One event per attribute is emitted with the **reassembled**
  value and `offset: 0`, rather than one per fragment.
- **Flag `0x00`** -- everything queued is discarded and nothing is applied or emitted.
- A fragment starting past the end of its attribute fails the whole execute with
  `ATT_ERROR_INVALID_OFFSET` and discards the queue.
- More than 64 queued fragments are refused with `ATT_ERROR_PREPARE_QUEUE_FULL`; the already-queued
  fragments survive, as the specification requires.
- The queue is per device and is dropped when that device disconnects, when Bluetooth is turned off,
  and on `stopServer`.

If the characteristic is configured with `delegate.write`, the execute is what waits for
`sendResponse` -- a single `requestId` covering the whole atomic operation, exactly as an iOS write
batch does. The individual prepare steps are never delegated; there is nothing meaningful to accept
or reject until the execute says the value is real.

> **iOS does not expose prepared writes at all.** `CBPeripheralManagerDelegate` declares twelve
> methods and none of them concerns prepare or execute; `CBATTRequest` carries only `central`,
> `characteristic`, `offset` and `value`, with no prepared-write flag. (`CBATTError` does define
> `prepareQueueFull`, but that is just the complete ATT error table -- there is no callback to return
> it from.) CoreBluetooth handles the procedure below the app layer and surfaces whatever it decides
> to surface through `didReceiveWriteRequests`, so there is nothing for the module to buffer and
> nothing to configure. Long writes to an iOS peripheral work, but the fragmentation is not
> observable and the execute cannot be rejected.

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
  mode?: AdvertisingMode;
  txPowerLevel?: AdvertisingTxPower;
  timeoutMs?: number;
  manufacturerData?: ManufacturerDataEntry[];
  serviceData?: ServiceDataEntry[];
  android?: AndroidAdvertiseOptions;
}
```

### AdvertisingMode

```typescript
type AdvertisingMode = 'lowPower' | 'balanced' | 'lowLatency';
```

Platform-neutral names for `AdvertiseSettings.ADVERTISE_MODE_*`, which Android implements as advertising intervals of 1 s, 250 ms and 100 ms. Ignored on iOS.

### AdvertisingTxPower

```typescript
type AdvertisingTxPower = 'ultraLow' | 'low' | 'medium' | 'high';
```

Platform-neutral names for `AdvertiseSettings.ADVERTISE_TX_POWER_*`. Ignored on iOS.

### ManufacturerDataEntry

```typescript
interface ManufacturerDataEntry {
  companyId: number;
  data: number[];
}
```

`companyId` is the 16-bit Bluetooth SIG Company Identifier; `0xFFFF` is reserved for development and testing.

### ServiceDataEntry

```typescript
interface ServiceDataEntry {
  uuid: string;
  data: number[];
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
| `ERR_UPDATE_VALUE` | An `updateCharacteristicValue` argument was rejected by the native layer. |
| `ERR_NO_SUBSCRIBER` | The device has not enabled the transmission `confirm` selects on the characteristic. |
| `ERR_CONFIRM_UNSUPPORTED` | `confirm` asks for a transmission the characteristic does not declare the property for -- `indicate` for `true`, `notify` for `false`. |
