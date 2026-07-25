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

Rebuild native projects after installing:

```bash
npx expo prebuild --clean
npx expo run:ios    # or run:android
```

**This package cannot run in Expo Go**, which ships a fixed set of native modules. Use a development
build (`npx expo run:*` or EAS Build). `isSupported()` reports `false` in Expo Go and on web, and
importing the package never throws there, so a conditional integration is safe.

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

That alone writes `NSBluetoothAlwaysUsageDescription` into the iOS `Info.plist` -- without it iOS terminates the app the moment it touches CoreBluetooth. Every option is optional:

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

The Android-only values reject on iOS with `ERR_UNSUPPORTED` rather than being approximated into a
weaker guarantee -- `CBAttributePermissions` has only four members and Apple documents `broadcast`
and `extendedProperties` as not allowed for local characteristics. See
[Properties and permissions in the API reference](./api.md#characteristicproperty) for the full
per-platform mapping and the reasoning.

An unrecognised property or permission name throws, so a typo cannot silently publish an attribute with
one fewer of either than the configuration asked for.

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

This initializes the native GATT server and registers all services and characteristics. On iOS, the
module checks Bluetooth authorization status. On Android, it checks the `BLUETOOTH_CONNECT` permission.

**The promise resolves only once every service is published**, so once it settles the database really is
there to advertise. Calling `createServer` again replaces the server rather than adding to it.

An optional second argument tunes how long a request delegated to JavaScript may go unanswered before
the module answers it itself:

```typescript
await createServer(services, { requestTimeoutMs: 5000 });
```

The default is 10000 ms. It exists because one unanswered request stalls the central until its own 30 s
ATT transaction timeout, which then bars every further read, write **and notification** on that
connection. See [createServer](./api.md#createserver).

### Guarding for platforms without the module

Importing this package never throws, so a bundle that only uses BLE conditionally is safe. Calls that
need the radio reject where the native module is absent -- on web, and in Expo Go, which ships a fixed
set of native modules. Guard them:

```typescript
import { isSupported, createServer } from 'expo-gatt-server';

if (isSupported()) {
  await createServer(services);
}
```

`isSupported` is synchronous and safe at module scope. Teardown functions and listener helpers never
throw either, so cleanup code needs no guard.

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

iOS supports only two advertisement keys in the peripheral role, so the options marked "Android only" cannot be expressed there. `manufacturerData`, `serviceData` and `connectable: false` are **rejected** on iOS rather than dropped, because a scanner filtering on them would never find the peripheral; `mode`, `txPowerLevel` and `includeTxPowerLevel` are accepted and warned about, since they only tune the radio. See [Platform support for advertising options](./api.md#platform-support-for-advertising-options).

On Android, `localName` cannot be advertised as given: the platform only offers "include the adapter's name". By default the device's existing name is advertised instead. Set `android.setAdapterName` to opt in to renaming the adapter, which the module reverses when advertising stops. See [the API reference](./api.md#the-advertised-local-name) for the details and caveats.

The promise resolves when advertising starts successfully. On iOS, this requires Bluetooth to be powered
on -- the call waits for a definitive state rather than sampling it, so calling it immediately after
`createServer` is safe. On Android, this requires the `BLUETOOTH_ADVERTISE` permission.

`timeoutMs` stops the advertisement by itself after the given number of milliseconds, up to 180000 on
both platforms. Android uses `AdvertiseSettings.setTimeout`; iOS has no equivalent, so the module
emulates it with a timer, which only holds while the process is alive. Either way there is no event when
it fires -- poll [`isAdvertising`](./api.md#isadvertising) if you need to know.

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

> **These events do not mean the same thing on both platforms.** Android reports the connection itself.
> iOS has no connection-level callback, so a central is reported on its **first ATT activity** -- a
> subscribe, read or write -- and its disconnection is inferred from losing its last subscription. A
> central that only reads and writes is therefore reported late and generally never reported as gone.
> If your app needs a reliable "is this central still there" signal on iOS, drive it from the
> subscription events below. See
> [Platform Differences](./architecture.md#platform-differences).

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

Sending before a subscription arrives rejects with `ERR_NO_SUBSCRIBER`. Whether the central asked for
notifications or indications is not reported, because CoreBluetooth does not expose the distinction.

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
  await sendResponse(
    event.deviceId,
    event.requestId,
    GATT_SUCCESS,
    event.offset,
    data,
  );
});
```

> **Note:** If a characteristic has a cached value (set via `value` in config or `updateCharacteristicValue`), the native layer auto-responds to reads without invoking this listener. Set `delegate: { read: true }` on the characteristic to receive every read regardless.

Answer every request you receive. One left unanswered is completed with `ATT_ERROR_UNLIKELY_ERROR` after
`requestTimeoutMs`, which keeps the connection usable but tells the central nothing useful.

### Write Requests

```typescript
import { addCharacteristicWriteRequestListener } from 'expo-gatt-server';

addCharacteristicWriteRequestListener((event) => {
  console.log('Written value:', event.value);
  console.log('From device:', event.deviceId);
  // event.responseNeeded is false here: the module already acknowledged the write.
});
```

The event fires for every write. By default the module has **already** acknowledged it before the
listener runs, on both platforms, so `event.responseNeeded` is `false` and there is nothing to answer.

Note that an acknowledged write updates the value later reads are answered from on iOS but not on
Android. Call `updateCharacteristicValue` from the listener if a read should serve what was written.

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
  if (!event.responseNeeded) return;

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

This pushes the value; it does **not** change what a read of the characteristic returns. Call
`updateCharacteristicValue` first if it should also be readable:

```typescript
await updateCharacteristicValue('180d', '2a37', heartRate);
await sendNotification(deviceId, '180d', '2a37', heartRate);
```

**The promise resolves when the platform reports the send as complete**, not when it is handed to the
Bluetooth stack, and sends issued while an earlier one is still in flight are queued in order. So
awaiting it is what paces a stream against the link:

```typescript
for (const sample of samples) {
  await sendNotification(deviceId, '180d', '2a37', sample);
}
```

Pass `confirm: true` as the fifth argument to send an indication (acknowledged by the central) instead of
a notification. The characteristic must declare the matching property -- `indicate` for `true`, `notify`
for `false` -- or the call rejects with `ERR_CONFIRM_UNSUPPORTED`.

> **A characteristic declaring both `notify` and `indicate` behaves differently per platform.** iOS never
> receives the `confirm` flag: `updateValue(_:for:onSubscribedCentrals:)` has no such parameter and
> CoreBluetooth chooses from the declared properties. Declare only the one you intend to use.

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

On Android this is the platform's own delivery callback and `status` is its GATT status. On iOS it is
emitted when CoreBluetooth accepts the payload, always with `status: 0`, and a failed send produces no
event at all -- only a rejected `sendNotification` promise. Prefer awaiting the promise when you need to
know the outcome of a specific send.

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

Both are synchronous and neither ever throws, including where the native module is absent, so cleanup
needs no guard and no `try`.

`stopServer` unpublishes the whole database and releases native resources, but **it does not disconnect
anybody** and emits no `onDeviceDisconnected` events. On Android call
[`disconnectDevice`](./api.md#disconnectdevice) first if a central must be dropped; on iOS that is not
possible at all, and only the central can end the connection.

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
  // Subscribers, not connections. A central receives nothing until it subscribes, and on iOS a
  // connection is not observable at all until the central's first ATT activity.
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
          // Awaited per device: the promise settles when the platform reports the send as
          // complete, which paces the stream against the link instead of overrunning it.
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

Two things this example deliberately does not do. It does not treat `onDeviceConnected` as "ready to
stream", because nothing is delivered before a subscription and iOS may not report the connection at
all. And it does not assume a central goes away quietly: `onCharacteristicUnsubscribed` covers both a
deliberate unsubscribe and a disconnection while subscribed, on both platforms.

A runnable version of this, with a button for every API call, lives in
[`example/`](https://github.com/Dishuk/expo-gatt-server/tree/main/example).

## Next Steps

| Document | Description |
|----------|-------------|
| [API Reference](api.md) | All functions, types, events, and constants |
| [Platform Setup](platform-setup.md) | Detailed iOS and Android configuration |
| [Architecture](architecture.md) | How the native bridge works |
