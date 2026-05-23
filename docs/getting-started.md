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

## Configure Permissions

### iOS

Add a Bluetooth usage description to `app.json`:

```json
{
  "expo": {
    "ios": {
      "infoPlist": {
        "NSBluetoothAlwaysUsageDescription": "This app uses Bluetooth to communicate with nearby devices."
      }
    }
  }
}
```

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
    uuid: '0000180d-0000-1000-8000-00805f9b34fb', // Heart Rate Service
    characteristics: [
      {
        uuid: '00002a37-0000-1000-8000-00805f9b34fb', // Heart Rate Measurement
        properties: ['notify'],
        permissions: ['readable'],
      },
      {
        uuid: '00002a38-0000-1000-8000-00805f9b34fb', // Body Sensor Location
        properties: ['read'],
        permissions: ['readable'],
        value: [1], // "Chest" -- static value, auto-responded by native layer
      },
    ],
  },
];
```

### Properties vs Permissions

| Property | Meaning |
|----------|---------|
| `read` | Central can read the value |
| `write` | Central can write with acknowledgment |
| `writeNoResponse` | Central can write without acknowledgment |
| `notify` | Server can push updates (no confirmation) |
| `indicate` | Server can push updates (with confirmation) |

| Permission | Meaning |
|------------|---------|
| `readable` | Characteristic value can be read |
| `writeable` | Characteristic value can be written |

> **Note:** Characteristics with `notify` or `indicate` should not have an initial `value`. The native layer requires a nil/null initial value to allow dynamic updates.

## Create the Server

```typescript
import { createServer } from 'expo-gatt-server';

await createServer(services);
```

This initializes the native GATT server and registers all services and characteristics. On iOS, the module checks Bluetooth authorization status. On Android, it checks the `BLUETOOTH_CONNECT` permission.

## Start Advertising

```typescript
import { startAdvertising } from 'expo-gatt-server';

await startAdvertising({
  localName: 'HeartSensor',
  serviceUuids: ['0000180d-0000-1000-8000-00805f9b34fb'],
  connectable: true,
});
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `localName` | `string` | -- | Device name visible to scanners |
| `serviceUuids` | `string[]` | -- | Service UUIDs to include in advertisement |
| `includeTxPowerLevel` | `boolean` | `false` | Include TX power in advertisement data |
| `connectable` | `boolean` | `true` | Whether the device accepts connections |

The promise resolves when advertising starts successfully. On iOS, this requires Bluetooth to be powered on. On Android, this requires the `BLUETOOTH_ADVERTISE` permission.

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

### Read Requests

When a central reads a characteristic without a cached value, the request is forwarded to JavaScript. Respond with `sendResponse`:

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

> **Note:** If a characteristic has a cached value (set via `value` in config or `updateCharacteristicValue`), the native layer auto-responds to reads without invoking this listener.

### Write Requests

```typescript
import { addCharacteristicWriteRequestListener } from 'expo-gatt-server';

addCharacteristicWriteRequestListener((event) => {
  console.log('Written value:', event.value);
  console.log('From device:', event.deviceId);
  // On Android, writes that need a response are auto-acknowledged by the native layer.
  // On iOS, responseNeeded indicates whether the central expects a response.
});
```

## Send Notifications

Push value updates to subscribed centrals:

```typescript
import { sendNotification } from 'expo-gatt-server';

const heartRate = [0x06, 75]; // Flags + 75 BPM

await sendNotification(
  deviceId,
  '0000180d-0000-1000-8000-00805f9b34fb', // service UUID
  '00002a37-0000-1000-8000-00805f9b34fb', // characteristic UUID
  heartRate,
);
```

Pass `confirm: true` as the fifth argument to send an indication (acknowledged by the central) instead of a notification.

Monitor delivery status:

```typescript
import { addNotificationSentListener } from 'expo-gatt-server';

addNotificationSentListener((event) => {
  if (event.status === 0) {
    console.log('Notification delivered');
  }
});
```

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

## Full Example

```typescript
import { useEffect, useRef } from 'react';
import {
  createServer,
  startAdvertising,
  stopAdvertising,
  stopServer,
  sendNotification,
  addDeviceConnectedListener,
  addDeviceDisconnectedListener,
  addCharacteristicWriteRequestListener,
  type GattServiceConfig,
} from 'expo-gatt-server';

const SERVICE_UUID = '0000180d-0000-1000-8000-00805f9b34fb';
const HR_CHAR_UUID = '00002a37-0000-1000-8000-00805f9b34fb';

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
  const connectedDevice = useRef<string | null>(null);

  useEffect(() => {
    let interval: ReturnType<typeof setInterval>;

    async function start() {
      await createServer(services);
      await startAdvertising({ localName: 'HRSensor' });
    }

    const connectSub = addDeviceConnectedListener((e) => {
      connectedDevice.current = e.deviceId;
      // Start pushing heart rate every second
      interval = setInterval(async () => {
        if (!connectedDevice.current) return;
        const bpm = 60 + Math.floor(Math.random() * 40);
        await sendNotification(
          connectedDevice.current,
          SERVICE_UUID,
          HR_CHAR_UUID,
          [0x06, bpm],
        );
      }, 1000);
    });

    const disconnectSub = addDeviceDisconnectedListener(() => {
      connectedDevice.current = null;
      clearInterval(interval);
    });

    start();

    return () => {
      clearInterval(interval);
      connectSub.remove();
      disconnectSub.remove();
      stopAdvertising();
      stopServer();
    };
  }, []);

  return null; // headless -- add your UI here
}
```

## Next Steps

| Document | Description |
|----------|-------------|
| [API Reference](api.md) | All functions, types, events, and constants |
| [Platform Setup](platform-setup.md) | Detailed iOS and Android configuration |
| [Architecture](architecture.md) | How the native bridge works |
