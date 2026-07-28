# expo-gatt-server - BLE Peripheral for Expo

Expo module that turns your React Native app into a BLE GATT server. Advertise services, handle read/write requests, send notifications to connected centrals—from JavaScript.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| [Expo](https://expo.dev/) | SDK 57 | Framework and module system -- the version this package is developed and tested against |
| [React Native](https://reactnative.dev/) | as bundled with your Expo SDK | Runtime |
| [Node.js](https://nodejs.org/) | >= 18 | Build tooling |
| Xcode | 26.5 | iOS builds (iOS 15.1+ deployment target, Swift 5.9). Bounded by Expo SDK 57 compatibility. |
| Android Studio | any | Android builds (API 24+ / Android 7.0, compileSdk 35) |

**A development build is required.** The package ships native code, so it cannot run in Expo Go. Use
`npx expo run:ios` / `npx expo run:android`, or EAS Build.

## Features

- **Peripheral mode** -- Act as a BLE GATT server, not just a client
- **Cross-platform** -- Unified API across iOS (CoreBluetooth) and Android (BluetoothGatt); platform differences documented
- **Expo native modules** -- No manual linking, auto-configured via expo-modules
- **Config plugin** -- Ships its own iOS permission, background mode and Android BLE requirement configuration
- **Event-driven** -- Connections, subscriptions, read/write requests, notification delivery, MTU changes, adapter state
- **Delegated handlers** -- Per-characteristic `delegate` opt-in validates or rejects writes, computes reads in JavaScript
- **MTU-aware** -- Validates payload size against negotiated MTU; exposes MTU for payload sizing
- **Portable UUIDs** -- 16-bit, 32-bit, and 128-bit forms accepted and normalized
- **Safe to import anywhere** -- `isSupported()` reports whether the native module is present;
  importing never throws on web or in Expo Go

## Quick Start

```bash
# Install
npx expo install expo-gatt-server
```

Add the config plugin to `app.json` to configure iOS Bluetooth usage description, background mode, and Android BLE requirement. See [Getting Started](./docs/getting-started.md#config-plugin).

```json
{
  "expo": {
    "plugins": ["expo-gatt-server"]
  }
}
```

```bash
# Generate the native projects
npx expo prebuild
```

```typescript
import {
  createServer,
  startAdvertising,
  addCharacteristicSubscribedListener,
  addCharacteristicWriteRequestListener,
  sendNotification,
} from 'expo-gatt-server';

// Define a service with a writable + notifiable characteristic.
// UUIDs may be 16-bit, 32-bit or 128-bit; short forms are expanded for you.
await createServer([
  {
    uuid: '00001234-0000-1000-8000-00805f9b34fb',
    characteristics: [
      {
        uuid: '00005678-0000-1000-8000-00805f9b34fb',
        properties: ['write', 'notify'],
        permissions: ['writeable'],
      },
    ],
  },
]);

// Start advertising
await startAdvertising({
  localName: 'MyDevice',
  serviceUuids: ['00001234-0000-1000-8000-00805f9b34fb'],
});

// Handle incoming writes. The module has already acknowledged them; set
// `delegate: { write: true }` on the characteristic to answer them yourself.
addCharacteristicWriteRequestListener((event) => {
  console.log('Received:', event.value);
});

// Push updates only once a central has subscribed -- before that there is nobody to send to.
addCharacteristicSubscribedListener((event) => {
  sendNotification(
    event.deviceId,
    '00001234-0000-1000-8000-00805f9b34fb',
    '00005678-0000-1000-8000-00805f9b34fb',
    [0x01],
  ).catch((error: unknown) => console.warn(error));
});
```

Event payloads report UUIDs as the lowercase 128-bit form on both platforms, whichever spelling the
configuration used.

## Platform Permissions

### iOS

The config plugin writes `NSBluetoothAlwaysUsageDescription`. To set it manually instead, add to `Info.plist` or `app.json` under `expo.ios.infoPlist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices.</string>
```

### Android

Permissions declared in `AndroidManifest.xml`:
- `BLUETOOTH`, `BLUETOOTH_ADMIN` (API <= 30)
- `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (API 31+)

For Android 12+ (API 31), request `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` at runtime before calling `createServer` or `startAdvertising`. See [Platform Setup](docs/platform-setup.md#permissions-1).

## Project Structure

```
expo-gatt-server/
├── src/
│   ├── index.ts                   # Public API
│   ├── ExpoGattServerModule.ts    # Native module bridge
│   ├── ExpoGattServer.types.ts    # TypeScript type definitions
│   └── __tests__/                 # Unit tests for the TypeScript layer
├── ios/
│   ├── ExpoGattServer.podspec     # CocoaPods spec (iOS 15.1+)
│   ├── ExpoGattServerModule.swift # Expo module definition
│   ├── GattConfigurationParsing.swift # Configuration decoding, host-testable
│   └── GattServerManager.swift    # CoreBluetooth peripheral manager
├── android/
│   ├── build.gradle               # Android build config (API 24+)
│   └── src/main/java/expo/modules/gattserver/
│       ├── AttOperations.kt         # ATT property, permission and error maps
│       ├── ExpoGattServerModule.kt  # Expo module definition
│       ├── GattConfiguration.kt     # Configuration parsing, host-testable
│       └── GattServerManager.kt     # BluetoothGatt server manager
├── tests/                         # Host-side Swift and Kotlin suites
├── plugin/src/                    # Expo config plugin (built to plugin/build/)
├── example/                       # Runnable harness app for local development
├── docs/                          # Documentation
├── expo-module.config.json        # Expo module platform config
└── package.json
```

## Documentation

| Document | Description |
|----------|-------------|
| [Getting Started](docs/getting-started.md) | Step-by-step tutorial: install, configure, advertise, handle requests |
| [API Reference](docs/api.md) | All functions, types, events, and constants |
| [Platform Setup](docs/platform-setup.md) | iOS and Android permissions, capabilities, and troubleshooting |
| [Architecture](docs/architecture.md) | System design, native bridge, event flow |
| [Development](docs/development.md) | Build commands, testing, contributing |

## License

MIT
