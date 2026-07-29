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
  - [addServerPublicationFailedListener](#addserverpublicationfailedlistener)
- [Types](#types)
- [Constants](#constants)
- [Error Codes](#error-codes)

## Byte values

Everywhere this API takes bytes -- a characteristic or descriptor `value`, a `sendNotification` or
`sendResponse` payload, `updateCharacteristicValue`, `ManufacturerDataEntry.data`,
`ServiceDataEntry.data` -- the type is `Bytes`:

```typescript
type Bytes = number[] | Uint8Array;
```

A `Uint8Array` is converted to `number[]` in the shared TypeScript layer, because neither native
bridge marshals typed arrays. Event payloads always come back as `number[]`.

## UUID forms

Every UUID this API accepts may be written in any of the three forms the Bluetooth Core Specification
defines: 16-bit (4 hex digits, `'180D'`), 32-bit (8 hex digits, `'0000180D'`) or the hyphenated
128-bit form (`'0000180d-0000-1000-8000-00805f9b34fb'`). This covers service and characteristic
`uuid`, descriptor `uuid`, `AdvertiseConfig.serviceUuids`, `ServiceDataEntry.uuid`, and the
`serviceUuid` / `characteristicUuid` arguments of `sendNotification` and
`updateCharacteristicValue`.

Short forms are **expanded onto the Bluetooth Base UUID in the shared TypeScript layer**. On iOS, `CBUUID` handles short-form conversion automatically; on Android, `UUID.fromString` requires the 8-4-4-4-12 form. The shared layer normalizes all forms before either platform sees them.

The Core Specification defines the expansion (Vol 3, Part B, Section 2.5.1):
```
128_bit_value = 16_bit_value * 2^96 + Bluetooth_Base_UUID (00000000-0000-1000-8000-00805F9B34FB)
```
So `'180D'` and `'0000180D'` both become `0000180d-0000-1000-8000-00805f9b34fb`. Android's `BluetoothLeAdvertiser` transmits the shortest representation, so a 16-bit alias still goes out as two octets.

**Event payloads always carry the lowercase 128-bit form**, on both platforms, regardless of input spelling. This ensures `event.characteristicUuid === MY_UUID` comparisons work reliably. On iOS, `CBUUID.uuidString` uppercases the form and echoes short UUIDs in their input form; on Android, `UUID.toString` is always lowercase 128-bit. The shared layer re-spells every UUID emitted.

`deviceId` is **not** affected. It is an opaque handle — a MAC address on Android, a `CBCentral.identifier` on iOS — and is passed straight back to `getMtu`, `sendNotification`, `sendResponse` and `disconnectDevice`.

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
| `services` | `GattServiceConfig[]` | -- | Array of service definitions. Must be an array; pass `[]` for a database with no services of its own |
| `options.requestTimeoutMs` | `number` | `10000` | How long a delegated request may go unanswered before the module answers it itself |

An explicitly empty `services` array is accepted -- it is how an advertise-only peripheral is built,
since `startAdvertising` requires a published database. Anything that is *not* an array is rejected,
including `undefined`: a `loadServices()` that returned nothing on a failure path used to publish an
empty server and resolve as though the configuration had arrived.

Each service's `characteristics` is required on the same terms, and `[]` declares a service with none
of its own.

Must be called before `startAdvertising`. Calling it again **replaces** the existing server without requiring an explicit `stopServer`.

**The promise resolves only once every service is confirmed published.** On iOS, CoreBluetooth must reach `poweredOn` and acknowledge each service. On Android, services are registered one at a time (`BluetoothGattServer.addService` forbids adding another before the callback).

**Rejects** with:

| Code | When |
|---|---|
| `ERR_PERMISSION` | iOS: `CBManager.authorization` is denied or restricted. Android: `BLUETOOTH_CONNECT` is not granted (API 31+) |
| `ERR_NO_CONTEXT` | Android only: no React context is available, so the permission could not be checked |
| `ERR_UNSUPPORTED` | iOS only: the configuration asks for a property, permission or descriptor CoreBluetooth cannot express -- see the type tables below |
| `ERR_BLUETOOTH` | Bluetooth is off, or the device has no BLE support. Check [`getBluetoothState`](#getbluetoothstate) to tell which |
| `ERR_NO_SERVER` | [`stopServer`](#stopserver) ran before the database finished publishing. Bluetooth going off during publication reports `ERR_BLUETOOTH` instead, on both platforms |
| `ERR_CREATE_SERVER` | A service failed to publish, the configuration was malformed, or registration did not complete within 30 s. Call `createServer` again to retry |

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
| One characteristic declaring the same descriptor UUID twice | **Rejected** |
| The same characteristic UUID in two *different* services | **Accepted** -- GATT permits it, and the pair of UUIDs still names one attribute |
| The same descriptor UUID on two *different* characteristics | **Accepted**, for the same reason |

A repeated descriptor UUID is refused more firmly than the others: on Android the second would shadow
the first, but on iOS `CBMutableCharacteristic.descriptors` raises `NSInternalInconsistencyException`
for a second User Description or Presentation Format descriptor, and an Objective-C exception cannot be
caught from Swift -- so a configuration Android published quietly **terminated the application** there.

UUIDs are compared after normalisation, so `'180d'` and `'0000180D-0000-1000-8000-00805F9B34FB'` count
as the same service. Both platforms repeat the check natively, since the native module is reachable
directly.

#### Delegating requests to JavaScript

By default the module answers ATT requests itself. Set [`delegate`](#characteristicdelegateconfig) to hand requests to JavaScript and answer with [`sendResponse`](#sendresponse).

#### Unanswered requests

A delegated request must be answered via [`sendResponse`](#sendresponse). The Bluetooth Core Specification allows 30 seconds per ATT transaction (Vol 3, Part F, Section 3.3.3); an unanswered request poisons the entire bearer.

The module auto-answers with `ATT_ERROR_UNLIKELY_ERROR` (`0x0e`) after `requestTimeoutMs`. Default is 10000 ms, leaving the central a 20-second margin. A subsequent `sendResponse` rejects with `REQUEST_NOT_FOUND`.

`requestTimeoutMs` must be an integer `0`–`29999`. Values `30000` and up are rejected (central has already given up). Set `0` to disable the timeout.

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

Requires a **published** database: rejects with `ERR_NO_SERVER` before `createServer`, after a service failed to publish, or after Bluetooth went down. Check [`isServerRunning`](#isserverrunning) or await `createServer` first.

**Publication in flight is waited for** on both platforms. `startAdvertising` parks until the database is published, so it is safe immediately after `createServer` resolves, or from a `poweredOn` handler.

A parked call is settled by: terminal Bluetooth state (`ERR_BLUETOOTH`), lost authorization (`ERR_PERMISSION` on iOS), publication failure (`ERR_NO_SERVER`), or `stopServer` / `stopAdvertising` (`ERR_ADVERTISE`). `stopAdvertising` / `stopServer` issued while the call is in flight always win, even if they reach the native side in reverse order — the shared layer tracks call order across the JavaScript/native boundary.

Calling `startAdvertising` again, once the earlier call has settled, replaces the current advertisement.

**Await each call before starting another.** Overlapping calls differ by platform, because `peripheralManagerDidStartAdvertising` names no call while Android's `AdvertiseCallback` is a distinct object per start: iOS rejects the *second* call with `ERR_ADVERTISE` and leaves the first to its own outcome, where Android lets the second displace the first and rejects the *first* with `ERR_ADVERTISE`.

**Rejects** with:

| Code | When |
|---|---|
| `ERR_NO_SERVER` | No server exists, a service failed to publish, or the server was stopped while the call waited for the database |
| `ERR_PERMISSION` | Android: `BLUETOOTH_ADVERTISE` is not granted, or `BLUETOOTH_CONNECT` is not granted while `android.setAdapterName` is set (API 31+). iOS: Bluetooth is unauthorized |
| `ERR_NO_CONTEXT` | Android only: no React context, so the permission could not be checked |
| `ERR_UNSUPPORTED` | iOS: `manufacturerData`, `serviceData` or `connectable: false` was supplied. Android: the adapter has no BLE advertising support, which no amount of retrying changes |
| `ERR_BLUETOOTH` | Bluetooth is off, or the device has no BLE support |
| `ERR_ADVERTISE` | The platform refused the advertisement -- data over the 31-byte budget, too many advertisers, already started, or `android.setAdapterName` without a `localName`. Also reported on both platforms when the stack neither started nor refused the advertisement within 30 s, a bound that exists so a start the platform never answers is reported rather than left pending for the life of the process; nothing is advertising afterwards |
| `ERR_CREATE_SERVER` | The call was waiting for the database and the registration round hit its 30 s bound without the stack acknowledging every service. Unlike the others this one is worth retrying, since nothing retries a timed-out round by itself |

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

`mode`, `txPowerLevel` and `includeTxPowerLevel` are hints about radio behaviour; `manufacturerData`, `serviceData` and `connectable: false` change what a scanner observes. The latter are rejected if unsupported to prevent silent data loss.

`mode` and `txPowerLevel` default to Android's platform defaults (`ADVERTISE_MODE_LOW_POWER`, `ADVERTISE_TX_POWER_MEDIUM`). Pass `mode: 'lowLatency'` for the shortest discovery latency (highest power consumption).

`timeoutMs` is bounded at `180000` (same as `AdvertiseSettings.Builder.setTimeout`). On iOS, it is **emulated** by a module timer that calls `stopAdvertising` when it fires, matching Android's behaviour of simply stopping.

`serviceUuids`, `manufacturerData` and `serviceData` share the 31-byte advertisement budget. An over-budget advertisement rejects with `ERR_ADVERTISE`.

**The advertised name's budget treatment differs:** On Android the name goes in the scan response and doesn't compete with the advertisement. On iOS, `CBAdvertisementDataLocalNameKey` costs its UTF-8 bytes plus 2 out of the 31-byte advertisement budget. A name that fits on Android may reject with `ERR_ADVERTISE` on iOS. CoreBluetooth silently truncates the name and relocates service UUIDs to an Apple-proprietary overflow area (not readable by non-Apple devices), which is why the module rejects the advertisement instead of trimming it. Keep names short for cross-platform compatibility.

The module measures the payload itself on iOS to enforce the budget before CoreBluetooth is asked (which would silently overflow). A 16-bit UUID costs 2 bytes, its 128-bit expansion costs 16, so the module advertises the shortest spelling of each UUID.

#### The advertised local name

On iOS, `localName` is honoured verbatim as `CBAdvertisementDataLocalNameKey`.

**Android has no per-advertisement local name.** `AdvertiseData.Builder` includes only the adapter's name via `setIncludeDeviceName(boolean)`, sized by `BluetoothAdapter.getNameLengthForAdvertise()`.

- **`android.includeDeviceName`** (default when `localName` is set): advertises the device's existing Bluetooth name in the scan response. Nothing is changed.
- **`android.setAdapterName`** (defaults to `false`): renames the adapter to `localName`. This changes the **system-wide** Bluetooth name (visible in device settings and to every peer, Classic and LE). The module restores the previous name on `stopAdvertising`, `stopServer`, module destruction, or when `timeoutMs` elapses. Restoration is best-effort; a process killed while advertising never runs it. **A `stopServer` issued while the adapter is off leaves the name changed permanently** (the restore fails at that moment and the state receiver is unregistered). Prefer `includeDeviceName` unless the exact advertised name is critical.

---

### stopAdvertising

```typescript
stopAdvertising(): void
```

Stop BLE advertisement. Synchronous, and never throws -- including when the native module is absent,
in which case nothing can be advertising anyway.

Does **not** disconnect existing connections or remove services. A `startAdvertising` promise still
in flight rejects with `ERR_ADVERTISE`, since the advertisement it was waiting on has been cancelled.
A `startAdvertising` still waiting for the database is cancelled too, with the same `ERR_ADVERTISE`, on
both platforms: it would otherwise start advertising once the services were published, after the app
had explicitly asked for the opposite. On Android this is also where the adapter name is restored if
`android.setAdapterName` changed it.

---

### sendNotification

```typescript
sendNotification(
  deviceId: string,
  serviceUuid: string,
  characteristicUuid: string,
  value: Bytes,
  options?: SendNotificationOptions,
): Promise<void>
```

Send a notification or indication to a connected central.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `deviceId` | `string` | -- | Target device identifier |
| `serviceUuid` | `string` | -- | Service containing the characteristic |
| `characteristicUuid` | `string` | -- | Characteristic to update |
| `value` | `Bytes` | -- | `number[]` or `Uint8Array` payload |
| `options.confirm` | `boolean` | `false` | `true` for indication (acknowledged), `false` for notification |
| `options.requireSubscription` | `boolean` | `true` | Refuse the send when the device has not subscribed |

> `confirm` was a positional fifth argument and is now an option. A boolean passed in that position
> throws and names the replacement.

#### `options.confirm`: notification or indication

An indication is acknowledged -- the central must reply with an `ATT_HANDLE_VALUE_CFM` and "no
further indications to this client shall occur until the confirmation has been received by the
server" (Vol 3, Part F, Section 3.4.7.2). A notification is fire-and-forget (Section 3.4.7.1).

The characteristic must declare the property that matches, or the call rejects with
`ERR_CONFIRM_UNSUPPORTED`:

| `options.confirm` | Required `properties` entry |
|-------------------|------------------------------|
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

**`sendNotification` does not change what a read returns.** A notification pushes a value to subscribed centrals; the stored attribute value is answered from via ATT Read, changed only by [`updateCharacteristicValue`](#updatecharacteristicvalue). Call both to push *and* make readable:

```typescript
await updateCharacteristicValue(SERVICE, CHARACTERISTIC, value);
await sendNotification(deviceId, SERVICE, CHARACTERISTIC, value);
```

The order ensures the value is readable before the notified central can read it. A characteristic streaming events or deltas may not need storage.

The promise resolves later than the call being handed to the stack. Calls for the same device are queued in order, so awaiting paces a stream against the connection.

**Resolution meaning differs by platform:**

| | Android | iOS |
|---|---|---|
| Resolves when | `onNotificationSent` fires — stack finished transmitting; for indication, central confirmed | `updateValue(_:for:onSubscribedCentrals:)` accepts the payload |
| Means | Delivered | **Queued, not delivered** |
| Indication confirmed? | Yes, implied | Never surfaced |

Treat resolution as "the platform took it", not "the central has it". Do not use it for application-level acknowledgement; have the central write back instead.

**Android** allows one outstanding notification per device; the module queues the rest and sends when callbacks fire.

**iOS** holds payloads that CoreBluetooth cannot immediately queue and resends when `peripheralManagerIsReady(toUpdateSubscribers:)` reports space.

**Rejects** with:

| Code | When |
|---|---|
| `PAYLOAD_EXCEEDS_MTU` | `value` is longer than the link can carry in one notification. Checked **before** anything is transmitted, and re-checked if the MTU shrinks while the send is queued -- nothing is sent and the payload is not truncated. Size payloads against [`getMtu`](#getmtu)`.maxNotificationPayload` |
| `ERR_CONFIRM_UNSUPPORTED` | The characteristic does not declare the property `confirm` asks for |
| `ERR_NO_SUBSCRIBER` | The device has not enabled the transmission on the characteristic |
| `ERR_NOTIFY_QUEUE_FULL` | 64 sends are already waiting for the same device. Await earlier sends first |
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
  value: Bytes,
): Promise<void>
```

Answer a pending read or write request -- one delivered by
[`addCharacteristicReadRequestListener`](#addcharacteristicreadrequestlistener) or by
[`addCharacteristicWriteRequestListener`](#addcharacteristicwriterequestlistener) with
`responseNeeded: true`.

| Parameter | Type | Description |
|-----------|------|-------------|
| `deviceId` | `string` | Requesting device identifier, from the event |
| `requestId` | `number` | Request ID from the event. Must be a whole number `0`--`9007199254740991`; `NaN`, an infinity or a fraction is rejected rather than narrowed. Both platforms narrow further, to `2147483647`, so an id above that rejects natively -- unreachable with an id the module issued |
| `status` | `number` | `GATT_SUCCESS` or an `ATT_ERROR_*` code. Must be an integer `0`--`255`, since an ATT error code is a single octet |
| `offset` | `number` | The offset within the attribute at which `value` begins. Must be an integer `0`--`65535` |
| `value` | `number[]` | Response byte array, starting at `offset`. Not transmitted for a write -- an `ATT_WRITE_RSP` carries no value -- so pass `[]` |

Answering with an `ATT_ERROR_*` status is the **only** way to reject a write; requires [`delegate.write`](#characteristicdelegateconfig). Other writes are auto-answered before the event is emitted.

`offset` specifies where `value` begins within the attribute; the response is rebased onto the request's offset. Both approaches below are correct and equivalent:

```typescript
// Pass the whole value and let the module take the part the request asked for.
await sendResponse(deviceId, requestId, GATT_SUCCESS, 0, wholeValue);

// Or slice it yourself and say where the slice starts.
await sendResponse(deviceId, requestId, GATT_SUCCESS, event.offset, wholeValue.slice(event.offset));
```

**Rejects** with:

| Code | When |
|---|---|
| `REQUEST_NOT_FOUND` | Invalid, already answered, or expired after `requestTimeoutMs` |
| `REQUEST_DEVICE_MISMATCH` | Request belongs to a different device |
| `ERR_RESPONSE_OFFSET` | `offset` is past the request's offset, leaving requested bytes missing |
| `ERR_DEVICE_DISCONNECTED` | Central disconnected (**Android only**) |
| `ERR_BLUETOOTH` | Bluetooth went off in the race between server drop and cleanup (**Android only**) |
| `ERR_RESPONSE` | Stack rejected the response (**Android only**; iOS resolves regardless) |

The request is looked up first, so answering one the server no longer holds (from `stopServer` or Bluetooth power cycle) reports `REQUEST_NOT_FOUND`. `stopServer` answers and discards every pending request.

`value` is not size-checked against the MTU. An `ATT_READ_RSP` carries at most `ATT_MTU - 1` octets; the central uses Read Blob to fetch longer values as separate requests.

---

### updateCharacteristicValue

```typescript
updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: Bytes,
): Promise<void>
```

Update the stored value of a characteristic. Read requests are auto-answered from this value, unless the characteristic has [`delegate.read`](#characteristicdelegateconfig), in which case every read goes to JavaScript.

This is the **only** call that changes what a read returns (besides auto-acknowledged writes). It does **not** send a notification; [`sendNotification`](#sendnotification) is independent. Call both to push *and* make readable.

A write batch outstanding when this resolves has its held value dropped instead. See [a batch that is only partly delegated](#a-batch-that-is-only-partly-delegated).

`value` may hold at most **512 octets**, the specification's maximum attribute value length (Core Spec
Vol 3, Part F, §3.2.9). The same bound applies to a `value` in the `createServer` configuration, on
both platforms. It is enforced wherever the value comes from, not only on writes arriving from a
central: a longer attribute could never be notified -- `sendNotification` caps at `min(mtu - 3, 512)` --
and only a conformant Read Blob could retrieve it in full.

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

Read the current Bluetooth adapter state. Safe to call before `createServer`. Resolves to `'unsupported'` where the native module is absent (same as the state already means, so `isSupported` check is not needed separately).

See [`BluetoothState`](#bluetoothstate) for states and [`addBluetoothStateChangedListener`](#addbluetoothstatechangedlistener) to observe changes.

**iOS limitation:** Before a server exists, `CBPeripheralManager.state` requires instantiation, which would trigger the Bluetooth permission prompt. iOS answers `'unauthorized'` if denied/restricted and `'unknown'` otherwise — never `'poweredOn'` or `'poweredOff'`. Android reads `BluetoothAdapter.getState()` without permission or server, so it is always accurate.

---

### getMtu

```typescript
getMtu(deviceId: string): Promise<DeviceMtu>
```

Read the current ATT MTU for a connected device, so payloads can be sized before they are sent.

**ATT MTU in octets**. `maxNotificationPayload` is what to size `sendNotification` against: `min(mtu - 3, 512)`. The 512-octet bound is the maximum attribute value length (Core Spec Vol 3, Part F, §3.2.9) and binds above ATT_MTU 515.

A device without negotiated MTU reports the specification default of `23`.

**Rejects** with `ERR_DEVICE_DISCONNECTED` (device not connected; on iOS "not connected" means "not known" — see [`getConnectedDevices`](#getconnecteddevices)) or `ERR_NO_SERVER`.

| Platform | `mtu` | `maxNotificationPayload` |
|----------|-------|--------------------------|
| Android | `BluetoothGattServerCallback.onMtuChanged` | `min(mtu - 3, 512)` |
| iOS | `maximumUpdateValueLength + 3` | `min(maximumUpdateValueLength, 512)` |

CoreBluetooth exposes only payload length, not MTU. On iOS, `mtu` is reconstructed by adding 3 header octets (not documented by Apple). Prefer `maxNotificationPayload` on iOS.

---

### getConnectedDevices

```typescript
getConnectedDevices(): Promise<ConnectedDevice[]>
```

List the centrals the module currently considers connected. Resolves to `[]` when no server exists.

**Platform difference:** "Connected" differs on iOS and Android:
- **Android:** Every central with a link to this server (from `BluetoothGattServerCallback.onConnectionStateChange`).
- **iOS:** Centrals that have touched an attribute (subscribed, read, or written). A central that connects without ATT activity is **absent**. One that unsubscribes while staying connected is dropped early.

The list is the module's own tracking on both platforms, not a platform query. (Android does not use `BluetoothManager.getConnectedDevices`, which would include other apps' GATT servers.)

---

### disconnectDevice

```typescript
disconnectDevice(deviceId: string): Promise<void>
```

Drop a connected central. **Android only.**

Calls `BluetoothGattServer.cancelConnection` ("Disconnects an established connection, or cancels a connection attempt currently in progress"). Returns `void`, so the promise resolves when the request reaches the stack, not when the central is gone — wait for `onDeviceDisconnected` for that. Requires `BLUETOOTH_CONNECT` (API 31+).

**iOS rejects with `ERR_UNSUPPORTED` always.** `CBPeripheralManager` has no disconnect method. `CBCentral` exposes only `identifier` and `maximumUpdateValueLength`. `cancelPeripheralConnection(_:)` exists but is a `CBCentralManager` method for connections *this* device opened. On iOS, only the central can end the connection.

**Android rejects** with: `ERR_PERMISSION` (`BLUETOOTH_CONNECT` not granted, API 31+), `ERR_NO_CONTEXT` (no React context), `ERR_NO_SERVER` (no server), `ERR_BLUETOOTH` (Bluetooth off), `ERR_DEVICE_DISCONNECTED` (device not connected), or `ERR_DISCONNECT` (stack failure).

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

Shut down the GATT server: stop advertising, unpublish every service, and release native resources. Synchronous, never throws (even when the native module is absent).

**Does not disconnect.** On Android, use [`disconnectDevice`](#disconnectdevice) first if needed; on iOS, only the central can disconnect. Connected centrals lose attribute access.

**No `onDeviceDisconnected` events are emitted.** The module discards its own tracking without reporting an unobserved disconnection, so `getConnectedDevices` goes empty while actual links remain.

**Pending work is settled:** Unanswered delegated requests are answered with `ATT_ERROR_UNLIKELY_ERROR` (so the central's 30-second transaction doesn't poison the bearer). Unresolved `createServer` rejects with `ERR_NO_SERVER`, queued notifications reject, pending `startAdvertising` rejects with `ERR_ADVERTISE` or `ERR_NO_SERVER`, and the adapter name is restored on Android if `android.setAdapterName` changed it.

Both platforms unpublish the database and stop listening for adapter state, so a later `createServer` starts fresh (services are not re-published if Bluetooth is cycled).

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

Fired once per connected central, independently of subscription.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier (UUID on iOS, MAC address on Android) |
| `event.name` | `string?` | `BluetoothDevice.getName()` on Android; always `''` on iOS |

**Platform difference:** iOS reports the first ATT activity (subscribe, read, or write), not the connection itself. A central that connects without touching an attribute is **not** observable. Android reports the connection from `onConnectionStateChange`.

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

**iOS limitation:** CoreBluetooth never reports disconnection, so the module treats **losing the last subscription** as one. Side effects:
- A central that only read/wrote produces **no** disconnect event when it leaves; it generally stays in `getConnectedDevices` until Bluetooth off or server stops.
- A central that unsubscribes but stays connected is reported **early**; a later read/write re-discovers it, producing a fresh `onDeviceConnected`.

CoreBluetooth cannot distinguish between "cleared CCCD" and "vanished". Android reports actual disconnection.

`stopServer` emits nothing (see [stopServer](#stopserver)).

---

### addCharacteristicReadRequestListener

```typescript
addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
): EventSubscription
```

Fired when a central reads a characteristic the module is not answering itself. **Every delegated request must be answered with [`sendResponse`](#sendresponse)** or it completes with `ATT_ERROR_UNLIKELY_ERROR` after `requestTimeoutMs`.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Requesting device |
| `event.requestId` | `number` | Use in `sendResponse`. Valid only until answered/expired; **unique only per device on Android** (see below) |
| `event.serviceUuid` | `string` | Service UUID, or `''` if platform cannot identify it (**Android only**; iOS fails unresolvable characteristics with `ATT_ERROR_UNLIKELY_ERROR` instead) |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Read offset; non-zero for Read Blob continuation |

**On Android, `requestId` is not globally unique:** the stack assigns from a per-connection counter, so multiple centrals both produce `1`, `2`, `3`. iOS uses a module-wide counter. `sendResponse` is safe (given `deviceId` and `requestId`), but a listener keeping its own state must key both:

```ts
pending.set(`${event.deviceId}:${event.requestId}`, context); // Correct on both platforms
```

**A read reaches this listener in exactly two cases:**
1. No cached value (omitted from config, never set via `updateCharacteristicValue`). Once a value exists, the module answers and stops firing this event.
2. Configured with [`delegate.read`](#characteristicdelegateconfig), which keeps every read coming to JavaScript.

A read with offset past the end of the cached value is answered with `ATT_ERROR_INVALID_OFFSET` (`0x07`, Core Spec Vol 3, Part F, Section 3.4.1.1) and never reaches this listener.

---

### addCharacteristicWriteRequestListener

```typescript
addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
): EventSubscription
```

Fired when a central writes to a characteristic, whether or not JavaScript has to answer it.

A write the ATT layer refuses raises no event, because nothing was written: an offset past the end of
the attribute is answered `ATT_INVALID_OFFSET`, and a value that would assemble past
`MAX_ATTRIBUTE_VALUE_LENGTH` is answered `ATT_INVALID_ATTRIBUTE_VALUE_LENGTH`, on both platforms. A
Write Without Response cannot be answered at all, so a refused one is dropped with only a native log
line.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Writing device |
| `event.requestId` | `number` | Pass to `sendResponse` when `responseNeeded` is `true`. Unique only per device -- see the note under `onCharacteristicReadRequest` |
| `event.serviceUuid` | `string` | Service UUID, or `''` when the platform could not identify the owning service (**Android only** -- on iOS an unresolvable characteristic fails the request with `ATT_ERROR_UNLIKELY_ERROR` instead of raising the event) |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Where `value` begins within the attribute. Always `0` for a reassembled long write |
| `event.value` | `number[]` | Written byte array |
| `event.responseNeeded` | `boolean` | Whether the module is **waiting for JavaScript** to answer this request -- see below |

#### `responseNeeded` means "you must answer this"

`responseNeeded: true` when the module is holding the ATT transaction for [`sendResponse`](#sendresponse). This happens exactly when the characteristic has [`delegate.write`](#characteristicdelegateconfig) **and** the write carries a response. It's decided per characteristic; a batch touching delegated and plain characteristics emits two events, only the delegated one asks for an answer.

Other writes are auto-acknowledged with `GATT_SUCCESS` before the event fires, arriving with `responseNeeded: false`. Calling `sendResponse` for those rejects with `REQUEST_NOT_FOUND`.

**Platform difference:** Android's `onCharacteristicWriteRequest` carries a `responseNeeded` flag, so the module never delegates Write Without Response. On iOS, `CBATTRequest` doesn't distinguish `ATT_WRITE_REQ` from `ATT_WRITE_CMD`, and CoreBluetooth requires `respond(to:withResult:)` for every invocation. So on iOS, Write Without Response to a delegated characteristic arrives with `responseNeeded: true` and waits for `sendResponse`, expiring after `requestTimeoutMs` if unanswered. Declare `writeNoResponse` on a delegated characteristic only if that's acceptable.

When `responseNeeded: true`, the write is **not applied** until JavaScript accepts it. Answer with `GATT_SUCCESS` and commit via [`updateCharacteristicValue`](#updatecharacteristicvalue), or answer with an `ATT_ERROR_*` code to reject. Unanswered requests complete with `ATT_ERROR_UNLIKELY_ERROR` after `requestTimeoutMs`.

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

One write batch (CoreBluetooth `didReceiveWrite:` array or Android reliable-write execute) can touch multiple characteristics with different delegation settings. Delegation is **per characteristic** and the batch is atomic:

- Each characteristic gets its own event; `responseNeeded: true` only on delegated ones.
- Plain characteristics' values are **held, not applied**, and committed if the batch answers `GATT_SUCCESS`. An `ATT_ERROR_*` answer or timeout after `requestTimeoutMs` discards them.
- If nothing in the batch delegates, it's applied and acknowledged immediately.
- A held value is **dropped** if anything wrote that characteristic while the batch was outstanding (via `updateCharacteristicValue` or another central's write). The newer value stands; the batch still answers with your status.

Deferring keeps the batch all-or-nothing: CoreBluetooth requires "if execution of one request would fail, none should execute" (Vol 3, Part F, Section 3.4.6.3). Rejecting a delegated write rolls back plain characteristics in the same batch.

**iOS shares one `requestId` across a batch:** CoreBluetooth delivers writes as an array and requires one `respond(to:withResult:)` per callback. One batch emits events per attribute, all with the **same** `requestId`. Only one event has `responseNeeded: true`; answering it covers the whole batch. Other delegated attributes still get events and can commit with `updateCharacteristicValue`, but must not call `sendResponse` again (second call rejects with `REQUEST_NOT_FOUND`). Android reliable writes behave the same way (single request).

Long writes are **not** replayed as fragments: split `ATT_PREPARE_WRITE_REQ` PDUs raise one event per attribute with the assembled value at `offset: 0`.

#### Long writes and reliable writes

A long value uses the queued-write procedure: `ATT_PREPARE_WRITE_REQ` PDUs carry fragments with offsets, then `ATT_EXECUTE_WRITE_REQ` applies or cancels (Vol 3, Part F, Section 3.4.6; Vol 3, Part G, Sections 4.9.4/4.9.5).

**Android:**
- Buffers fragments per device, echoing each in the prepare response as specified.
- On execute with flag `0x01`: assembles fragments onto the attribute's current value, applies atomically. Emits one event per attribute with reassembled value at `offset: 0`.
- Execute flag `0x00`: discards everything, no events.
- Fragment past the attribute's end fails the execute with `ATT_ERROR_INVALID_OFFSET`, discards the queue.
- Over 64 fragments rejected with `ATT_ERROR_PREPARE_QUEUE_FULL`; already-queued fragments survive (per spec).
- Queue is per device, dropped on device disconnect, Bluetooth off, or `stopServer`.

If any characteristic in the execute has `delegate.write`, the execute waits for `sendResponse` (single `requestId` for the whole operation). Plain characteristics' values are held until answered.

**iOS:**
- No prepared-write callbacks in `CBPeripheralManagerDelegate`. CoreBluetooth handles the procedure internally.
- Long writes reach JavaScript as one event per attribute with the assembled value at `offset: 0`.
- A delegated characteristic can reject the batch with an `ATT_ERROR_*` in `sendResponse`, failing the whole batch.
- **Cannot distinguish** plain `ATT_WRITE_REQ` from executed prepared writes (arrive through the same callback).
- The module infers by **offsets**: a write past offset 0 must be from queued-write procedure (ATT_WRITE_REQ carries no offset). This matters because the same callback delivers multiple coalesced Write Without Response commands, each at offset 0 — each is separate and replaces the value.
- **Limitation:** A long write delivered as a single part at offset 0 is indistinguishable from an ordinary write and is treated as one, replacing the value instead of splicing at offset 0.

---

### addNotificationSentListener

```typescript
addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
): EventSubscription
```

Fired when the platform finishes with a notification or indication.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Target device |
| `event.characteristicUuid` | `string` | Notified characteristic |
| `event.status` | `number` | `0` for success; platform GATT status otherwise |

`characteristicUuid` always identifies which characteristic this notification carried.

`sendNotification`'s promise carries the same outcome and is usually better (identifies which send finished). This event observes traffic the app didn't initiate.

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

Fired when a connection's MTU changes or is observed for the first time. Carries the same fields as [`getMtu`](#getmtu).

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | The device whose MTU changed |
| `event.mtu` | `number` | ATT MTU in octets |
| `event.maxNotificationPayload` | `number` | `min(mtu - 3, 512)` |

Both platforms report the starting MTU alongside `onDeviceConnected`. Android `onMtuChanged` fires only if the central negotiates (many don't), so an initial event ensures a peripheral can size payloads.

**Platform difference:** Android delivers changes from `BluetoothGattServerCallback.onMtuChanged` as they happen. iOS has no MTU callback; the value is sampled on each ATT activity (subscribe, read, write) and the event fires when it differs from the last-seen value (surfaces at next activity, not at change moment).

---

### addCharacteristicSubscribedListener

```typescript
addCharacteristicSubscribedListener(
  listener: (event: CharacteristicSubscribedEvent) => void,
): EventSubscription
```

Fired when a central enables notifications or indications on a characteristic. This signals to start streaming.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Subscribing device |
| `event.serviceUuid` | `string` | Owning service, or `''` if platform cannot identify it |
| `event.characteristicUuid` | `string` | Subscribed characteristic |

Android tracks CCCD writes per client (Vol 3, Part G, Section 3.3.3.3). iOS uses `peripheralManager(_:central:didSubscribeTo:)`.

**CoreBluetooth limitation:** Whether the central requested notifications or indications is not reported. Switching between them doesn't emit a further event.

---

### addCharacteristicUnsubscribedListener

```typescript
addCharacteristicUnsubscribedListener(
  listener: (event: CharacteristicUnsubscribedEvent) => void,
): EventSubscription
```

Fired when a central stops receiving updates for a characteristic — the signal to stop streaming. Also emitted for every subscription a central held when it disconnects, or when Bluetooth goes off.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Unsubscribing device |
| `event.serviceUuid` | `string` | Owning service, or `''` if platform cannot identify it |
| `event.characteristicUuid` | `string` | Characteristic no longer subscribed |

On iOS, losing the **last** subscription is also read as a disconnection, so this event is immediately followed by `onDeviceDisconnected`. See [addDeviceDisconnectedListener](#adddevicedisconnectedlistener).

---

### addBluetoothStateChangedListener

```typescript
addBluetoothStateChangedListener(
  listener: (event: BluetoothStateChangedEvent) => void,
): EventSubscription
```

Fired whenever the Bluetooth adapter state changes. See [`BluetoothState`](#bluetoothstate).

| Field | Type | Description |
|-------|------|-------------|
| `event.state` | `BluetoothState` | The new adapter state |

**Delivered only while a server exists.** Android registers a receiver for `BluetoothAdapter.ACTION_STATE_CHANGED` in `createServer`/`stopServer`. iOS gets the state from `peripheralManagerDidUpdateState`, which requires an instantiated `CBPeripheralManager`. Use [`getBluetoothState`](#getbluetoothstate) for a one-off read outside that window.

Both platforms deliver an initial event (Android on creation, iOS shortly after when CoreBluetooth resolves). Android doesn't re-report a sticky state, so the initial event lets listeners registered after Bluetooth was already on respond to `'poweredOn'`.

**`poweredOff`** destroys the GATT database on both platforms. Expect `onCharacteristicUnsubscribed` and `onDeviceDisconnected` for everything live. The module re-publishes services on the next `poweredOn` transition (check [`isServerRunning`](#isserverrunning)), but **advertising is not restarted** — call `startAdvertising` again. The event fires before re-publication finishes, but `startAdvertising` parks until services publish, so calling it directly from the handler works:

```typescript
addBluetoothStateChangedListener(async ({ state }) => {
  if (state !== 'poweredOn') return;
  await startAdvertising({ serviceUuids: [SERVICE_UUID] });
});
```

If Bluetooth goes off before re-publication finishes, that call rejects with `ERR_BLUETOOTH`.

**iOS `resetting`:** `CBManagerState.resetting` clears the database and disconnects every central (like `poweredOff`). `isServerRunning` goes `false`. Services re-publish on `poweredOn` after. Do not assume connections or subscriptions survive.

**Android `resetting`:** Only a label for `STATE_TURNING_OFF`/`STATE_TURNING_ON`. Neither tears anything down; the actual teardown happens on `STATE_OFF`. `isServerRunning` stays `true` through `resetting`, and disconnection events arrive later. To detect a lost database reliably, branch on `poweredOff` or check `isServerRunning`, not on `resetting` alone.

### addServerPublicationFailedListener

```typescript
addServerPublicationFailedListener(
  listener: (event: ServerPublicationFailedEvent) => void
): EventSubscription
```

Fires when the published database goes away for a reason no promise reported.

The module re-publishes services on every `poweredOn` / `STATE_ON` transition. Re-publication can fail (platform refuses a service, or registration times out), and by then `createServer` has resolved. Only a parked `startAdvertising` would hear about it.

The database is absent afterwards: `isServerRunning` reports `false`, nothing retries. Recovering requires calling `createServer` again.

**Not** emitted when Bluetooth is turned off (handled by [`addBluetoothStateChangedListener`](#addbluetoothstatechangedlistener)), or when `createServer` / parked `startAdvertising` already rejected with the same failure (to avoid double-reporting).

```typescript
addServerPublicationFailedListener(({ code, message }) => {
  console.warn(`the GATT database is gone: ${code} ${message}`);
  createServer(SERVICES);
});
```

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

A secondary service "is a service that is included from another service" (Core Specification, Vol 3, Part G, Section 3.1). This module publishes every service at the top level with no way to include one from another, so a `secondary` service will not be found by primary service discovery.

### GattCharacteristicConfig

```typescript
interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  value?: Bytes;
  descriptors?: GattDescriptorConfig[];
  delegate?: CharacteristicDelegateConfig;
}
```

`value` is what reads are answered from. Omit it to delegate every read to JavaScript. `value: []` declares a zero-length attribute (a legitimate GATT state, both platforms cache and answer it). Omit `value` entirely for delegating.

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

Per-characteristic opt-in delegation of ATT request handling to JavaScript. Both flags default to `false`. Set on [`GattCharacteristicConfig.delegate`](#gattcharacteristicconfig).

| Flag | Effect |
|---|---|
| `read` | Always emit `onCharacteristicReadRequest` and wait for `sendResponse`, even with a cached value. Without it, the module answers from the last value and the event stops firing — necessary for computed/dynamic reads. Doesn't affect notifications, which always carry the `sendNotification` payload |
| `write` | Never auto-acknowledge writes. Write stays unapplied/unanswered until `sendResponse` is called with `GATT_SUCCESS` or `ATT_ERROR_*`. **Only way to reject a write** |

**Notes:**
- Android never delegates Write Without Response (carries nothing to answer). iOS can't distinguish `ATT_WRITE_REQ` from `ATT_WRITE_CMD`, so it delegates like any other write. See [addCharacteristicWriteRequestListener](#addcharacteristicwriterequestlistener).
- Delegation is per characteristic. A batch touching delegated and plain characteristics applies the plain one's value if the batch is accepted, discards it if rejected, or leaves it if something wrote that characteristic meanwhile. See [addCharacteristicWriteRequestListener](#addcharacteristicwriterequestlistener).
- iOS batches share a single `requestId` (one `sendResponse` answers all). Android reliable-write execute is a single request, behaves the same way.

### GattDescriptorConfig

```typescript
interface GattDescriptorConfig {
  uuid: string;
  value: Bytes;
  permissions?: CharacteristicPermission[];
}
```

Descriptors published alongside the Client Characteristic Configuration descriptor the module adds
itself.

`value` is required (iOS documents it as "required and cannot be updated dynamically once published"; requiring it everywhere keeps the config portable). `permissions` defaults to `['readable']` and is **ignored on iOS** — `CBMutableDescriptor` has no permissions parameter; CoreBluetooth derives them from descriptor type.

| Descriptor | Android | iOS |
|---|---|---|
| `0x2901` Characteristic User Description | published | published; UTF-8 decoded (Apple models as `NSString`), invalid UTF-8 rejects with `ERR_UNSUPPORTED` |
| `0x2904` Characteristic Presentation Format | published | published, bytes verbatim |
| `0x2902` Client Characteristic Configuration | **rejected** | **rejected** |
| anything else (e.g. `0x2900`, `0x2903`) | published | **rejected** with `ERR_UNSUPPORTED` |

`CBMutableDescriptor` supports only User Description and Presentation Format; Client Characteristic Configuration and Extended Properties are "created automatically upon publication". Declaring other UUIDs is refused on iOS (would fail the whole service). Declare such descriptors for Android only.

CCCD is rejected on both platforms: the module publishes it for characteristics declaring `notify` or `indicate` and answers reads/writes from per-device tracking (spec requires "each client its own instantiation"; platforms hand out one shared object). A manually declared CCCD would shadow it. CCCD write permission comes from the parent characteristic (see [Permissions and subscriptions](#permissions-and-subscriptions)).

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

Apple documents both as "Not allowed for local characteristics". They're offered because Android sets the bits; a peripheral targeting Android alone can legitimately want them — declare for Android only.

Unrecognised names throw (rather than being silently ignored), so a typo can't publish with fewer properties than intended.

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

`CBAttributePermissions` has four members (`readable`, `writeable`, `readEncryptionRequired`, `writeEncryptionRequired`), not eight. The rejected variants are **not approximated** because all near equivalents are weaker: MITM requires authenticated pairing (not just encryption), signed requires a signature over unencrypted. Mapping them would publish less protection than declared without saying so. The error names `readEncrypted` / `writeEncrypted` as the portable choice.

Unrecognised names throw (rather than being silently ignored), so a typo can't publish with less protection than intended.

#### Permissions and subscriptions

A permission is enforced on the attribute that carries it. Android resolves `p_attr->permission` from the handle being accessed (doesn't inherit from parent characteristic); `GATTS_HandleValueNotification` performs no permission, encryption, or subscription check. Notifications escape every check a read faces — so the **CCCD write**, which opens the stream, carries a permission derived from the parent characteristic rather than a fixed `PERMISSION_WRITE`:

| Characteristic declares | CCCD read | CCCD write |
|---|---|---|
| neither `readEncrypted*` nor `writeEncrypted*` | `PERMISSION_READ` | `PERMISSION_WRITE` |
| `readEncrypted` or `readEncryptedMitm` | `PERMISSION_READ` | `PERMISSION_WRITE_ENCRYPTED` (or MITM variant) |
| `writeEncrypted` or `writeEncryptedMitm` | `PERMISSION_READ` | `PERMISSION_WRITE_ENCRYPTED` (or MITM variant) |

Only the write is derived; the strongest encryption level declared in *either* direction wins (subscription enables transmission). `writeSigned` and `writeSignedMitm` don't contribute (they constrain inbound write PDUs; CCCD uses an ordinary write).

**CCCD read is never protected** (Core Spec Vol 3, Part G, Table 3.10 specifies "Readable with no authentication or authorization"). The module answers from per-client tracking, revealing nothing about the value or other clients; unsubscribed clients read back `0x0000`. Refusing it breaks descriptor discovery, which a conformant central does before subscribing.

**Behaviour change with encrypted permissions:** A client below that level is now refused at CCCD *write* with `GATT_INSUF_ENCRYPTION`/`GATT_INSUF_AUTHENTICATION`, where before it could subscribe and receive values in cleartext. CCCD *read* still succeeds.

**iOS mechanism:** CoreBluetooth owns the CCCD and never exposes it. `CBAttributePermissions` guards reads/writes of the value. The gate on subscribing is the separate property "only trusted devices can enable notifications/indications". A characteristic declaring `notify`/`indicate` with `readEncrypted` or `writeEncrypted` is published with `.notifyEncryptionRequired` / `.indicateEncryptionRequired` added:

| Characteristic declares | iOS properties published |
|---|---|
| `notify`, no encrypted permission | `.notify` |
| `notify` + `readEncrypted` or `writeEncrypted` | `.notify`, `.notifyEncryptionRequired` |
| `indicate` + `readEncrypted` or `writeEncrypted` | `.indicate`, `.indicateEncryptionRequired` |

These are derived (not offered as separate [`CharacteristicProperty`](#characteristicproperty) names), so one config means the same thing on both platforms. The plain member is kept (sets the bit in Vol 3, Part G, Table 3.5 that centrals read before subscribing).

**Android limitation:** `sendNotification` with `requireSubscription: false` transmits to an unsubscribed device. Android offers nothing to check encryption at this layer (`BluetoothDevice.isEncrypted()` is `@hide`; `getBondState()` is unreliable for LE). Leave `requireSubscription` at `true` for characteristics needing secure links. iOS has no gap (`updateValue` "ignores centrals that haven't subscribed").

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
  data: Bytes;
}
```

`companyId` is the 16-bit Bluetooth SIG Company Identifier; `0xFFFF` is reserved for development and testing.

### ServiceDataEntry

```typescript
interface ServiceDataEntry {
  uuid: string;
  data: Bytes;
}
```

### AndroidAdvertiseOptions

```typescript
interface AndroidAdvertiseOptions {
  includeDeviceName?: boolean;
  setAdapterName?: boolean;
}
```

Options with no CoreBluetooth counterpart; the whole object is ignored on iOS. Both concern the advertised name (see [The advertised local name](#the-advertised-local-name)). `includeDeviceName` defaults to `true` when `localName` is set, `false` otherwise; costs the name's length plus 2 bytes of the scan response's 31-byte budget. `setAdapterName` defaults to `false`.

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
  /** Octets that fit in one notification or indication: `min(mtu - 3, 512)`. */
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

Adapter state, normalised so consumers never branch on platform.

| State | Meaning | iOS | Android |
|---|---|---|---|
| `poweredOn` | Only state where a server can advertise | `CBManagerState.poweredOn` | `STATE_ON` |
| `poweredOff` | Bluetooth off; destroys the published database | `CBManagerState.poweredOff` | `STATE_OFF` |
| `resetting` | Transient; don't act yet | `CBManagerState.resetting` | `STATE_TURNING_ON` / `STATE_TURNING_OFF` |
| `unsupported` | No BLE peripheral support; also reported when native module is absent | `CBManagerState.unsupported` | No `BluetoothAdapter` |
| `unauthorized` | App may not use Bluetooth | `CBManagerState.unauthorized` | Not reported; Android surfaces as rejected calls instead |
| `unknown` | Not determined yet | Reported until first callback; by `getBluetoothState` before server exists | Only when no React context available |

### ServerPublicationFailedEvent

```typescript
interface ServerPublicationFailedEvent {
  code: string;
  message: string;
}
```

`code` is the same code the equivalent `createServer` rejection would have carried, usually
`ERR_CREATE_SERVER`. See [`addServerPublicationFailedListener`](#addserverpublicationfailedlistener).

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
  onServerPublicationFailed(event: ServerPublicationFailedEvent): void;
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

ATT error codes are single octets (Core Spec 5.4, Vol 3, Part F, Table 3.4). `sendResponse` rejects anything outside `0`–`255`.

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

Codes above `0x11` are not exposed: iOS `CBATTError.Code` stops at `0x11`. Spec's `0x12`, `0x13`, application (`0x80`–`0x9F`), and profile (`0xE0`–`0xFF`) ranges have no iOS representation (arrive as Unlikely Error). `sendResponse` still accepts any byte, so passing higher codes to Android alone works but has no portable meaning.

### Other constants

| Constant | Value | Description |
|----------|-------|-------------|
| `ATT_TRANSACTION_TIMEOUT_MS` | `30000` | The ATT transaction timeout (Core Specification, Vol 3, Part F, Section 3.3.3). The exclusive upper bound on `CreateServerOptions.requestTimeoutMs` |
| `DEFAULT_REQUEST_TIMEOUT_MS` | `10000` | The default `CreateServerOptions.requestTimeoutMs` |
| `MAX_ATTRIBUTE_VALUE_LENGTH` | `512` | The longest value an attribute may hold (Core Specification, Vol 3, Part F, Section 3.2.9). Bounds a configured `value`, anything `updateCharacteristicValue` writes, and what a queued write may assemble to |
| `CLIENT_CHARACTERISTIC_CONFIGURATION_UUID` | `'00002902-0000-1000-8000-00805f9b34fb'` | The CCCD UUID, in the 128-bit form both platforms compare against. The module publishes and answers this descriptor itself, so declaring it in `descriptors` is rejected; the constant is exported for recognising it |

## Error Codes

Argument validation (malformed UUIDs, bytes outside `0`–`255`, out-of-range timeouts, unrecognised enum names) throws a plain `Error` with **no** `code` (never reaches platform).

Shared-layer cancellations carry a `code`: `createServer` rejects `ERR_NO_SERVER` and `startAdvertising` rejects `ERR_ADVERTISE` when a stop was issued in flight. Missing native module (web, Expo Go) throws without `code`. Branch on `code` to tell one failure from another.

**The same situation reports the same code on both platforms.** Where a code is marked as platform-specific below, only that platform has the situation — not that the other reports it differently. `code` can be branched on without `Platform.OS`. Messages differ.

When a call has **more than one** fault, both platforms check in the same order and report the first one. `sendNotification`'s order is [given with the call](#sendnotification).

| Code | Description |
|------|-------------|
| `ERR_UNSUPPORTED` | The call, configuration or advertising option cannot be expressed on this platform, so retrying never helps. On iOS: `disconnectDevice`, `manufacturerData` / `serviceData` / `connectable: false`, MITM and signed permissions, the `broadcast` and `extendedProperties` properties, and any descriptor other than `0x2901` / `0x2904`. On Android: an adapter with no BLE advertising support |
| `ERR_NO_SERVER` | No server exists, or it was stopped while the call was in flight. A database missing because Bluetooth is off is `ERR_BLUETOOTH` instead, on both platforms |
| `ERR_PERMISSION` | Bluetooth permission is not granted -- `CBManager.authorization` on iOS, `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` on Android (API 31+) |
| `ERR_NO_CONTEXT` | **Android only.** No React context was available, so the permission could not be checked. Treated as a failure rather than a pass, because assuming the grant only defers it to a `SecurityException`. It is the same class of failure as `ERR_PERMISSION` and has no iOS counterpart, so handle both wherever you handle a missing grant |
| `ERR_BLUETOOTH` | Bluetooth is off, unsupported or otherwise not ready. This is how every call reports a powered-off adapter, including `createServer`, `startAdvertising`, `sendNotification`, `updateCharacteristicValue` and `disconnectDevice` -- the published database does not survive the adapter going down, and the module re-publishes it when Bluetooth returns, so this is the retryable one |
| `ERR_CREATE_SERVER` | A service failed to publish, or the configuration was rejected by the native layer |
| `ERR_ADVERTISE` | The platform refused the advertisement, or a pending `startAdvertising` was superseded by another one, by `stopAdvertising` or by `stopServer` |
| `ERR_RESPONSE` | The Bluetooth stack did not accept a `sendResponse`, or its arguments were rejected by the native layer |
| `ERR_DISCONNECT` | **Android only.** `disconnectDevice` failed for a reason the stack did not classify |
| `PAYLOAD_EXCEEDS_MTU` | A `sendNotification` payload is longer than one notification can carry (`min(mtu - 3, 512)`). Checked before transmitting, so nothing was sent. The message says when the link is still at the default ATT MTU of 23 |
| `REQUEST_NOT_FOUND` | The `requestId` does not match a pending read or write request. It was never delegated, has already been answered, or expired after `requestTimeoutMs` |
| `REQUEST_DEVICE_MISMATCH` | The `requestId` is pending, but for a different device than the `deviceId` supplied |
| `ERR_RESPONSE_OFFSET` | The `offset` given to `sendResponse` is past the offset the request asked for, so the requested bytes would be missing |
| `ERR_NOTIFY` | The Bluetooth stack refused the notification, or reported it as undelivered. A bad address is `ERR_DEVICE_DISCONNECTED` or `ERR_CHARACTERISTIC_NOT_FOUND` instead |
| `ERR_NOTIFY_QUEUE_FULL` | 64 notifications are already queued for the device. Counted per central on both platforms, so one link that has stopped draining cannot refuse sends to another. Await earlier sends before queueing more |
| `ERR_DEVICE_DISCONNECTED` | The device is not connected, or disconnected -- or on iOS unsubscribed -- before a queued notification could be delivered. Also raised by `getMtu`, `sendResponse` and Android's `disconnectDevice` |
| `ERR_CHARACTERISTIC_NOT_FOUND` | The pair of UUIDs names nothing in the published GATT database. An unknown `serviceUuid` shares this code, since iOS cannot tell the two apart |
| `ERR_UPDATE_VALUE` | An `updateCharacteristicValue` argument was rejected by the native layer |
| `ERR_NO_SUBSCRIBER` | The device has not enabled the transmission `confirm` selects on the characteristic |
| `ERR_CONFIRM_UNSUPPORTED` | `confirm` asks for a transmission the characteristic does not declare the property for -- `indicate` for `true`, `notify` for `false` |
