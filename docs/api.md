# API Reference

Complete reference for all exported functions, types, events, and constants.

- [UUID forms](#uuid-forms)
- [Functions](#functions)
  - [isSupported](#issupported)
  - [createServer](#createserver)
  - [startAdvertising](#startadvertising)
  - [stopAdvertising](#stopadvertising)
  - [sendNotification](#sendnotification)
  - [sendResponse](#sendresponse)
  - [updateCharacteristicValue](#updatecharacteristicvalue)
  - [getBluetoothState](#getbluetoothstate)
  - [getMtu](#getmtu)
  - [getConnectedDevices](#getconnecteddevices)
  - [disconnectDevice](#disconnectdevice)
  - [isServerRunning](#isserverrunning)
  - [isAdvertising](#isadvertising)
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
  - [addBluetoothStateChangedListener](#addbluetoothstatechangedlistener)
- [Types](#types)
- [Constants](#constants)
- [Error Codes](#error-codes)

## UUID forms

Every UUID this API accepts may be written in any of the three forms the Bluetooth Core Specification
defines: 16-bit (4 hex digits, `'180D'`), 32-bit (8 hex digits, `'0000180D'`) or the hyphenated
128-bit form (`'0000180d-0000-1000-8000-00805f9b34fb'`). This covers service and characteristic
`uuid`, descriptor `uuid`, `AdvertiseConfig.serviceUuids`, `ServiceDataEntry.uuid`, and the
`serviceUuid` / `characteristicUuid` arguments of `sendNotification` and
`updateCharacteristicValue`.

Short forms are **expanded onto the Bluetooth Base UUID in the shared TypeScript layer**, before
either platform sees them. This exists because the two platforms disagreed: `CBUUID` "automatically
handles transformations of 16 and 32 bit UUIDs into 128 bit UUIDs", but Java's `UUID.fromString`
requires the 8-4-4-4-12 form, so `'180D'` was accepted on iOS and threw on Android.

The Core Specification defines the aliases arithmetically (Vol 3, Part B, Section 2.5.1):

```
Bluetooth_Base_UUID = 00000000-0000-1000-8000-00805F9B34FB

128_bit_value = 16_bit_value * 2^96 + Bluetooth_Base_UUID
128_bit_value = 32_bit_value * 2^96 + Bluetooth_Base_UUID
```

`2^96` places the value in the leading 32 bits in both cases -- a 16-bit alias being zero-extended to
32 bits first -- so the expansion is exactly "left-pad to eight hex digits, then append the base
UUID's remaining four groups". `'180D'` and `'0000180D'` therefore both become
`0000180d-0000-1000-8000-00805f9b34fb`.

**This costs nothing in an advertisement.** Android encodes advertised UUIDs with
`BluetoothUuid.uuidToBytes`, documented as returning "the shortest representation, a 16-bit, 32-bit or
128-bit UUID", and `BluetoothLeAdvertiser` sizes the 31-byte budget the same way -- so a normalised
16-bit alias still goes out as two octets, exactly as before.

### What events report

Event payloads always carry the **lowercase 128-bit form**, on both platforms, whatever spelling the
configuration used. The spelling the consumer passed is deliberately not echoed back:

- The specification requires the conversion for comparison anyway: "If two UUIDs of differing sizes
  are to be compared, the shorter UUID must be converted to the longer UUID format before comparison".
  One canonical spelling is what makes `event.characteristicUuid === MY_UUID` work at all.
- Preserving it would mean carrying a reverse map from normalised UUID back to original spelling on
  both platforms, and events also arrive for attributes that were never configured -- for which there
  is no original spelling to restore.

This also holds for UUIDs that come back out of the platform rather than from the configuration, which
takes a second normalisation on iOS: `CBUUID.uuidString` uppercases the 128-bit form and echoes a short
UUID back in the short form it was built from, while Java's `UUID.toString` is always lowercase 128-bit.
iOS therefore re-spells every UUID it emits.

`deviceId` is **not** affected. It is an opaque handle -- a MAC address on Android, a
`CBCentral.identifier` on iOS -- not a Bluetooth UUID, and it is passed straight back to `getMtu`,
`sendNotification`, `sendResponse` and `disconnectDevice`.

## Functions

### isSupported

```typescript
isSupported(): boolean
```

Whether the native module is present, and therefore whether anything else here can work.

**Importing this package never throws, whatever this returns.** The module is resolved with
`requireOptionalNativeModule`, which yields `null` instead of raising, so a bundle that only uses BLE
conditionally is safe to `import` from unconditionally.

`false` on web -- this package publishes a GATT server, which needs the peripheral role, and Web
Bluetooth implements only the central role -- and in any binary that does not contain the module,
Expo Go being the usual case, since it ships a fixed set of native modules.

Synchronous, so it is safe at module scope.

#### Behaviour when the native module is absent

Nothing crashes on an undefined property. Every export answers in the way that is true when there is
no Bluetooth peripheral support at all:

| Export | Behaviour |
|---|---|
| `createServer`, `startAdvertising`, `sendNotification`, `sendResponse`, `updateCharacteristicValue`, `getMtu`, `disconnectDevice` | Reject with a message naming the platform and the reason |
| `getBluetoothState` | Resolves to `'unsupported'`, which is exactly what that state already means |
| `getConnectedDevices` | Resolves to `[]` |
| `isServerRunning`, `isAdvertising` | Resolve to `false` |
| `stopAdvertising`, `stopServer` | Do nothing |
| every `add*Listener` | Returns a subscription whose `remove()` does nothing |

The teardown functions and the listener helpers deliberately do **not** throw. A listener registered
in an effect is paired with `remove()` in that effect's cleanup, and a `stopServer()` in the same
cleanup runs whether setup succeeded or not -- throwing there would turn one reported setup failure
into a crash on unmount. Nothing was ever started, so there is nothing for them to fail at.

---

### createServer

```typescript
createServer(services: GattServiceConfig[], options?: CreateServerOptions): Promise<void>
```

Initialize the native BLE GATT server with the given services and characteristics.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `services` | `GattServiceConfig[]` | -- | Array of service definitions |
| `options.requestTimeoutMs` | `number` | `10000` | How long a delegated request may go unanswered before the module answers it itself |

Must be called before `startAdvertising`. Calling it again **replaces** the existing server: the
previous one is stopped first, so an explicit `stopServer` in between is not required.

**The promise resolves only once every service is confirmed published**, so a resolved `createServer`
means the database really is there to advertise. On iOS that means waiting for CoreBluetooth to reach
`poweredOn` and acknowledge each service; on Android the services are registered strictly one at a
time, because `BluetoothGattServer.addService` documents "do not add another service before this
callback".

**Rejects** with:

| Code | When |
|---|---|
| `ERR_PERMISSION` | iOS: `CBManager.authorization` is denied or restricted. Android: `BLUETOOTH_CONNECT` is not granted (API 31+) |
| `ERR_NO_CONTEXT` | Android only: no React context is available, so the permission could not be checked |
| `ERR_UNSUPPORTED` | iOS only: the configuration asks for a property, permission or descriptor CoreBluetooth cannot express -- see the type tables below |
| `ERR_BLUETOOTH` | Bluetooth is off, or the device has no BLE support. Check [`getBluetoothState`](#getbluetoothstate) to tell which |
| `ERR_NO_SERVER` | [`stopServer`](#stopserver) ran, or Bluetooth went off, before the database finished publishing |
| `ERR_CREATE_SERVER` | A service failed to publish, or the configuration was malformed |

A failed registration leaves **no** usable database: `isServerRunning` stays `false` and
[`startAdvertising`](#startadvertising) rejects with `ERR_NO_SERVER`. On iOS the services that did
publish before the failure are unpublished again once the round's remaining callbacks have arrived --
`CBPeripheralManager` shares one GATT database per app, so leaving them there would collide with the
next `createServer`.

Invalid configuration is rejected in the shared TypeScript layer before either platform sees it, as a
plain `Error` rather than a coded one: a malformed UUID, a byte outside `0`--`255`, an unrecognised
service type, characteristic property or permission name, a `requestTimeoutMs` outside its range, a
descriptor declaring the Client Characteristic Configuration UUID, or a duplicate UUID.

#### Duplicate UUIDs

`sendNotification` and `updateCharacteristicValue` address an attribute by a **pair** of UUIDs, and both
platforms resolve that pair to exactly one attribute -- Android's `getService` and `getCharacteristic`
return the first match, iOS keeps the last service added. A configuration in which the pair names more
than one attribute is therefore rejected:

| Configuration | Verdict |
|---|---|
| Two services with the same UUID | **Rejected** |
| One service declaring the same characteristic UUID twice | **Rejected** |
| The same characteristic UUID in two *different* services | **Accepted** -- GATT permits it, and the pair of UUIDs still names one attribute |

UUIDs are compared after normalisation, so `'180d'` and `'0000180D-0000-1000-8000-00805F9B34FB'` count
as the same service. Both platforms repeat the check natively, since the native module is reachable
directly.

#### Delegating requests to JavaScript

By default the module answers every ATT request itself -- reads from the characteristic's cached
value, writes with a success response. Set
[`delegate`](#characteristicdelegateconfig) on a characteristic to hand its requests to JavaScript
instead, and answer them with [`sendResponse`](#sendresponse).

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
| `config.localName` | `string` | -- | Local name to advertise. Honoured verbatim on iOS only; **Android has no per-advertisement local name** -- see below |
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

Requires a **published** database, not merely a server object: `startAdvertising` rejects with
`ERR_NO_SERVER` before `createServer`, after a service failed to publish, and after Bluetooth went down
and took the database with it. Advertising a half-built or empty database would otherwise expose it to
scanners, which is worse than not advertising at all. [`isServerRunning`](#isserverrunning) reports the
same condition, so awaiting `createServer` is all that is normally needed.

**A publication still in flight is waited for**, on both platforms. Neither platform can report
readiness synchronously -- `CBPeripheralManager.state` is `unknown` until its first callback arrives and
the services it then publishes are acknowledged some callbacks later, and Android registers each service
through its own `onServiceAdded` -- so instead of sampling the state, the call parks until the database
is published. `startAdvertising` is therefore safe immediately after awaiting `createServer`, alongside
a `createServer` that has not resolved yet, and from a `poweredOn` event handler.

A parked call is always settled: a terminal Bluetooth state rejects with `ERR_BLUETOOTH`, or
`ERR_PERMISSION` when iOS reports Bluetooth as unauthorized; a publication that failed rejects with
`ERR_NO_SERVER`; and [`stopServer`](#stopserver) or Bluetooth going off while the call waits rejects it
rather than leaving it pending.

Calling `startAdvertising` again **replaces** the current advertisement on both platforms rather than
adding a second one, and settles the earlier call's promise with `ERR_ADVERTISE`.

**Rejects** with:

| Code | When |
|---|---|
| `ERR_NO_SERVER` | No server exists, a service failed to publish, or the server was stopped while the call waited for the database |
| `ERR_PERMISSION` | Android: `BLUETOOTH_ADVERTISE` is not granted, or `BLUETOOTH_CONNECT` is not granted while `android.setAdapterName` is set (API 31+). iOS: Bluetooth is unauthorized |
| `ERR_NO_CONTEXT` | Android only: no React context, so the permission could not be checked |
| `ERR_UNSUPPORTED` | iOS: `manufacturerData`, `serviceData` or `connectable: false` was supplied. Android: the adapter has no BLE advertising support, which no amount of retrying changes |
| `ERR_BLUETOOTH` | Bluetooth is off, or the device has no BLE support |
| `ERR_ADVERTISE` | The platform refused the advertisement -- data over the 31-byte budget, too many advertisers, already started, or `android.setAdapterName` without a `localName` |

An invalid `mode`, `txPowerLevel`, `timeoutMs`, `companyId` or byte value is rejected in the shared
TypeScript layer as a plain `Error`, before either platform sees it.

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

`mode` and `txPowerLevel` default to Android's own platform defaults (`ADVERTISE_MODE_LOW_POWER`, `ADVERTISE_TX_POWER_MEDIUM`). Pass `mode: 'lowLatency'` for the shortest discovery latency, which the platform documents as having "the highest power consumption" and as something that "should not be used for continuous background advertising".

`timeoutMs` is bounded at `180000` on both platforms -- the limit `AdvertiseSettings.Builder.setTimeout` enforces -- so one configuration behaves the same either side. On iOS it is **emulated** by a module timer that calls `stopAdvertising` when it fires, matching Android's behaviour of simply stopping with no error and no callback. Because it is a process-local timer, it only holds while the process is alive; `isAdvertising` goes `false` when it fires on either platform.

`serviceUuids`, `manufacturerData` and `serviceData` all go in the advertisement itself, where a passive scanner sees them, and share its 31-byte budget; the device name and TX power go in the scan response so they do not compete for it. An over-budget advertisement rejects with `ERR_ADVERTISE` ("Advertise data too large").

#### The advertised local name

`localName` is honoured verbatim on iOS: it becomes `CBAdvertisementDataLocalNameKey`, one of the two advertisement keys `CBPeripheralManager.startAdvertising` supports.

**Android has no per-advertisement local name.** `AdvertiseData.Builder` exposes only `setIncludeDeviceName(boolean)`, and the name that flag includes is the *adapter's* -- `BluetoothLeAdvertiser` sizes the field from `BluetoothAdapter.getNameLengthForAdvertise()`. No public API writes an arbitrary Local Name into an advertisement. Android therefore has two options, neither of which advertises `localName` as given:

- **`android.includeDeviceName`** (the default whenever `localName` is set) advertises the name the device already has, in the scan response. Nothing is mutated.
- **`android.setAdapterName`** renames the adapter to `localName` so scanners see the requested string. It defaults to `false`, so **nothing is renamed unless the app asks for it.** This changes the phone's **system-wide** Bluetooth name -- visible in the device's own Bluetooth settings and to every peer, over Classic as well as LE. The module records the previous name and restores it on `stopAdvertising`, `stopServer`, or module destruction, but restoration is best-effort: `BluetoothAdapter.setName` fails while the adapter is off, and a process killed while advertising never runs it. Prefer `includeDeviceName` unless the exact advertised name genuinely matters.

---

### stopAdvertising

```typescript
stopAdvertising(): void
```

Stop BLE advertisement. Synchronous, and never throws -- including when the native module is absent,
in which case nothing can be advertising anyway.

Does **not** disconnect existing connections or remove services. A `startAdvertising` promise still
in flight rejects with `ERR_ADVERTISE`, since the advertisement it was waiting on has been cancelled.
A `startAdvertising` that is still waiting for the database is **not** cancelled by this, on either
platform: it advertises once the services are published. Use [`stopServer`](#stopserver) to settle one.
On Android this is also where the adapter name is restored if `android.setAdapterName` changed it.

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
only indications is therefore never sent a notification. Passing `requireSubscription: false`
sends anyway, since the platform transmits without consulting the descriptor.

iOS cannot make the distinction at all -- CoreBluetooth reports a subscription without saying which
bit the central set -- so it is checked as "subscribed at all". `false` changes nothing there:
`updateValue(_:for:onSubscribedCentrals:)` "ignores any centrals that haven't subscribed to the
characteristic's value", so there is no send to force and `ERR_NO_SUBSCRIBER` is still reported.

It never relaxes the `confirm` property check, which applies on both platforms regardless.

#### The stored value is left alone

**`sendNotification` does not change the value a read returns.** A notification pushes a value to
subscribed centrals; the *stored* attribute value is what an ATT Read is answered from, and
[`updateCharacteristicValue`](#updatecharacteristicvalue) is what sets it. Do both when a value should
be pushed *and* readable:

```typescript
await updateCharacteristicValue(SERVICE, CHARACTERISTIC, value);
await sendNotification(deviceId, SERVICE, CHARACTERISTIC, value);
```

The order is the useful one: the value is readable before the central it just notified can act on the
notification by reading. Nothing forces you to store a notified value at all -- a characteristic that
streams events or deltas usually should not, and one declaring only `notify` has no readable value to
keep in step in the first place.

> **This changed, and it is breaking.** The stored value used to follow a notification on iOS
> unconditionally -- including on a failed send -- and on Android only below API 33, as a side effect
> of the deprecated overload taking its payload from `characteristic.getValue()`. A consumer relying on
> that must now call `updateCharacteristicValue` itself. See the [changelog](../CHANGELOG.md).

The returned promise resolves once the platform reports the notification as delivered, not when the
call is handed to the Bluetooth stack. Calls made while an earlier notification for the same device
is still in flight are queued in order and sent as the link drains, so awaiting the promise paces a
stream against the connection instead of overrunning it.

> **Android:** the platform allows one outstanding notification per device -- "when multiple
> notifications are to be sent, an application must wait for this callback to be received before
> sending additional notifications"
> ([`onNotificationSent`](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback#onNotificationSent(android.bluetooth.BluetoothDevice,%20int))),
> so the module holds the rest in a per-device queue and hands them over one at a time.

> **iOS:** a payload CoreBluetooth's transmit queue cannot take is held and resent when
> [`peripheralManagerIsReady(toUpdateSubscribers:)`](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanagerdelegate/peripheralmanagerisready(toupdatesubscribers:))
> reports space. Only the refused payloads are resent, in order -- an unrelated central never
> receives an unsolicited update because another central's send was throttled.

**Rejects** with:

| Code | When |
|---|---|
| `PAYLOAD_EXCEEDS_MTU` | `value` is longer than the link can carry in one notification. Checked **before** anything is transmitted, and re-checked if the MTU shrinks while the send is queued -- nothing is sent and the payload is not truncated. Size payloads against [`getMtu`](#getmtu)`.maxNotificationPayload` |
| `ERR_CONFIRM_UNSUPPORTED` | The characteristic does not declare the property `confirm` asks for |
| `ERR_NO_SUBSCRIBER` | The device has not enabled the transmission on the characteristic |
| `ERR_NOTIFY_QUEUE_FULL` | 64 sends are already waiting for the same device (iOS: for the peripheral manager's transmit queue). Await earlier sends first |
| `ERR_DEVICE_DISCONNECTED` | `deviceId` names no connected central, or it went away before a queued notification was delivered |
| `ERR_CHARACTERISTIC_NOT_FOUND` | The pair of UUIDs names nothing in the published GATT database. An unknown `serviceUuid` and an unknown `characteristicUuid` share this code |
| `ERR_NOTIFY` | The stack refused the send, or reported it as undelivered |
| `ERR_NO_SERVER` | No server exists, or it was stopped while the send was queued |
| `ERR_BLUETOOTH` | Bluetooth is not powered on, or was turned off while the send was queued |

A call with more than one thing wrong reports the **first** of these, in this order, identically on both
platforms: `ERR_NO_SERVER` / `ERR_BLUETOOTH`, then `ERR_CHARACTERISTIC_NOT_FOUND`, then
`ERR_CONFIRM_UNSUPPORTED`, then `ERR_DEVICE_DISCONNECTED`, then `ERR_NO_SUBSCRIBER`, then
`PAYLOAD_EXCEEDS_MTU`. The address is checked before the connection because it is the permanent fault of
the two -- no retry fixes a mistyped UUID, while a disconnection may well resolve itself.

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

Answer a pending read or write request -- one delivered by
[`addCharacteristicReadRequestListener`](#addcharacteristicreadrequestlistener) or by
[`addCharacteristicWriteRequestListener`](#addcharacteristicwriterequestlistener) with
`responseNeeded: true`.

| Parameter | Type | Description |
|-----------|------|-------------|
| `deviceId` | `string` | Requesting device identifier, from the event |
| `requestId` | `number` | Request ID from the event |
| `status` | `number` | `GATT_SUCCESS` or an `ATT_ERROR_*` code. Must be an integer `0`--`255`, since an ATT error code is a single octet |
| `offset` | `number` | The offset within the attribute at which `value` begins. Must be an integer `0`--`65535` |
| `value` | `number[]` | Response byte array, starting at `offset`. Not transmitted for a write -- an `ATT_WRITE_RSP` carries no value -- so pass `[]` |

Answering a write with an `ATT_ERROR_*` status is the **only** way to reject a write, and it requires
the characteristic to be configured with [`delegate.write`](#characteristicdelegateconfig); every
other write is already answered by the module before the event is emitted.

`offset` says where `value` begins within the attribute, and the response is rebased onto the offset
the request actually asked for. So both of these are correct and equivalent, on both platforms:

```typescript
// Pass the whole value and let the module take the part the request asked for.
await sendResponse(deviceId, requestId, GATT_SUCCESS, 0, wholeValue);

// Or slice it yourself and say where the slice starts.
await sendResponse(deviceId, requestId, GATT_SUCCESS, event.offset, wholeValue.slice(event.offset));
```

**Rejects** with `REQUEST_NOT_FOUND` if the request ID is invalid, already responded to, or expired
after `createServer`'s `requestTimeoutMs`; `REQUEST_DEVICE_MISMATCH` if the request belongs to a
different device than `deviceId`; `ERR_RESPONSE_OFFSET` if `offset` is past the offset the request
asked for -- which would leave the requested bytes missing from the response;
`ERR_DEVICE_DISCONNECTED` if the central went away; `ERR_NO_SERVER` if no server exists;
`ERR_BLUETOOTH` if Bluetooth is off; and `ERR_RESPONSE` if the Bluetooth stack does not accept the
response. A `status`, `offset` or byte value outside its range is rejected as a plain `Error` before
either platform sees it.

The request is looked up **first**, so answering one the server no longer holds -- which is what losing
the database to a `stopServer` or a Bluetooth power cycle leaves behind -- reports `REQUEST_NOT_FOUND`
rather than the reason the database went away.

A rejected call leaves the request still answerable, rather than consuming it -- so a mistake here
does not strand the central until its own ATT transaction times out.

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

Update the stored value of a characteristic. Subsequent read requests from centrals are auto-responded
by the native layer using this value -- unless the characteristic is configured with
[`delegate.read`](#characteristicdelegateconfig), in which case every read still reaches JavaScript
and the stored value is never consulted.

This is the **only** call that changes what a read returns, besides an automatically acknowledged write.
It does **not** send a notification, and [`sendNotification`](#sendnotification) does not do this -- the
two are independent, so use both to push a value and make it readable.

A value stored here is never overwritten by a write batch that was already outstanding when it
resolved: that batch's held value is dropped instead. See
[a batch that is only partly delegated](#a-batch-that-is-only-partly-delegated).

**Rejects** with `ERR_CHARACTERISTIC_NOT_FOUND` when the pair of UUIDs names nothing in the published
GATT database, `ERR_NO_SERVER` when no server exists, and `ERR_BLUETOOTH` when Bluetooth is not powered
on -- there is no published database to update then, since "the powered off state clears the local
database". `ERR_BLUETOOTH` is the one worth retrying: the module re-publishes the services when
Bluetooth comes back, which [`isServerRunning`](#isserverrunning) reports.

---

### getBluetoothState

```typescript
getBluetoothState(): Promise<BluetoothState>
```

Read the current Bluetooth adapter state. Safe to call before `createServer`, and resolves to
`'unsupported'` where the native module is absent -- which is exactly what that state already means,
so a consumer branching on the state needs no separate `isSupported` check.

See [`BluetoothState`](#bluetoothstate) for the states and their per-platform sources, and
[`addBluetoothStateChangedListener`](#addbluetoothstatechangedlistener) to observe changes.

> **iOS cannot answer precisely before a server exists.** `CBPeripheralManager.state` requires an
> instantiated manager, and instantiating one purely to read the state would trigger the Bluetooth
> permission prompt. So without a server iOS answers `'unauthorized'` when authorization has been
> denied or restricted and `'unknown'` otherwise -- never `'poweredOn'` or `'poweredOff'`. Android
> reads `BluetoothAdapter.getState()`, which needs no permission and no server, so it is accurate at
> any time.

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

**Rejects** with `ERR_DEVICE_DISCONNECTED` when the device is not connected, `ERR_NO_SERVER` when no
server exists. On iOS "not connected" means "not known", which is a weaker statement -- see
[`getConnectedDevices`](#getconnecteddevices).

| Platform | `mtu` | `maxNotificationPayload` |
|----------|-------|--------------------------|
| Android | Exact, from `BluetoothGattServerCallback.onMtuChanged` | Derived as `mtu - 3` |
| iOS | Derived as `maximumUpdateValueLength + 3` | Exact, from `CBCentral.maximumUpdateValueLength` |

CoreBluetooth exposes only a payload length, never an MTU, so on iOS `mtu` is reconstructed by
adding the three header octets back. Apple does not document that identity, so prefer
`maxNotificationPayload` on iOS where the figure is exact.

---

### getConnectedDevices

```typescript
getConnectedDevices(): Promise<ConnectedDevice[]>
```

List the centrals the module currently considers connected. Resolves to `[]` when no server exists.

**"Connected" does not mean the same thing on both platforms, and cannot be made to.** Android
reports connections directly through `BluetoothGattServerCallback.onConnectionStateChange`, so the
list is every central with a link to this server. iOS has no connection-level callback at all, so
membership is derived from ATT activity: a central appears on its first subscribe, read request or
write request, and is dropped when it unsubscribes from everything or Bluetooth leaves `poweredOn`.
So on iOS a central that connects and never touches an attribute is **absent** from this list, and
one that unsubscribes while staying connected is dropped from it early. This is the same tracking
`onDeviceConnected` and `onDeviceDisconnected` report.

The list is the module's own tracking on both platforms, not a platform query.
`BluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)` is deliberately not used: it
reports centrals connected to *any* GATT server on the device, including other apps'.

---

### disconnectDevice

```typescript
disconnectDevice(deviceId: string): Promise<void>
```

Drop a connected central. **Android only.**

Android calls `BluetoothGattServer.cancelConnection`, documented as "Disconnects an established
connection, or cancels a connection attempt currently in progress". That method returns `void` and
reports no outcome, so the promise resolves once the request reaches the Bluetooth stack, not once
the central is gone -- wait for `onDeviceDisconnected` for that. Requires `BLUETOOTH_CONNECT`.

**iOS rejects with `ERR_UNSUPPORTED`, because CoreBluetooth genuinely cannot do this.** The entire
`CBPeripheralManager` interface is `startAdvertising`, `stopAdvertising`,
`setDesiredConnectionLatency(_:for:)`, `addService`, `removeService`, `removeAllServices`,
`respond(to:withResult:)`, `updateValue(_:for:onSubscribedCentrals:)`,
`publishL2CAPChannel(withEncryption:)` and `unpublishL2CAPChannel` -- there is no disconnect among
them. `CBCentral` exposes only `identifier` and `maximumUpdateValueLength`, so there is no handle to
act on either. `cancelPeripheralConnection(_:)` exists, but it is a `CBCentralManager` method taking a
`CBPeripheral`: it ends a connection *this* device opened in the central role, and cannot be turned
around on a remote central.

No approximation is offered. `stopAdvertising` prevents new connections but does not end existing
ones, and neither `removeAllServices` nor `stopServer` is documented as disconnecting anybody --
presenting either as an equivalent would be an invention. On iOS, only the central can end the
connection.

**Rejects** on iOS with `ERR_UNSUPPORTED` always, before any other check -- there is no state in
which the call can succeed there. On Android it rejects with `ERR_PERMISSION` when
`BLUETOOTH_CONNECT` is not granted (API 31+), `ERR_NO_CONTEXT` when no React context is available to
check that against, `ERR_NO_SERVER` when no server exists, `ERR_BLUETOOTH` when Bluetooth is off,
`ERR_DEVICE_DISCONNECTED` when the device is not connected, and `ERR_DISCONNECT` if the stack raises
anything else.

---

### isServerRunning

```typescript
isServerRunning(): Promise<boolean>
```

Whether a GATT database is currently published and usable.

`false` before `createServer`, after `stopServer`, and while Bluetooth is not powered on -- both
platforms destroy the published database when the adapter goes down. The module re-publishes it on
the next transition to `poweredOn`, at which point this becomes `true` again with no further call. It
is therefore the right thing to check before advertising, rather than remembering whether
`createServer` was ever called.

---

### isAdvertising

```typescript
isAdvertising(): Promise<boolean>
```

Whether the peripheral is currently advertising.

iOS reads `CBPeripheralManager.isAdvertising`, which the platform maintains itself. Android has no
equivalent query -- `BluetoothLeAdvertiser` exposes none -- so the module tracks it from
`AdvertiseCallback.onStartSuccess` / `onStartFailure` and clears it on `stopAdvertising`, on the
adapter going down, and when an `AdvertiseConfig.timeoutMs` elapses. That last case needs its own
timer because `AdvertiseSettings.setTimeout` stops the advertisement at the limit without invoking
`AdvertiseCallback` at all.

---

### stopServer

```typescript
stopServer(): void
```

Shut down the GATT server: stop advertising, unpublish every service, and release native resources.
Call this in cleanup or when done with BLE. Synchronous, and never throws -- including when the native
module is absent.

**It does not disconnect anybody.** Neither platform offers a peripheral-role disconnect that
unpublishing a database implies: on Android use [`disconnectDevice`](#disconnectdevice) first if the
central must be dropped, and on iOS only the central can end the connection. A central that was
connected simply loses the attributes it was talking to.

For the same reason **no `onDeviceDisconnected` events are emitted** by `stopServer`. The module
discards its own connection tracking without reporting a disconnection it did not observe, so
`getConnectedDevices` goes empty while any actual links are still up.

Pending work is settled rather than abandoned: an unresolved `createServer` rejects with
`ERR_NO_SERVER`, queued notifications reject, unanswered
delegated requests are dropped without being answered, and a pending `startAdvertising` rejects with
`ERR_ADVERTISE` -- or with `ERR_NO_SERVER` if it was still waiting for the database -- restoring the
adapter name on Android if `android.setAdapterName` changed it. Both platforms unpublish the whole database and stop
listening for adapter state, so a later `createServer` starts from an empty GATT database rather than
colliding with the previous one, and services are **not** re-published if Bluetooth is cycled
afterwards.

## Event Listeners

All listeners return an `EventSubscription` with a `.remove()` method. Call `.remove()` to
unsubscribe. When the native module is absent they return a subscription whose `remove()` does
nothing, so registering a listener is safe on any platform.

### addDeviceConnectedListener

```typescript
addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): EventSubscription
```

Fired once per connected central, independently of any subscription.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier (UUID on iOS, MAC address on Android) |
| `event.name` | `string?` | `BluetoothDevice.getName()` on Android. Always `''` on iOS, which exposes no name for a remote central |

> **iOS reports the first ATT activity, not the connection.**
> `CBPeripheralManagerDelegate` declares no connection-level callback, so a central is reported when
> it first subscribes, reads or writes -- whichever comes first, and only once. **A central that
> connects and never touches an attribute is not observable from the peripheral role at all**, so no
> event fires for it. Android reports the connection itself, from `onConnectionStateChange`.

---

### addDeviceDisconnectedListener

```typescript
addDeviceDisconnectedListener(
  listener: (event: DeviceDisconnectedEvent) => void,
): EventSubscription
```

Fired once when a central goes away.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier |

> **On iOS this is inferred, and the inference is imperfect.** CoreBluetooth never reports a
> disconnection to a peripheral, so the module treats **losing the last subscription** as one, and
> reports every known central as disconnected when Bluetooth leaves `poweredOn`. Two consequences,
> both real:
>
> - A central that only ever read or wrote produces **no** disconnect event when it leaves. It
>   generally stays in `getConnectedDevices` until Bluetooth is turned off or the server stops.
> - A central that deliberately unsubscribes but stays connected is reported as disconnected
>   **early**. A later read or write re-discovers it, producing a fresh `onDeviceConnected`.
>
> CoreBluetooth delivers the same callback whether the central cleared its Client Characteristic
> Configuration or vanished, and offers nothing to tell the two apart. Android reports the
> disconnection itself.

`stopServer` emits nothing: see [stopServer](#stopserver).

---

### addCharacteristicReadRequestListener

```typescript
addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
): EventSubscription
```

Fired when a central reads a characteristic the module is not answering itself. **Every such request
must be answered with [`sendResponse`](#sendresponse)**, or it is completed with
`ATT_ERROR_UNLIKELY_ERROR` after `createServer`'s `requestTimeoutMs`.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Requesting device |
| `event.requestId` | `number` | Use in `sendResponse`. Unique per request, and only valid until answered or expired |
| `event.serviceUuid` | `string` | Service UUID, or `''` when the platform could not identify the owning service |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Read offset. Non-zero for a Read Blob continuation |

A read reaches this listener in exactly two cases, on both platforms:

- The characteristic has **no cached value** -- `value` was omitted from its configuration and
  `updateCharacteristicValue` has never been called for it. Once a value exists the module answers
  from it and the event stops firing.
- The characteristic is configured with [`delegate.read`](#characteristicdelegateconfig), which keeps
  every read coming to JavaScript however current the cached value is. This is what a computed or
  dynamic read needs.

> **Note:** A read whose offset is past the end of the cached value is answered directly with ATT
> error `0x07` "Invalid Offset" (Core Specification, Vol 3, Part F, Section 3.4.1.1) and does not
> reach this listener. An offset equal to the value's length is in range and answers with an empty
> value, as the specification intends for a Read Blob that has consumed the whole attribute. A
> characteristic with `delegate.read` has no cached value to range-check against, so its offsets are
> passed through for the listener to interpret.

---

### addCharacteristicWriteRequestListener

```typescript
addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
): EventSubscription
```

Fired when a central writes to a characteristic. The event is emitted for **every** write, whether or
not JavaScript has to answer it.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Writing device |
| `event.requestId` | `number` | Pass to `sendResponse` when `responseNeeded` is `true` |
| `event.serviceUuid` | `string` | Service UUID, or `''` when the platform could not identify the owning service |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Where `value` begins within the attribute. Always `0` for a reassembled long write |
| `event.value` | `number[]` | Written byte array |
| `event.responseNeeded` | `boolean` | Whether the module is **waiting for JavaScript** to answer this request -- see below |

#### `responseNeeded` means "you must answer this"

`responseNeeded` is `true` only when the module is holding the ATT transaction open for
[`sendResponse`](#sendresponse). That happens exactly when the characteristic is configured with
[`delegate.write`](#characteristicdelegateconfig) **and** the write actually carries a response. It is
decided **per characteristic**, so a batch touching a delegated and a plain characteristic emits one
event of each and only the delegated one asks to be answered.

Every other write has already been acknowledged with `GATT_SUCCESS` before the event was emitted, and
arrives with `responseNeeded: false`. Calling `sendResponse` for such a request rejects with
`REQUEST_NOT_FOUND`.

> **A Write Without Response is not delegated on Android, and cannot be told apart on iOS.** Android's
> `onCharacteristicWriteRequest` carries a `responseNeeded` flag, so the module never delegates a write
> that carries nothing to answer -- an opted-in characteristic still receives the event, with
> `responseNeeded: false`, and its value is left alone. **iOS has no such flag.**
> `peripheralManager:didReceiveWriteRequests:` is documented as invoked "when *peripheral* receives an
> ATT request **or command**", and `CBATTRequest` exposes only `central`, `characteristic`, `offset` and
> `value` -- nothing distinguishing an `ATT_WRITE_REQ` from an `ATT_WRITE_CMD`. CoreBluetooth also
> requires `respond(to:withResult:)` for every invocation regardless. So on iOS a Write Without Response
> to a `delegate.write` characteristic arrives with `responseNeeded: true` and does wait for
> `sendResponse`; leaving it unanswered expires it after `requestTimeoutMs` as any other delegated
> request would. Declare `writeNoResponse` on a delegated characteristic only if that is acceptable.

When it is `true`, the write is **not applied** until JavaScript accepts it: answer with
`GATT_SUCCESS` and commit the value yourself with
[`updateCharacteristicValue`](#updatecharacteristicvalue), or answer with an `ATT_ERROR_*` code to
reject the write. Leaving it unanswered completes it with `ATT_ERROR_UNLIKELY_ERROR` after
`requestTimeoutMs`.

**An automatically acknowledged write updates the value a later read is answered from**, identically on
both platforms, so a readable characteristic serves what was written without any help from JavaScript.
The value is *replaced*, not merged: `ATT_WRITE_REQ` carries only a handle and a value, and "the
attribute value shall be truncated or lengthened to match the length of the Attribute Value parameter"
(Vol 3, Part F, Section 3.4.5.1), so writing fewer bytes than the characteristic currently holds
shortens it rather than leaving a stale tail. A write bearing a non-zero offset -- which only arises
from the queued-write procedure -- is spliced in at that offset instead, and one starting past the end
of the value is refused with `ATT_ERROR_INVALID_OFFSET`.

A **delegated** write is the exception: nothing is stored until JavaScript accepts it, so commit the
value yourself with [`updateCharacteristicValue`](#updatecharacteristicvalue) when you answer with
`GATT_SUCCESS`. A characteristic configured with `delegate.write` never has a written value stored for
it automatically, including for a Write Without Response that arrives with `responseNeeded: false`.

#### A batch that is only partly delegated

One write batch -- a CoreBluetooth `didReceiveWrite:` array, or one Android reliable-write execute --
can touch several characteristics at once, and they need not agree about delegation. Delegation is
decided **per characteristic**, and the batch stays atomic:

- Each characteristic gets its own event. `responseNeeded` is `true` on the delegated ones only.
- The plain characteristics' values are **held, not applied**, and committed the moment the batch is
  answered with `GATT_SUCCESS`. An `ATT_ERROR_*` answer, or letting the request expire after
  `requestTimeoutMs`, discards them.
- If nothing in the batch delegates, it is applied and acknowledged immediately, as before.
- A held value is **dropped rather than committed** if anything wrote that characteristic while the
  batch was outstanding -- an [`updateCharacteristicValue`](#updatecharacteristicvalue) call, or another
  central's write. The newer value stands; the batch is still answered with the status you passed, since
  by then the write has been accepted and there is nothing in ATT that reports "applied, then
  superseded".

Deferring rather than applying immediately is what keeps the batch all-or-nothing, which both platforms
require: CoreBluetooth documents that "if the execution of one of the requests would cause a failure
[...] none of the requests should be executed", and an execute either applies its whole queue or none of
it (Vol 3, Part F, Section 3.4.6.3). Rejecting a delegated write in a batch therefore also rolls back the
plain characteristic written beside it.

> **iOS shares one `requestId` across a batch.** CoreBluetooth delivers writes as an array and
> requires exactly one `respond(to:withResult:)` per callback, passing the first request. So one batch
> emits one event per attribute written, all carrying the **same** `requestId`, and the first
> `sendResponse` for that id answers the whole batch. Later calls for it reject with
> `REQUEST_NOT_FOUND`. On Android each direct write request has its own id, but an execute is a single
> request, so a reliable write behaves the same way there.

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

If any characteristic in the execute is configured with `delegate.write`, the execute is what waits for
`sendResponse` -- a single `requestId` covering the whole atomic operation, exactly as an iOS write
batch does, and the characteristics that did not opt in have their values held until it is answered.
The individual prepare steps are never delegated; there is nothing meaningful to accept or reject until
the execute says the value is real.

> **iOS does not expose prepared writes at all.** `CBPeripheralManagerDelegate` declares twelve
> methods and none of them concerns prepare or execute; `CBATTRequest` carries only `central`,
> `characteristic`, `offset` and `value`, with no prepared-write flag. (`CBATTError` does define
> `prepareQueueFull`, but that is just the complete ATT error table -- there is no callback to return
> it from.) CoreBluetooth handles the procedure below the app layer and surfaces whatever it decides
> to surface through `didReceiveWriteRequests`, so there is nothing for the module to buffer and
> nothing to configure. Long writes to an iOS peripheral work, and each request's `offset` is honoured
> when its value is stored, but the batch reaches JavaScript as one event per request rather than one
> reassembled value per attribute, and the execute cannot be rejected.

---

### addNotificationSentListener

```typescript
addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
): EventSubscription
```

Fired when the platform has finished with a notification or indication.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Target device |
| `event.characteristicUuid` | `string` | Notified characteristic |
| `event.status` | `number` | `0` for success; a platform GATT status otherwise |

`characteristicUuid` always identifies the characteristic this particular notification carried, so
notifying several characteristics, or several devices, reports each one correctly.

`sendNotification`'s promise carries the same outcome, and is usually the better signal -- it
identifies *which* send finished. This event is for observing traffic the app did not initiate the
await for.

| | Android | iOS |
|---|---|---|
| Source | `BluetoothGattServerCallback.onNotificationSent` | The module, once `updateValue(_:for:onSubscribedCentrals:)` accepts the payload |
| Meaning | The stack finished transmitting -- and, for an indication, received the central's confirmation | CoreBluetooth accepted the payload for transmission. It is **not** a delivery report |
| On failure | Emitted with the non-zero `status` the platform reported | **Not emitted at all** -- a refused or failed send only rejects `sendNotification`'s promise |
| `status` | Whatever the platform reported | Always `0` |

### addMtuChangedListener

```typescript
addMtuChangedListener(
  listener: (event: MtuChangedEvent) => void,
): EventSubscription
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
): EventSubscription
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
): EventSubscription
```

Fired when a central stops receiving updates for a characteristic -- the signal to stop streaming.
Also emitted for every subscription a central still held when it disconnects, and when Bluetooth is
turned off and the published database is dropped.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Unsubscribing device |
| `event.serviceUuid` | `string` | Owning service, or `''` when the platform could not identify it |
| `event.characteristicUuid` | `string` | Characteristic no longer subscribed |

On iOS, losing the **last** subscription for a central is also what the module reads as a
disconnection, so this event is immediately followed by `onDeviceDisconnected` in that case. See
[addDeviceDisconnectedListener](#adddevicedisconnectedlistener).

---

### addBluetoothStateChangedListener

```typescript
addBluetoothStateChangedListener(
  listener: (event: BluetoothStateChangedEvent) => void,
): EventSubscription
```

Fired whenever the Bluetooth adapter state changes. See [`BluetoothState`](#bluetoothstate) for the
states.

| Field | Type | Description |
|-------|------|-------------|
| `event.state` | `BluetoothState` | The new adapter state |

**Delivered only while a server exists**, because state monitoring is tied to the server lifecycle on
both platforms: Android registers a receiver for `BluetoothAdapter.ACTION_STATE_CHANGED` in
`createServer` and unregisters it in `stopServer`, and iOS gets the state from
`peripheralManagerDidUpdateState`, which requires an instantiated `CBPeripheralManager`. Use
[`getBluetoothState`](#getbluetoothstate) for a one-off read outside that window.

On iOS the first event usually arrives shortly after `createServer` and carries the state
CoreBluetooth resolved to; before that the state is `'unknown'`.

`poweredOff` destroys the published GATT database on both platforms, and every subscription with it,
so expect `onCharacteristicUnsubscribed` and `onDeviceDisconnected` for everything that was live. The
module **re-publishes the services** on the next transition to `poweredOn`, at which point
[`isServerRunning`](#isserverrunning) goes `true` again -- but **advertising is not restarted**, so
call `startAdvertising` again yourself.

The event fires *before* that re-publication has finished, but `startAdvertising` parks until the
services are published on both platforms, so calling it straight from the handler is enough:

```typescript
addBluetoothStateChangedListener(async ({ state }) => {
  if (state !== 'poweredOn') return;
  await startAdvertising({ serviceUuids: [SERVICE_UUID] });
});
```

If Bluetooth goes off again before the re-publication finishes, that call rejects with `ERR_BLUETOOTH`
rather than staying pending.

**On iOS `resetting` does the same.** `CBManagerState.resetting` sorts *below* `poweredOff`, and Apple
documents any state below it as clearing the local database and disconnecting every central, so the
module discards the same state and emits the same events. `isServerRunning` goes `false` for the
duration. Treat the state itself as transient exactly as before -- the services are re-published on the
`poweredOn` that follows -- but do not assume connections or subscriptions survive it.

## Types

### GattServiceConfig

```typescript
interface GattServiceConfig {
  uuid: string;
  type?: GattServiceType;
  characteristics: GattCharacteristicConfig[];
}
```

### GattServiceType

```typescript
type GattServiceType = 'primary' | 'secondary';
```

Defaults to `primary`. Maps onto `BluetoothGattService.SERVICE_TYPE_SECONDARY` and
`CBMutableService(type:primary: false)`.

A secondary service "is a service that is included from another service" (Core Specification,
Vol 3, Part G, Section 3.1). This module publishes every configured service at the top level and
offers no way to include one service from another, so a `secondary` service **will not be found by
a central doing primary service discovery**. It is exposed because both platforms can express the
type, not because a standalone secondary service is useful.

### GattCharacteristicConfig

```typescript
interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  value?: number[];
  descriptors?: GattDescriptorConfig[];
  delegate?: CharacteristicDelegateConfig;
}
```

`value` is the value reads are answered from until something replaces it. Omit it to have every read
delegated to JavaScript instead.

`value: []` is a **configured** value, not an absent one: it declares a present but zero-length
attribute, which is a legitimate GATT state, and both platforms cache it and answer reads from it with
an empty value. Omit `value` entirely for the delegating behaviour.

### CreateServerOptions

```typescript
interface CreateServerOptions {
  requestTimeoutMs?: number;
}
```

`requestTimeoutMs` is how long a request delegated to JavaScript may go unanswered before the module
answers it itself with `ATT_ERROR_UNLIKELY_ERROR`. Defaults to `DEFAULT_REQUEST_TIMEOUT_MS` (`10000`).
Must be an integer from `0` to `ATT_TRANSACTION_TIMEOUT_MS - 1` (`29999`), where `0` disables the
timeout. See [Unanswered requests](#unanswered-requests).

### CharacteristicDelegateConfig

```typescript
interface CharacteristicDelegateConfig {
  read?: boolean;
  write?: boolean;
}
```

Per-characteristic opt-in delegation of ATT request handling to JavaScript. Both flags default to
`false`, which keeps the module answering the request itself. Set on
[`GattCharacteristicConfig.delegate`](#gattcharacteristicconfig).

| Flag | Effect |
|---|---|
| `read` | Always emit `onCharacteristicReadRequest` and wait for `sendResponse`, even when the characteristic already has a value to serve. Without it the module answers from the last known value as soon as one exists, so the event stops firing -- which is why a computed or dynamic read needs this. A configured `value` is still used as the payload of a notification |
| `write` | Do not acknowledge writes automatically. The write stays unapplied and unanswered, and `onCharacteristicWriteRequest` arrives with `responseNeeded: true`, until `sendResponse` is called with `GATT_SUCCESS` or an `ATT_ERROR_*` code. **This is the only way to reject a write** |

Both behave identically on Android and iOS, with three notes:

- A **Write Without Response** carries nothing to answer, so Android never delegates one even with
  `write: true`. iOS cannot make that distinction -- `CBATTRequest` carries no response flag -- so there
  it is delegated like any other write. See
  [addCharacteristicWriteRequestListener](#addcharacteristicwriterequestlistener).
- Delegation is decided **per characteristic**, so a batch touching a delegated and a plain
  characteristic applies the plain one's value once the batch is accepted, discards it if the delegated
  one is rejected, and leaves it alone if something wrote that characteristic in the meantime. See
  [addCharacteristicWriteRequestListener](#addcharacteristicwriterequestlistener).
- On iOS a batch of writes delivered in one callback shares a single `requestId`, so one
  `sendResponse` answers all of it. An Android reliable-write execute is a single request and behaves
  the same way.

### GattDescriptorConfig

```typescript
interface GattDescriptorConfig {
  uuid: string;
  value: number[];
  permissions?: CharacteristicPermission[];
}
```

Descriptors published alongside the Client Characteristic Configuration descriptor the module adds
itself.

`value` is required because iOS documents a descriptor value as "required and cannot be updated
dynamically once the parent service has been published"; requiring it everywhere keeps one
configuration portable. `permissions` defaults to `['readable']` and is **ignored on iOS** —
`CBMutableDescriptor` has no permissions parameter, so CoreBluetooth derives them from the
descriptor type.

| Descriptor | Android | iOS |
|---|---|---|
| `0x2901` Characteristic User Description | published | published; the bytes are decoded as UTF-8 because Apple models this value as an `NSString`, and invalid UTF-8 rejects with `ERR_UNSUPPORTED` |
| `0x2904` Characteristic Presentation Format | published | published, bytes verbatim |
| `0x2902` Client Characteristic Configuration | **rejected** | **rejected** |
| anything else (e.g. `0x2900`, `0x2903`) | published | **rejected** with `ERR_UNSUPPORTED` |

`CBMutableDescriptor` is documented as supporting "only the `Characteristic User Description` and
`Characteristic Presentation Format` descriptors", with the Client Characteristic Configuration and
Characteristic Extended Properties descriptors "created automatically upon publication of the parent
service". Declaring any other UUID is therefore refused on iOS rather than handed to CoreBluetooth,
which would fail the whole service at publication time. Declare such a descriptor for Android only.

The CCCD is rejected on both platforms: the module publishes it for every characteristic declaring
`notify` or `indicate`, and answers reads and writes of it from its own per-device subscription
tracking, because the specification gives "each client its own instantiation" of that descriptor
while the platforms hand out a single shared object. A manually declared one would shadow that. Its
permissions come from the parent characteristic — see
[Permissions and subscriptions](#permissions-and-subscriptions).

### CharacteristicProperty

```typescript
type CharacteristicProperty =
  | 'read'
  | 'write'
  | 'writeNoResponse'
  | 'notify'
  | 'indicate'
  | 'broadcast'
  | 'signedWrite'
  | 'extendedProperties';
```

| Property | Android | iOS |
|---|---|---|
| `read` | `PROPERTY_READ` | `.read` |
| `write` | `PROPERTY_WRITE` | `.write` |
| `writeNoResponse` | `PROPERTY_WRITE_NO_RESPONSE` | `.writeWithoutResponse` |
| `notify` | `PROPERTY_NOTIFY` | `.notify` |
| `indicate` | `PROPERTY_INDICATE` | `.indicate` |
| `signedWrite` | `PROPERTY_SIGNED_WRITE` | `.authenticatedSignedWrites` |
| `broadcast` | `PROPERTY_BROADCAST` | **rejected** with `ERR_UNSUPPORTED` |
| `extendedProperties` | `PROPERTY_EXTENDED_PROPS` | **rejected** with `ERR_UNSUPPORTED` |

Apple annotates both `CBCharacteristicPropertyBroadcast` and
`CBCharacteristicPropertyExtendedProperties` as "Not allowed for local characteristics", so neither
can be set on a published peripheral. They are still offered because Android does set the bits, and
a peripheral targeting Android alone can legitimately want them — declare them for Android only.

An unrecognised name throws, rather than being ignored, so a typo cannot silently publish a
characteristic with one fewer property than the configuration asked for.

### CharacteristicPermission

```typescript
type CharacteristicPermission =
  | 'readable'
  | 'writeable'
  | 'readEncrypted'
  | 'readEncryptedMitm'
  | 'writeEncrypted'
  | 'writeEncryptedMitm'
  | 'writeSigned'
  | 'writeSignedMitm';
```

| Permission | Android | iOS |
|---|---|---|
| `readable` | `PERMISSION_READ` | `.readable` |
| `writeable` | `PERMISSION_WRITE` | `.writeable` |
| `readEncrypted` | `PERMISSION_READ_ENCRYPTED` | `.readEncryptionRequired` |
| `writeEncrypted` | `PERMISSION_WRITE_ENCRYPTED` | `.writeEncryptionRequired` |
| `readEncryptedMitm` | `PERMISSION_READ_ENCRYPTED_MITM` | **rejected** with `ERR_UNSUPPORTED` |
| `writeEncryptedMitm` | `PERMISSION_WRITE_ENCRYPTED_MITM` | **rejected** with `ERR_UNSUPPORTED` |
| `writeSigned` | `PERMISSION_WRITE_SIGNED` | **rejected** with `ERR_UNSUPPORTED` |
| `writeSignedMitm` | `PERMISSION_WRITE_SIGNED_MITM` | **rejected** with `ERR_UNSUPPORTED` |

`CBAttributePermissions` has exactly four members — `readable`, `writeable`,
`readEncryptionRequired`, `writeEncryptionRequired` — so there is no 1:1 mapping for Android's
eight. The four rejected variants are **not approximated**, because every near equivalent is weaker
than what was asked for: an MITM variant requires authenticated pairing rather than merely an
encrypted link, and a signed variant requires a signature over an unencrypted one. Mapping either
onto `.readEncryptionRequired`/`.writeEncryptionRequired` — or worse, onto plain
`.readable`/`.writeable` — would publish an attribute less protected than the app declared, without
saying so. Failing the call is the safer outcome, and the error names `readEncrypted` /
`writeEncrypted` as the portable choice.

An unrecognised name throws, rather than being ignored, so a typo cannot silently publish an attribute
less protected than the configuration asked for.

#### Permissions and subscriptions

A permission is enforced on the attribute that carries it and on nothing else. Android resolves
`p_attr->permission` from the handle being read or written and inherits nothing from the parent
characteristic (`gatts_write_attr_perm_check`, `system/stack/gatt/gatt_db.cc`), and
`GATTS_HandleValueNotification` performs no permission, encryption or subscription check whatsoever.
A notification therefore escapes every check a read of the same value would face — so the Client
Characteristic Configuration descriptor, whose write is what opens the stream, carries permissions
derived from the parent characteristic's rather than a fixed `PERMISSION_READ | PERMISSION_WRITE`:

| Characteristic declares | CCCD read | CCCD write |
|---|---|---|
| neither `readEncrypted*` nor `writeEncrypted*` | `PERMISSION_READ` | `PERMISSION_WRITE` |
| `readEncrypted` | `PERMISSION_READ_ENCRYPTED` | `PERMISSION_WRITE_ENCRYPTED` |
| `writeEncrypted` | `PERMISSION_READ` | `PERMISSION_WRITE_ENCRYPTED` |
| `readEncryptedMitm` | `PERMISSION_READ_ENCRYPTED_MITM` | `PERMISSION_WRITE_ENCRYPTED_MITM` |
| `writeEncryptedMitm` | `PERMISSION_READ` | `PERMISSION_WRITE_ENCRYPTED_MITM` |

The strongest level wins when several are combined: the descriptor's read mirrors the characteristic's
strongest read permission, and its write demands the strongest encryption level declared in *either*
direction, because enabling a subscription is what puts the value on the air. `writeSigned` and
`writeSignedMitm` contribute nothing — they constrain the form of an inbound write PDU, and a CCCD is
configured with an ordinary write request rather than a signed write command.

A characteristic declaring only `readable` and `writeable` keeps the plain
`PERMISSION_READ | PERMISSION_WRITE` it always had. One declaring an encrypted permission is a
**behaviour change**: a client that has not reached that level is now refused at the CCCD with
`GATT_INSUF_ENCRYPTION` or `GATT_INSUF_AUTHENTICATION`, where before it could subscribe and receive
every value in cleartext.

iOS reaches the same place by a different mechanism. CoreBluetooth owns the CCCD and never exposes it,
and `CBAttributePermissions` guards only a read or a write of the value — so the gate on subscribing is
the separate property pair Apple documents as "only trusted devices can enable
notifications/indications of the characteristic value". A characteristic declaring `notify` or
`indicate` together with `readEncrypted` or `writeEncrypted` is therefore published with
`.notifyEncryptionRequired` / `.indicateEncryptionRequired` added to the property it declared:

| Characteristic declares | iOS properties published |
|---|---|
| `notify`, no encrypted permission | `.notify` |
| `notify` + `readEncrypted` or `writeEncrypted` | `.notify`, `.notifyEncryptionRequired` |
| `indicate` + `readEncrypted` or `writeEncrypted` | `.indicate`, `.indicateEncryptionRequired` |

These are derived rather than offered as two more [`CharacteristicProperty`](#characteristicproperty)
names, so one configuration means the same thing on both platforms and nothing has to branch on
`Platform.OS`. The plain member is kept alongside, because it is what sets the matching bit of the
published characteristic declaration (Vol 3, Part G, Table 3.5) that a central looks for before it
subscribes at all. The MITM and signed permissions are still rejected on iOS before any of this
applies.

> **The residual limitation, on Android only.** `sendNotification` with `requireSubscription: false`
> transmits to a device that never subscribed, and so never passed the descriptor check above. Android
> offers nothing at this layer to check instead: `BluetoothDevice.isEncrypted()` is `@hide`/`@SystemApi`
> and unreachable from an app, and the public `getBondState()` answers a different question — LE pairing
> without bonding leaves it at `BOND_NONE` over an encrypted link, and a bonded device is not
> necessarily on an encrypted one. Leave `requireSubscription` at its default of `true` for a
> characteristic whose value needs a secure link. iOS has no such gap: `updateValue` "ignores any
> centrals that haven't subscribed", so there is no send to force.

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

Options whose concept has no CoreBluetooth counterpart, so the whole object is ignored on iOS. Both
concern the advertised name -- see [The advertised local name](#the-advertised-local-name).
`includeDeviceName` defaults to `true` whenever `localName` is set and `false` otherwise, and costs the
name's length plus two bytes of the scan response's 31-byte budget. `setAdapterName` defaults to
`false`.

### SendNotificationOptions

```typescript
interface SendNotificationOptions {
  requireSubscription?: boolean;
}
```

Defaults to `true`. See [`requireSubscription`](#requiresubscription) for what each platform can
actually check, and note that `false` changes nothing on iOS.

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

### ConnectedDevice

```typescript
interface ConnectedDevice {
  deviceId: string;
  name?: string;
}
```

Returned by `getConnectedDevices`. `name` comes from `BluetoothDevice.getName()` on Android and is
always empty on iOS, which exposes no name for a remote central. See `getConnectedDevices` for what
"connected" means on each platform.

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

`CharacteristicUnsubscribedEvent` has the same three fields.

### BluetoothState

```typescript
type BluetoothState =
  | 'unknown'
  | 'resetting'
  | 'unsupported'
  | 'unauthorized'
  | 'poweredOff'
  | 'poweredOn';
```

Adapter state, normalised so consumers never have to branch on platform.

| State | Meaning | iOS | Android |
|---|---|---|---|
| `poweredOn` | The only state in which a server can advertise | `CBManagerState.poweredOn` | `STATE_ON` |
| `poweredOff` | Bluetooth is off. Destroys the published database on both platforms | `CBManagerState.poweredOff` | `STATE_OFF` |
| `resetting` | Transient, so do not act yet | `CBManagerState.resetting` | `STATE_TURNING_ON` / `STATE_TURNING_OFF`, both documented as not yet usable |
| `unsupported` | No BLE peripheral support. Also what the module reports where the native module is absent | `CBManagerState.unsupported` | No `BluetoothAdapter` |
| `unauthorized` | The app may not use Bluetooth | `CBManagerState.unauthorized` | Not reported; Android surfaces this as a rejected call instead |
| `unknown` | Not determined yet | Reported until the first state callback arrives, and by `getBluetoothState` before a server exists | Only when no React context is available |

### BluetoothStateChangedEvent

```typescript
interface BluetoothStateChangedEvent {
  state: BluetoothState;
}
```

### GattServerEvents

```typescript
type GattServerEvents = {
  onDeviceConnected(event: DeviceConnectedEvent): void;
  onDeviceDisconnected(event: DeviceDisconnectedEvent): void;
  onCharacteristicReadRequest(event: CharacteristicReadRequestEvent): void;
  onCharacteristicWriteRequest(event: CharacteristicWriteRequestEvent): void;
  onNotificationSent(event: NotificationSentEvent): void;
  onMtuChanged(event: MtuChangedEvent): void;
  onCharacteristicSubscribed(event: CharacteristicSubscribedEvent): void;
  onCharacteristicUnsubscribed(event: CharacteristicUnsubscribedEvent): void;
  onBluetoothStateChanged(event: BluetoothStateChangedEvent): void;
};
```

The event-name-to-payload map the native module is typed against. Exported for consumers that want to
name an event and its payload together; the `add*Listener` helpers are the supported way to subscribe.

### EventSubscription

Re-exported from `expo-modules-core`, and what every `add*Listener` returns:

```typescript
interface EventSubscription {
  remove(): void;
}
```

It is re-exported so consumers can annotate a stored subscription without adding a direct dependency on
`expo-modules-core`.

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
(`0xE0`–`0xFF`) ranges have no iOS representation and would arrive as Unlikely Error. `sendResponse`
still accepts any byte, so passing one of those to Android alone works -- it simply has no portable
meaning.

### Other constants

| Constant | Value | Description |
|----------|-------|-------------|
| `ATT_TRANSACTION_TIMEOUT_MS` | `30000` | The ATT transaction timeout (Core Specification, Vol 3, Part F, Section 3.3.3). The exclusive upper bound on `CreateServerOptions.requestTimeoutMs` |
| `DEFAULT_REQUEST_TIMEOUT_MS` | `10000` | The default `CreateServerOptions.requestTimeoutMs` |
| `CLIENT_CHARACTERISTIC_CONFIGURATION_UUID` | `'00002902-0000-1000-8000-00805f9b34fb'` | The CCCD UUID, in the 128-bit form both platforms compare against. The module publishes and answers this descriptor itself, so declaring it in `descriptors` is rejected; the constant is exported for recognising it |

## Error Codes

Every rejection from this module carries a `code` property. Argument validation performed in the shared
TypeScript layer -- malformed UUIDs, bytes outside `0`--`255`, out-of-range timeouts, offsets and
statuses, unrecognised enum names -- throws a plain `Error` with a descriptive message and **no**
`code`, since it never reaches a platform. So a rejection with a `code` always came from a platform, and
one without it always means the arguments were wrong.

**The same situation reports the same code on both platforms.** Where a code is marked as belonging to
one platform below, it is because only that platform has the situation at all -- not because the other
reports it differently -- so `code` can be branched on without also branching on `Platform.OS`. The
messages are not part of that contract and do differ.

That extends to a call with **more than one** thing wrong: both platforms check in the same order and
report the same one of the faults. `sendNotification`'s order is
[given with the call](#sendnotification).

| Code | Description |
|------|-------------|
| `ERR_UNSUPPORTED` | The call, configuration or advertising option cannot be expressed on this platform, so retrying never helps. On iOS: `disconnectDevice`, `manufacturerData` / `serviceData` / `connectable: false`, MITM and signed permissions, the `broadcast` and `extendedProperties` properties, and any descriptor other than `0x2901` / `0x2904`. On Android: an adapter with no BLE advertising support |
| `ERR_NO_SERVER` | No server exists, or it was stopped while the call was in flight. A database missing because Bluetooth is off is `ERR_BLUETOOTH` instead, on both platforms |
| `ERR_PERMISSION` | Bluetooth permission is not granted -- `CBManager.authorization` on iOS, `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` on Android (API 31+) |
| `ERR_NO_CONTEXT` | **Android only.** No React context was available, so the permission could not be checked. Treated as a failure rather than a pass, because assuming the grant only defers it to a `SecurityException` |
| `ERR_BLUETOOTH` | Bluetooth is off, unsupported or otherwise not ready. This is how every call reports a powered-off adapter, including `createServer`, `startAdvertising`, `sendNotification`, `updateCharacteristicValue` and `disconnectDevice` -- the published database does not survive the adapter going down, and the module re-publishes it when Bluetooth returns, so this is the retryable one |
| `ERR_CREATE_SERVER` | A service failed to publish, or the configuration was rejected by the native layer |
| `ERR_ADVERTISE` | The platform refused the advertisement, or a pending `startAdvertising` was superseded by another one, by `stopAdvertising` or by `stopServer` |
| `ERR_RESPONSE` | The Bluetooth stack did not accept a `sendResponse`, or its arguments were rejected by the native layer |
| `ERR_DISCONNECT` | **Android only.** `disconnectDevice` failed for a reason the stack did not classify |
| `PAYLOAD_EXCEEDS_MTU` | A `sendNotification` payload is longer than one notification can carry (`mtu - 3`). Checked before transmitting, so nothing was sent. The message says when the link is still at the default ATT MTU of 23 |
| `REQUEST_NOT_FOUND` | The `requestId` does not match a pending read or write request. It was never delegated, has already been answered, or expired after `requestTimeoutMs` |
| `REQUEST_DEVICE_MISMATCH` | The `requestId` is pending, but for a different device than the `deviceId` supplied |
| `ERR_RESPONSE_OFFSET` | The `offset` given to `sendResponse` is past the offset the request asked for, so the requested bytes would be missing |
| `ERR_NOTIFY` | The Bluetooth stack refused the notification, or reported it as undelivered. A bad address is `ERR_DEVICE_DISCONNECTED` or `ERR_CHARACTERISTIC_NOT_FOUND` instead |
| `ERR_NOTIFY_QUEUE_FULL` | 64 notifications are already queued for the device. Await earlier sends before queueing more |
| `ERR_DEVICE_DISCONNECTED` | The device is not connected, or disconnected -- or on iOS unsubscribed -- before a queued notification could be delivered. Also raised by `getMtu`, `sendResponse` and Android's `disconnectDevice` |
| `ERR_CHARACTERISTIC_NOT_FOUND` | The pair of UUIDs names nothing in the published GATT database. An unknown `serviceUuid` shares this code, since iOS cannot tell the two apart |
| `ERR_UPDATE_VALUE` | An `updateCharacteristicValue` argument was rejected by the native layer |
| `ERR_NO_SUBSCRIBER` | The device has not enabled the transmission `confirm` selects on the characteristic |
| `ERR_CONFIRM_UNSUPPORTED` | `confirm` asks for a transmission the characteristic does not declare the property for -- `indicate` for `true`, `notify` for `false` |
