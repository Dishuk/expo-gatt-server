# expo-gatt-server - BLE Peripheral for Expo

An Expo module that turns your React Native app into a BLE GATT server (peripheral). Advertise services, handle read/write requests, and push notifications to connected centrals -- all from JavaScript.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| [Expo](https://expo.dev/) | SDK 51+ | Framework and module system |
| [React Native](https://reactnative.dev/) | 0.74+ | Runtime |
| [Node.js](https://nodejs.org/) | >= 18 | Build tooling |
| Xcode | >= 15 | iOS builds (iOS 15.1+ deployment target) |
| Android Studio | any | Android builds (API 24+ / Android 7.0) |

## Features

- **Peripheral mode** -- Act as a BLE GATT server, not just a client
- **Cross-platform** -- Unified API across iOS (CoreBluetooth) and Android (BluetoothGatt)
- **Expo native modules** -- No manual linking, auto-configured via expo-modules
- **Event-driven** -- Subscribe to connections, read/write requests, and notification delivery
- **MTU-aware** -- Validates payload size against negotiated MTU before sending

## Quick Start

```bash
# Install
npx expo install expo-gatt-server

# Add to your app
npx expo prebuild
```

```typescript
import {
  createServer,
  startAdvertising,
  addCharacteristicWriteRequestListener,
  sendNotification,
} from 'expo-gatt-server';

// Define a service with a writable + notifiable characteristic
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
await startAdvertising({ localName: 'MyDevice' });

// Handle incoming writes
addCharacteristicWriteRequestListener((event) => {
  console.log('Received:', event.value);
});
```

## Platform Permissions

### iOS

Add to `Info.plist` (or `app.json` under `expo.ios.infoPlist`):

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices.</string>
```

### Android

Permissions are declared in the module's `AndroidManifest.xml` and merged automatically. For Android 12+ (API 31), the module checks `BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` at runtime -- request them before calling `createServer` or `startAdvertising`.

## Project Structure

```
expo-gatt-server/
├── src/
│   ├── index.ts                   # Public API
│   ├── ExpoGattServerModule.ts    # Native module bridge
│   └── ExpoGattServer.types.ts    # TypeScript type definitions
├── ios/
│   ├── ExpoGattServer.podspec     # CocoaPods spec (iOS 15.1+)
│   ├── ExpoGattServerModule.swift # Expo module definition
│   └── GattServerManager.swift    # CoreBluetooth peripheral manager
├── android/
│   ├── build.gradle               # Android build config (API 24+)
│   └── src/main/java/expo/modules/gattserver/
│       ├── ExpoGattServerModule.kt  # Expo module definition
│       └── GattServerManager.kt     # BluetoothGatt server manager
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
