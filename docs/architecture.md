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
│  - MTU validation                                │
└─────────────────────────────────────────────────┘
```

## Native Module Bridge

**ExpoGattServerModule** (Swift / Kotlin) acts as the translation layer between JavaScript and native Bluetooth APIs. Responsibilities:

| Responsibility | Details |
|----------------|---------|
| Config parsing | Converts JS objects to `CBMutableService` (iOS) or `BluetoothGattService` (Android) |
| Permission checks | iOS: `CBPeripheralManager.authorization`, Android: `ContextCompat.checkSelfPermission` |
| Async wrapping | Maps native callbacks to JS Promises via Expo's `AsyncFunction` |
| Event dispatch | Forwards native delegate/callback events to JS listeners |

The module does not hold BLE state itself -- it delegates to `GattServerManager`.

## GATT Server Manager

**GattServerManager** (Swift / Kotlin) owns the native BLE peripheral and manages all state.

### Managed State

| State | iOS Type | Android Type | Purpose |
|-------|----------|--------------|---------|
| Connected devices | `[String: CBCentral]` (from observed ATT activity) | `ConcurrentHashMap<String, BluetoothDevice>` | Track which centrals are connected |
| Device MTU | Read live from `central.maximumUpdateValueLength` | `ConcurrentHashMap<String, Int>` | Validate payload size, answer `getMtu` |
| Pending requests | `[Int: CBATTRequest]` | `ConcurrentHashMap<Int, String>` | Match `sendResponse` to read requests |
| Characteristic values | `[CBUUID: Data]` | Set on `BluetoothGattCharacteristic.value` | Auto-respond to reads |
| Subscribed centrals | `[String: [CBUUID: CBCentral]]` | Managed via CCCD descriptor | Track notification subscribers |

### Lifecycle

```
open(services)
    │
    ▼
startAdvertising()  ◄──  Central scans and finds the device
    │
    ▼
[Central connects]  ──►  onDeviceConnected event
    │
    ├── [Central reads]   ──►  Auto-respond or onCharacteristicReadRequest
    ├── [Central writes]  ──►  onCharacteristicWriteRequest
    ├── sendNotification  ──►  Push update to central
    │
    ▼
stopAdvertising()
    │
    ▼
stop()  ──►  Remove services, disconnect, release resources
```

## Event Flow

### Read Request (no cached value)

```
Central                    Native                     JavaScript
  │                          │                            │
  ├── Read request ──────►   │                            │
  │                          ├── onCharacteristicReadRequest ──►
  │                          │                            │
  │                          │   ◄── sendResponse ────────┤
  │   ◄── ATT response ─────┤                            │
```

### Read Request (cached value)

```
Central                    Native                     JavaScript
  │                          │                            │
  ├── Read request ──────►   │                            │
  │                          │  (auto-respond from cache) │
  │   ◄── ATT response ─────┤                            │
```

### Notification

```
JavaScript                 Native                     Central
  │                          │                            │
  ├── sendNotification ──►   │                            │
  │                          ├── BLE notification ──────► │
  │                          │                            │
  │   ◄── onNotificationSent┤                            │
```

## MTU Handling

The ATT protocol has a default MTU of 23 bytes (3-byte header + 20-byte payload). Centrals can negotiate a larger MTU after connecting.

| Scenario | Behavior |
|----------|----------|
| Payload <= 20 bytes, no MTU negotiation | Sent normally |
| Payload > 20 bytes, no MTU negotiation | Data is sent, then `MTU_SMALL` error is thrown |
| Payload <= negotiated MTU - 3 | Sent normally |
| Payload > negotiated MTU - 3 | Data is sent, then `PAYLOAD_EXCEEDS_MTU` error is thrown |

The "send first, throw after" pattern ensures the central receives whatever the BLE stack can deliver, while still alerting the JavaScript layer that data may have been truncated.

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
| Device identifier | UUID (opaque, can rotate) | MAC address (stable) |
| Connection event | Fires on the central's first ATT activity (subscribe, read or write) -- `CBPeripheralManagerDelegate` has no connection callback | Fires on `onConnectionStateChange` |
| Disconnection event | Inferred from the loss of the last subscription, or reported for every known central when Bluetooth leaves `poweredOn` | Fires on `onConnectionStateChange` |
| Write auto-response | Not automatic; JS must respond if `responseNeeded` | Automatic for `responseNeeded` requests |
| CCCD descriptor | Managed by CoreBluetooth internally | Explicitly added by the module |
| MTU source | `central.maximumUpdateValueLength` (a payload length) | `onMtuChanged` callback (an ATT MTU) |
| MTU change event | No callback exists; sampled on the central's next ATT activity | Delivered as it happens |
| Bluetooth state check | `CBManagerState.poweredOn` | `BluetoothAdapter.isEnabled()` |
| Permission model | `CBPeripheralManager.authorization` | Runtime permissions (API 31+) |
