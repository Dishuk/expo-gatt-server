# Getting Started

A step-by-step guide to adding BLE peripheral functionality to your Expo app.

- [Installation](#installation)
- [Configure Permissions](#configure-permissions)
- [Define a Service](#define-a-service)
- [Create the Server](#create-the-server)
- [Start Advertising](#start-advertising)
- [Handle Requests](#handle-requests)
- [Send Notifications](#send-notifications)
- [Cleanup](#cleanup)
- [Full Example](#full-example)

## Installation

```bash
npx expo install expo-gatt-server
```

From git: enable lifecycle scripts (published tarball includes compiled `build/` and `plugin/build/`, git checkout builds in `prepare`).

Rebuild native projects:

```bash
npx expo prebuild --clean
npx expo run:ios    # or run:android
```

**Not available in Expo Go** (fixed native modules). Use `npx expo run:*` or EAS Build. `isSupported()` returns `false` in Expo Go and on web; importing never throws, so conditional use is safe.

## Configure Permissions

### Config Plugin

The package ships an Expo config plugin, so the build-time configuration needs no hand-editing. Add it to `app.json`:

```json
{
  "expo": {
    "plugins": ["expo-gatt-server"]
  }
}
```

This writes `NSBluetoothAlwaysUsageDescription` to iOS `Info.plist` (required for CoreBluetooth access). All options are optional:

```json
{
  "expo": {
    "plugins": [
      [
        "expo-gatt-server",
        {
          "bluetoothAlwaysPermission": "This app uses Bluetooth to communicate with nearby devices.",
          "bluetoothPeripheralBackgroundMode": true,
          "requireBluetoothLeHardware": true
        }
      ]
    ]
  }
}
```

| Option | Platform | Default | Effect |
|--------|----------|---------|--------|
| `bluetoothAlwaysPermission` | iOS | a generic description | Sets `NSBluetoothAlwaysUsageDescription`. `false` leaves the key alone; omitting it keeps an existing `ios.infoPlist` value |
| `bluetoothPeripheralBackgroundMode` | iOS | `false` | Adds `bluetooth-peripheral` to `UIBackgroundModes` |
| `requireBluetoothLeHardware` | Android | `false` | Sets `android:required` on `android.hardware.bluetooth_le`. See [Hardware Requirements](./platform-setup.md#hardware-requirements) |

Run `npx expo prebuild --clean` after changing any of them.

### Android

The module declares all required permissions in its manifest. For Android 12+ (API 31), request runtime permissions before using the module:

```typescript
import { PermissionsAndroid, Platform } from 'react-native';

if (Platform.OS === 'android' && Platform.Version >= 31) {
  await PermissionsAndroid.requestMultiple([
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
  ]);
}
```

## Define a Service

A GATT service is a collection of characteristics identified by a UUID. Each characteristic has properties (what operations it supports) and permissions (access control).

```typescript
import type { GattServiceConfig } from 'expo-gatt-server';

const services: GattServiceConfig[] = [
  {
    uuid: '180d', // Heart Rate Service
    characteristics: [
      {
        uuid: '2a37', // Heart Rate Measurement
        properties: ['notify'],
        permissions: ['readable'],
      },
      {
        uuid: '2a38', // Body Sensor Location
        properties: ['read'],
        permissions: ['readable'],
        value: [1], // "Chest" -- static value, auto-responded by native layer
      },
    ],
  },
];
```

Any UUID may be written in the 16-bit (`'2a37'`), 32-bit or full 128-bit form. Short forms are expanded
onto the Bluetooth Base UUID before either platform sees them, so `'2a37'` and
`'00002a37-0000-1000-8000-00805f9b34fb'` are the same characteristic. **Event payloads always report the
lowercase 128-bit form**, whichever spelling the configuration used -- so compare against that, not
against `'2a37'`. See [UUID forms](./api.md#uuid-forms).

A service may also declare `type: 'secondary'`, though a secondary service will not be found by a
central doing primary service discovery -- see
[GattServiceType](./api.md#gattservicetype).

### Properties vs Permissions

| Property | Meaning |
|----------|---------|
| `read` | Central can read the value |
| `write` | Central can write with acknowledgment |
| `writeNoResponse` | Central can write without acknowledgment |
| `notify` | Server can push updates (no confirmation) |
| `indicate` | Server can push updates (with confirmation) |
| `signedWrite` | Central can write with a signature over an unencrypted link |
| `broadcast` | Value may be broadcast. **Android only** |
| `extendedProperties` | Further properties live in the extended properties descriptor. **Android only** |

| Permission | Meaning |
|------------|---------|
| `readable` | Characteristic value can be read |
| `writeable` | Characteristic value can be written |
| `readEncrypted` | Readable only over an encrypted link |
| `writeEncrypted` | Writeable only over an encrypted link |
| `readEncryptedMitm` | Readable only with MITM protection. **Android only** |
| `writeEncryptedMitm` | Writeable only with MITM protection. **Android only** |
| `writeSigned` | Writeable with a signature. **Android only** |
| `writeSignedMitm` | Writeable with a signature and MITM protection. **Android only** |

Android-only values reject on iOS with `ERR_UNSUPPORTED`. Unrecognized property or permission names throw. Encrypted permissions apply to subscriptions: a central cannot subscribe unless it meets the link security requirement for that characteristic. See [API reference](./api.md#characteristicproperty).

### Optional extras

| Field | Purpose |
|-------|---------|
| `value` | The value reads are answered from until something replaces it. Any characteristic may have one, including a notifying one -- the module keeps its own cache rather than relying on the platform's initial-value field. Omit it to have every read delegated to JavaScript |
| `descriptors` | Descriptors beyond the Client Characteristic Configuration descriptor the module publishes itself for every `notify` / `indicate` characteristic. iOS accepts only `0x2901` and `0x2904`; see [GattDescriptorConfig](./api.md#gattdescriptorconfig) |
| `delegate` | Opt out of the module's automatic responses for this characteristic, so reads and/or writes reach JavaScript. See [Delegating to JavaScript](#delegating-to-javascript) |

## Create the Server

```typescript
import { createServer } from 'expo-gatt-server';

await createServer(services);
```

Initializes the GATT server and registers all services and characteristics. Checks Bluetooth authorization (iOS) or `BLUETOOTH_CONNECT` permission (Android). **Promise resolves only after all services are published.** Calling again replaces the server.

An optional second argument tunes how long a request delegated to JavaScript may go unanswered before
the module answers it itself:

```typescript
await createServer(services, { requestTimeoutMs: 5000 });
```

The default is 10000 ms. It exists because one unanswered request stalls the central until its own 30 s
ATT transaction timeout, which then bars every further read, write **and notification** on that
connection. See [createServer](./api.md#createserver).

### Guarding for unsupported platforms

Importing never throws. Guard API calls on web and in Expo Go:

```typescript
import { isSupported, createServer } from 'expo-gatt-server';

if (isSupported()) {
  await createServer(services);
}
```

`isSupported()` is synchronous and safe at module scope. Cleanup functions and listeners never throw.

## Start Advertising

```typescript
import { startAdvertising } from 'expo-gatt-server';

await startAdvertising({
  localName: 'HeartSensor',
  serviceUuids: ['180d'],
  connectable: true,
});
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `localName` | `string` | -- | Local name to advertise. Honoured on iOS only -- Android has no per-advertisement local name |
| `serviceUuids` | `string[]` | -- | Service UUIDs to include in advertisement |
| `includeTxPowerLevel` | `boolean` | `false` | Include TX power in advertisement data. Android only |
| `connectable` | `boolean` | `true` | Whether the device accepts connections. `false` is Android only |
| `mode` | `'lowPower' \| 'balanced' \| 'lowLatency'` | `'lowPower'` | Discovery latency against battery. Android only |
| `txPowerLevel` | `'ultraLow' \| 'low' \| 'medium' \| 'high'` | `'medium'` | Advertising range. Android only |
| `timeoutMs` | `number` | `0` | Stop advertising after this many ms; `0` means no limit |
| `manufacturerData` | `{ companyId, data }[]` | `[]` | Manufacturer Specific Data. Android only |
| `serviceData` | `{ uuid, data }[]` | `[]` | Service Data. Android only |
| `android.includeDeviceName` | `boolean` | `localName !== undefined` | Advertise the device's own Bluetooth name |
| `android.setAdapterName` | `boolean` | `false` | Rename the phone's system-wide Bluetooth name to `localName` |

**iOS**: Only two advertisement keys supported. Options marked "Android only" are **rejected** (`manufacturerData`, `serviceData`, `connectable: false`); tuning-only options (`mode`, `txPowerLevel`, `includeTxPowerLevel`) are accepted with a warning. See [API reference](./api.md#platform-support-for-advertising-options).

**Android**: `localName` cannot be advertised directly. By default, the device's system Bluetooth name is advertised. Set `android.setAdapterName: true` to rename the adapter (module reverses on stop).

Promise resolves when advertising starts. **iOS**: requires Bluetooth powered on (waits for definitive state; safe to call immediately after `createServer`). **Android**: requires `BLUETOOTH_ADVERTISE` permission.

`timeoutMs` stops advertising after the given milliseconds (max 180000 on both platforms). Android: `AdvertiseSettings.setTimeout`. iOS: emulated with timer (only holds while process is alive). No event on timeout; poll [`isAdvertising`](./api.md#isadvertising) if needed.

## Handle Requests

### Connection Events

```typescript
import {
  addDeviceConnectedListener,
  addDeviceDisconnectedListener,
} from 'expo-gatt-server';

const connectSub = addDeviceConnectedListener((event) => {
  console.log(`Connected: ${event.deviceId}`);
});

const disconnectSub = addDeviceDisconnectedListener((event) => {
  console.log(`Disconnected: ${event.deviceId}`);
});
```

> **Platform difference:** Android reports connection directly. iOS reports on **first ATT activity** (subscribe/read/write); disconnection inferred from losing last subscription. A read-only central may not be reported as gone. Use subscription events for reliable "central present" on iOS. See [Platform Differences](./architecture.md#platform-differences).

### Subscription Events

A central must enable notifications or indications before it receives anything. These events are the
signal to start and stop streaming:

```typescript
import {
  addCharacteristicSubscribedListener,
  addCharacteristicUnsubscribedListener,
} from 'expo-gatt-server';

const subscribeSub = addCharacteristicSubscribedListener((event) => {
  console.log(`${event.deviceId} subscribed to ${event.characteristicUuid}`);
});

const unsubscribeSub = addCharacteristicUnsubscribedListener((event) => {
  console.log(`${event.deviceId} unsubscribed from ${event.characteristicUuid}`);
});
```

Sending before a subscription rejects with `ERR_NO_SUBSCRIBER`. Whether the central subscribed to notifications or indications is not reported (CoreBluetooth limitation).

### Read Requests

When a central reads a characteristic that has no cached value, the request is forwarded to JavaScript. Respond with `sendResponse`:

```typescript
import {
  addCharacteristicReadRequestListener,
  sendResponse,
  GATT_SUCCESS,
} from 'expo-gatt-server';

addCharacteristicReadRequestListener(async (event) => {
  const data = [0x06, 72]; // Flags + heart rate
  // Module rebases response onto the request's offset; pass 0 unless you've already sliced data.
  await sendResponse(event.deviceId, event.requestId, GATT_SUCCESS, 0, data);
});
```

> **Note:** Cached values (via config `value` or `updateCharacteristicValue`) auto-respond without invoking this listener. Set `delegate: { read: true }` to receive all reads.

Answer every request. Unanswered requests timeout after `requestTimeoutMs` with `ATT_ERROR_UNLIKELY_ERROR`.

### Write Requests

```typescript
import { addCharacteristicWriteRequestListener } from 'expo-gatt-server';

addCharacteristicWriteRequestListener((event) => {
  console.log('Written value:', event.value);
  console.log('From device:', event.deviceId);
  // event.responseNeeded is false here: the module already acknowledged the write.
});
```

The module acknowledges writes before the listener runs (both platforms), so `event.responseNeeded` is `false`. Acknowledged writes update the cached value for later reads automatically. With `delegate: { write: true }`, nothing is stored until you accept the write via `updateCharacteristicValue`.

### Delegating to JavaScript

To validate or reject a write -- or to serve a computed read -- opt the characteristic out of the
automatic responses:

```typescript
const services: GattServiceConfig[] = [
  {
    uuid: '180d',
    characteristics: [
      {
        uuid: '2a39', // Heart Rate Control Point
        properties: ['write'],
        permissions: ['writeable'],
        delegate: { write: true },
      },
    ],
  },
];
```

Now the write is held open, arrives with `responseNeeded: true`, and is not applied until you answer:

```typescript
import {
  addCharacteristicWriteRequestListener,
  sendResponse,
  updateCharacteristicValue,
  GATT_SUCCESS,
  ATT_ERROR_WRITE_NOT_PERMITTED,
} from 'expo-gatt-server';

addCharacteristicWriteRequestListener(async (event) => {
  // iOS: long writes batch multiple delegated characteristics; only one has responseNeeded: true.
  if (!event.responseNeeded) {
    await updateCharacteristicValue(event.serviceUuid, event.characteristicUuid, event.value);
    return;
  }

  if (event.value.length !== 1) {
    await sendResponse(
      event.deviceId,
      event.requestId,
      ATT_ERROR_WRITE_NOT_PERMITTED,
      0,
      [],
    );
    return;
  }

  await sendResponse(event.deviceId, event.requestId, GATT_SUCCESS, 0, []);
  await updateCharacteristicValue(event.serviceUuid, event.characteristicUuid, event.value);
});
```

Answering with an `ATT_ERROR_*` status is the only way to reject a write. A write response carries no
value, so pass `[]`. `delegate: { read: true }` does the same for reads, where the response value *is*
sent back. See [CharacteristicDelegateConfig](./api.md#characteristicdelegateconfig).

## Send Notifications

Push value updates to subscribed centrals:

```typescript
import { sendNotification } from 'expo-gatt-server';

const heartRate = [0x06, 75]; // Flags + 75 BPM

await sendNotification(deviceId, '180d', '2a37', heartRate);
```

The central must have subscribed first, or the call rejects with `ERR_NO_SUBSCRIBER` -- wait for
`onCharacteristicSubscribed`.

Notifications do **not** update the cached value. Call `updateCharacteristicValue` first if readable:

```typescript
await updateCharacteristicValue('180d', '2a37', heartRate);
await sendNotification(deviceId, '180d', '2a37', heartRate);
```

**Promise resolution:** Android = transmission complete (+ confirmation for indications); iOS = CoreBluetooth accepted payload. Both platforms queue sends in order, so awaiting paces the stream:

```typescript
for (const sample of samples) {
  await sendNotification(deviceId, '180d', '2a37', sample);
}
```

Pass `confirm: true` as the fifth argument to send an indication (acknowledged by the central) instead of
a notification. The characteristic must declare the matching property -- `indicate` for `true`, `notify`
for `false` -- or the call rejects with `ERR_CONFIRM_UNSUPPORTED`.

> **Characteristic with both `notify` and `indicate`:** iOS ignores `confirm` flag; CoreBluetooth chooses from declared properties. Declare only the intended property.

### Payload size

A notification has no continuation mechanism, so an oversized payload is rejected with
`PAYLOAD_EXCEEDS_MTU` rather than truncated. Size against the link:

```typescript
import { getMtu, addMtuChangedListener } from 'expo-gatt-server';

const { maxNotificationPayload } = await getMtu(deviceId);
```

Until a central negotiates a larger MTU the link carries the specification default of 23 octets, leaving
20 for the payload. `addMtuChangedListener` reports every change -- immediately on Android, and at the
central's next ATT activity on iOS, which has no MTU callback.

### Delivery status

```typescript
import { addNotificationSentListener } from 'expo-gatt-server';

addNotificationSentListener((event) => {
  console.log(`${event.characteristicUuid} status=${event.status}`);
});
```

**Android:** platform's delivery callback; `status` is GATT status. **iOS:** emitted when payload accepted, always `status: 0`; failed sends have no event (only rejected promise). Await the promise for specific send outcome.

## Cleanup

Remove event listeners when the component unmounts and stop the server when done:

```typescript
import { stopAdvertising, stopServer } from 'expo-gatt-server';

// In cleanup / useEffect return:
connectSub.remove();
disconnectSub.remove();
stopAdvertising();
stopServer();
```

Both are synchronous and never throw (safe without guards). `stopServer` unpublishes the database and releases resources, but **does not disconnect centrals** or emit `onDeviceDisconnected` events. To drop a central on Android, call [`disconnectDevice`](./api.md#disconnectdevice) first; on iOS, only the central can end the connection.

## Full Example

```typescript
import { useEffect, useRef } from 'react';
import {
  addCharacteristicSubscribedListener,
  addCharacteristicUnsubscribedListener,
  createServer,
  isSupported,
  sendNotification,
  startAdvertising,
  stopAdvertising,
  stopServer,
  type EventSubscription,
  type GattServiceConfig,
} from 'expo-gatt-server';

const SERVICE_UUID = '180d';
const HR_CHAR_UUID = '2a37';

const services: GattServiceConfig[] = [
  {
    uuid: SERVICE_UUID,
    characteristics: [
      {
        uuid: HR_CHAR_UUID,
        properties: ['notify'],
        permissions: ['readable'],
      },
    ],
  },
];

export default function HeartRatePeripheral() {
  // Track subscribers, not connections (centrals receive nothing until subscribed).
  const subscribers = useRef<Set<string>>(new Set());

  useEffect(() => {
    if (!isSupported()) return;

    let stopped = false;
    let interval: ReturnType<typeof setInterval> | undefined;

    const subscriptions: EventSubscription[] = [
      addCharacteristicSubscribedListener((event) => {
        subscribers.current.add(event.deviceId);
      }),
      addCharacteristicUnsubscribedListener((event) => {
        subscribers.current.delete(event.deviceId);
      }),
    ];

    async function push() {
      const bpm = 60 + Math.floor(Math.random() * 40);
      for (const deviceId of Array.from(subscribers.current)) {
        try {
          // Await per device to pace the stream: Android = send completed, iOS = queued.
          await sendNotification(deviceId, SERVICE_UUID, HR_CHAR_UUID, [0x06, bpm]);
        } catch (error) {
          console.warn(`notification to ${deviceId} failed`, error);
        }
      }
    }

    async function start() {
      await createServer(services);
      await startAdvertising({ localName: 'HRSensor', serviceUuids: [SERVICE_UUID] });
      if (stopped) return;
      interval = setInterval(() => {
        void push();
      }, 1000);
    }

    start().catch((error: unknown) => console.warn('BLE setup failed', error));

    return () => {
      stopped = true;
      if (interval) clearInterval(interval);
      subscriptions.forEach((subscription) => subscription.remove());
      stopAdvertising();
      stopServer();
    };
  }, []);

  return null; // headless -- add your UI here
}
```

This example does not treat `onDeviceConnected` as "ready" (no delivery before subscription); instead, it waits for `onCharacteristicSubscribed`. It also uses `onCharacteristicUnsubscribed` for cleanup (covers both explicit unsubscribe and disconnection while subscribed).

A runnable version with API buttons lives in [`example/`](https://github.com/Dishuk/expo-gatt-server/tree/main/example).

## Next Steps

| Document | Description |
|----------|-------------|
| [API Reference](api.md) | All functions, types, events, and constants |
| [Platform Setup](platform-setup.md) | Detailed iOS and Android configuration |
| [Architecture](architecture.md) | How the native bridge works |
