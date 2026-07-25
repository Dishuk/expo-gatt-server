# Architecture

System design, native bridge, and event flow.

- [Overview](#overview)
- [Layer Diagram](#layer-diagram)
- [Native Module Bridge](#native-module-bridge)
- [GATT Server Manager](#gatt-server-manager)
- [Event Flow](#event-flow)
- [MTU Handling](#mtu-handling)
- [Platform Differences](#platform-differences)

## Overview

expo-gatt-server is a two-layer native module built on the Expo Modules API. The TypeScript API delegates to platform-specific native code (Swift on iOS, Kotlin on Android) that wraps the respective Bluetooth frameworks.

```
JavaScript (React Native)
    │
    ▼
TypeScript API (src/index.ts)
    │
    ▼
Expo Module Bridge (ExpoGattServerModule)
    │
    ├── iOS: CoreBluetooth (CBPeripheralManager)
    │
    └── Android: BluetoothGatt (BluetoothGattServer + BluetoothLeAdvertiser)
```

## Layer Diagram

```
┌─────────────────────────────────────────────────┐
│                  JavaScript                      │
│                                                  │
│  createServer() ─► startAdvertising() ─► ...     │
│  addDeviceConnectedListener()                    │
│  addCharacteristicWriteRequestListener()         │
└────────────────────┬────────────────────────────┘
                     │ Expo Modules Bridge
┌────────────────────▼────────────────────────────┐
│             ExpoGattServerModule                 │
│                                                  │
│  - Parses JS config into native types            │
│  - Permission checks (Bluetooth auth / runtime)  │
│  - Async function wrappers with Promise          │
│  - Event emission to JS listeners                │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│              GattServerManager                   │
│                                                  │
│  - Manages native BLE peripheral lifecycle       │
│  - Tracks connected devices, MTU, subscriptions  │
│  - Handles GATT callbacks (read/write/notify)    │
│  - Caches characteristic values for auto-respond │
│  - Answers or delegates each ATT request         │
│  - Queues notifications, one outstanding at a    │
│    time per device                               │
│  - Watches adapter state, re-publishes services  │
│  - MTU validation                                │
└─────────────────────────────────────────────────┘
```

Above the bridge, `src/index.ts` is not a pass-through. It normalises every UUID onto its 128-bit form,
range-checks bytes, offsets, statuses and timeouts, rejects unrecognised enum names, warns about
advertising options iOS ignores, and degrades gracefully when the native module is absent -- so both
platforms receive identical, already-valid input.

## Native Module Bridge

**ExpoGattServerModule** (Swift / Kotlin) acts as the translation layer between JavaScript and native Bluetooth APIs. Responsibilities:

| Responsibility | Details |
|----------------|---------|
| Config parsing | Converts JS objects to `CBMutableService` (iOS) or `BluetoothGattService` (Android), and refuses what the platform cannot express |
| Permission checks | iOS: `CBManager.authorization`, Android: `ContextCompat.checkSelfPermission` for `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` |
| Async wrapping | Maps native callbacks to JS Promises via Expo's `AsyncFunction`, with a coded rejection per failure mode |
| Event dispatch | Forwards native delegate/callback events to JS listeners |

The module does not hold BLE state itself -- it delegates to `GattServerManager`.

## GATT Server Manager

**GattServerManager** (Swift / Kotlin) owns the native BLE peripheral and manages all state.

### Managed State

| State | iOS Type | Android Type | Purpose |
|-------|----------|--------------|---------|
| Connected devices | `[String: CBCentral]` (from observed ATT activity) | `ConcurrentHashMap<String, BluetoothDevice>` | Track which centrals are connected, and answer `getConnectedDevices` |
| Device MTU | Read live from `central.maximumUpdateValueLength`; the last value seen is cached only to detect a change | `ConcurrentHashMap<String, Int>` | Validate payload size, answer `getMtu`, emit `onMtuChanged` |
| Pending requests | `[Int: PendingRequest]` | `ConcurrentHashMap<Int, PendingRequest>` | Match `sendResponse` to a request, validate its device, rebase the response onto the requested offset, and expire it after `requestTimeoutMs` |
| Characteristic values | `[CharacteristicAddress: Data]` | Set on the per-service `BluetoothGattCharacteristic` instance | Auto-respond to reads |
| Subscribed centrals | `[String: [CharacteristicAddress: CBCentral]]` -- membership only, since CoreBluetooth does not report which bit was set | `ConcurrentHashMap<String, ConcurrentHashMap<UUID, Int>>` -- the raw two-octet CCCD value per device | Track notification subscribers, answer per-client CCCD reads on Android |
| Delegations | `[CharacteristicAddress: CharacteristicDelegation]` | `ConcurrentHashMap<CharacteristicAddress, CharacteristicDelegation>` | Decide whether a read or write is answered natively or handed to JavaScript |
| Notification queue | `[QueuedNotification]`, one queue for the peripheral manager's transmit queue | `ConcurrentHashMap<String, NotificationQueue>`, one per device | Keep at most one send outstanding, and resolve `sendNotification` on the platform's own callback |
| Prepared writes | Not applicable -- CoreBluetooth does not expose them | `ConcurrentHashMap<String, MutableList<PreparedWrite>>` | Buffer a long or reliable write until its execute |
| Published state | `databasePublished` plus the retained service configuration | `AtomicBoolean` plus a service factory | Answer `isServerRunning`, and rebuild the database when Bluetooth returns |

### Lifecycle

```
open(services)  ──►  resolves only once every service is published
    │
    ▼
startAdvertising()  ◄──  Central scans and finds the device
    │
    ▼
[Central connects]  ──►  onDeviceConnected event
    │                    (iOS: on the central's first ATT activity)
    │
    ├── [Central reads]      ──►  Auto-respond, or onCharacteristicReadRequest
    ├── [Central writes]     ──►  Auto-acknowledge, or onCharacteristicWriteRequest
    ├── [Central subscribes] ──►  onCharacteristicSubscribed
    ├── sendNotification     ──►  Push update to a subscribed central
    │
    ▼
stopAdvertising()
    │
    ▼
stop()  ──►  Unpublish services, release resources. Disconnects nobody
```

An adapter power cycle interrupts this without ending it. Turning Bluetooth off destroys the published
database on both platforms, so the module reports every subscription as ended and every known central
as disconnected, and `isServerRunning` goes `false`. It retains the service configuration and
re-publishes it on the next transition to `poweredOn` -- **advertising is not resumed**, because the
consumer chose when to start it.

## Event Flow

Which of the two read paths and which of the two write paths a request takes is decided by the
characteristic's `delegate` configuration and, for reads, by whether a cached value exists.

### Read Request (no cached value, or `delegate.read`)

```
Central                    Native                     JavaScript
  │                          │                            │
  ├── Read request ──────►   │                            │
  │                          ├── onCharacteristicReadRequest ──►
  │                          │                            │
  │                          │   ◄── sendResponse ────────┤
  │   ◄── ATT response ─────┤                            │
```

If `sendResponse` never comes, the module answers with `ATT_ERROR_UNLIKELY_ERROR` after
`requestTimeoutMs` -- otherwise the central would stall until its own 30 s ATT transaction timeout,
which then bars every further request and notification on that bearer.

### Read Request (cached value)

```
Central                    Native                     JavaScript
  │                          │                            │
  ├── Read request ──────►   │                            │
  │                          │  (auto-respond from cache) │
  │   ◄── ATT response ─────┤                            │
```

### Write Request (default)

```
Central                    Native                     JavaScript
  │                          │                            │
  ├── Write request ─────►   │                            │
  │   ◄── ATT response ─────┤  (auto-acknowledged)        │
  │                          ├── onCharacteristicWriteRequest ──►
  │                          │      responseNeeded: false │
```

The written value is stored before the listener runs, on both platforms, so a later read of the same
characteristic serves it without JavaScript doing anything. The value is *replaced* rather than merged,
as `ATT_WRITE_REQ` requires; a fragment bearing a non-zero offset, which only arises from the
queued-write procedure, is spliced in at that offset instead.

### Write Request (`delegate.write`)

```
Central                    Native                     JavaScript
  │                          │                            │
  ├── Write request ─────►   │                            │
  │                          ├── onCharacteristicWriteRequest ──►
  │                          │      responseNeeded: true  │
  │                          │   ◄── sendResponse ────────┤
  │   ◄── ATT response ─────┤     (GATT_SUCCESS or an ATT error)
```

The value is not applied until JavaScript accepts the write, so an `ATT_ERROR_*` status rejects it
outright. Committing the accepted value is the listener's job, via `updateCharacteristicValue`.

### Notification

```
JavaScript                 Native                     Central
  │                          │                            │
  ├── sendNotification ──►   │                            │
  │                          ├── BLE notification ──────► │
  │                          │                            │
  │   ◄── onNotificationSent┤                            │
```

At most one notification is outstanding per device, and `sendNotification`'s promise settles on the
platform's own completion -- so awaiting it paces a stream against the link. `onNotificationSent`
reports the same outcome as an event, with a caveat: on Android it is the platform's delivery callback
and carries its status, while on iOS it is emitted when CoreBluetooth accepts the payload and is not
emitted at all for a failed send.

## MTU Handling

The ATT protocol has a default MTU of 23 bytes (3-byte header + 20-byte payload). Centrals can negotiate a larger MTU after connecting.

| Scenario | Behavior |
|----------|----------|
| Notification payload <= MTU - 3 | Sent normally |
| Notification payload > MTU - 3 | Rejected with `PAYLOAD_EXCEEDS_MTU`; **nothing is transmitted** |
| Read response of any length | Sent as-is; the central continues a long value with a Read Blob request |

`sendNotification` validates the payload **before** transmitting. Both platforms silently truncate an oversized notification rather than failing it -- Apple documents that `updateValue` truncates a value exceeding `maximumUpdateValueLength` "to fit", and the Android stack logs "attribute value too long, to be truncated to N" while building the `ATT_HANDLE_VALUE_NTF` PDU. Because a notification has no continuation mechanism, transmitting it would lose the tail with nothing to recover it, so the send is refused instead.

`sendResponse` is deliberately **not** size-checked. An `ATT_READ_RSP` carries at most `ATT_MTU - 1` octets and the central finishes a longer value with `ATT_READ_BLOB_REQ`, which arrives as another read request bearing an offset -- so answering with more than fits is normal ATT rather than a failure. The module's own automatic read path already answers with the whole remainder from the requested offset, so a size check here only penalised delegated reads for behaving identically.

On iOS, the negotiated payload size is read from `central.maximumUpdateValueLength`. On Android, it is tracked via the `onMtuChanged` callback.

### Exposing the MTU to JavaScript

The public unit is the **ATT MTU in octets** -- what the Bluetooth Core Specification and the Android platform both call "MTU". `getMtu(deviceId)` returns it, and `onMtuChanged` reports every change. `maxNotificationPayload` is supplied alongside it as `mtu - 3`, the maximum Attribute Value length of an `ATT_HANDLE_VALUE_NTF` PDU, so callers never have to know the header size.

The two platforms report different halves of the same figure exactly, and derive the other:

| | iOS | Android |
|---|---|---|
| Native source | `CBCentral.maximumUpdateValueLength`, a **payload length** | `onMtuChanged`, an **ATT MTU** |
| `maxNotificationPayload` | Exact | Derived as `mtu - 3` |
| `mtu` | Derived as `maximumUpdateValueLength + 3` | Exact |
| Change notification | None exists; the value is sampled on the central's next ATT activity | Delivered as it happens |

## Platform Differences

| Behavior | iOS | Android |
|----------|-----|---------|
| Device identifier | `CBCentral.identifier` UUID (opaque, can rotate) | MAC address (stable) |
| Device name | Never available -- CoreBluetooth exposes no name for a central | `BluetoothDevice.getName()` |
| Connection event | Fires on the central's first ATT activity (subscribe, read or write) -- `CBPeripheralManagerDelegate` has no connection callback. A central that never touches an attribute is never reported | Fires on `onConnectionStateChange` |
| Disconnection event | Inferred from the loss of the last subscription, or reported for every known central when Bluetooth leaves `poweredOn`. Generally undetectable for a read/write-only central | Fires on `onConnectionStateChange` |
| Dropping a central | **Impossible** -- `disconnectDevice` rejects with `ERR_UNSUPPORTED` | `BluetoothGattServer.cancelConnection` |
| Read auto-response | From the module's own value cache, keyed by service **and** characteristic | From `BluetoothGattCharacteristic.value`, which is already per service |
| Write auto-response | Automatic, unless the characteristic sets `delegate.write` | Automatic, unless the characteristic sets `delegate.write` |
| Prepared / long writes | Not exposed at all; CoreBluetooth handles the procedure below the app layer | Buffered per device and applied on execute |
| `confirm` on a notification | Never reaches the platform; CoreBluetooth picks notification or indication from the declared properties | Passed to `notifyCharacteristicChanged` |
| CCCD descriptor | Created by CoreBluetooth on publication; per-client bits are not exposed | Explicitly added by the module, which tracks the per-client bits itself |
| Custom descriptors | Only `0x2901` and `0x2904`; anything else rejects with `ERR_UNSUPPORTED` | Any UUID except the CCCD |
| MTU source | `central.maximumUpdateValueLength` (a payload length) | `onMtuChanged` callback (an ATT MTU) |
| MTU change event | No callback exists; sampled on the central's next ATT activity | Delivered as it happens |
| Advertised local name | `CBAdvertisementDataLocalNameKey`, verbatim | No per-advertisement name exists; the adapter's own name is advertised instead |
| Advertising `timeoutMs` | Emulated by a module timer | `AdvertiseSettings.setTimeout` |
| Bluetooth state source | `CBPeripheralManager.state`, which needs an instantiated manager | `BluetoothAdapter.getState()`, plus an `ACTION_STATE_CHANGED` receiver registered while the server is open |
| Permission model | `CBManager.authorization` | Runtime permissions (API 31+): `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` |

The [API reference](./api.md) states the consequence of each of these at the function or type it
affects.
