# expo-gatt-server - BLE Peripheral for Expo

An Expo module that turns your React Native app into a BLE GATT server (peripheral). Advertise services, handle read/write requests, and push notifications to connected centrals -- all from JavaScript.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| [Expo](https://expo.dev/) | SDK 57 | Framework and module system -- the version this package is developed and tested against |
| [React Native](https://reactnative.dev/) | as bundled with your Expo SDK | Runtime |
| [Node.js](https://nodejs.org/) | >= 18 | Build tooling |
| Xcode | 26.5 | iOS builds (iOS 15.1+ deployment target, Swift 5.9). Bounded at both ends by Expo SDK 57 rather than by this package: its prebuilt artefacts need Swift 6.2, and later compilers do not yet build them -- see the `ios-integration` job in `.github/workflows/ci.yml` |
| Android Studio | any | Android builds (API 24+ / Android 7.0, compileSdk 35) |

**A development build is required.** The package ships native code, so it cannot run in Expo Go. Use
`npx expo run:ios` / `npx expo run:android`, or EAS Build.

## Features

- **Peripheral mode** -- Act as a BLE GATT server, not just a client
- **Cross-platform** -- Unified API across iOS (CoreBluetooth) and Android (BluetoothGatt), with the
  platform differences documented rather than papered over
- **Expo native modules** -- No manual linking, auto-configured via expo-modules
- **Config plugin** -- Ships its own iOS permission, background mode and Android BLE requirement configuration
- **Event-driven** -- Connections, subscriptions, read/write requests, notification delivery, MTU
  changes and Bluetooth adapter state
- **Answer requests yourself** -- Per-characteristic `delegate` opt-in hands reads and writes to
  JavaScript, so a write can be validated or rejected and a read can be computed
- **MTU-aware** -- Validates payload size against the negotiated MTU before sending, and exposes the
  MTU so payloads can be sized up front
- **Portable UUIDs** -- 16-bit, 32-bit and 128-bit forms all accepted, normalised before either
  platform sees them
- **Safe to import anywhere** -- `isSupported()` reports whether the native module is present;
  importing never throws on web or in Expo Go

## Quick Start

```bash
# Install
npx expo install expo-gatt-server
```

Add the config plugin to `app.json` -- it writes the iOS Bluetooth usage description your app cannot
launch CoreBluetooth without, and exposes the background mode and the Android BLE hardware requirement
as options. See [Getting Started](./docs/getting-started.md#config-plugin).

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

The config plugin writes `NSBluetoothAlwaysUsageDescription` for you. To set it by hand instead, add it
to `Info.plist` (or `app.json` under `expo.ios.infoPlist`):

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices.</string>
```

### Android

`BLUETOOTH`, `BLUETOOTH_ADMIN` (both API <= 30), `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT` are
declared in the module's `AndroidManifest.xml` and merged automatically. No location permission is
declared -- that is a scanning concern, and this module never scans.

For Android 12+ (API 31), the module checks `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` at runtime --
request them before calling `createServer` or `startAdvertising`. See
[Platform Setup](docs/platform-setup.md#permissions-1).

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
│   └── GattServerManager.swift    # CoreBluetooth peripheral manager
├── android/
│   ├── build.gradle               # Android build config (API 24+)
│   └── src/main/java/expo/modules/gattserver/
│       ├── ExpoGattServerModule.kt  # Expo module definition
│       └── GattServerManager.kt     # BluetoothGatt server manager
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
