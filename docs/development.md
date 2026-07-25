# Development

Build commands, testing, and contributing.

- [Setup](#setup)
- [Build](#build)
- [Project Layout](#project-layout)
- [Testing](#testing)
- [Code Style](#code-style)
- [Contributing](#contributing)

## Setup

```bash
git clone https://github.com/Dishuk/expo-gatt-server.git
cd expo-gatt-server
npm install
```

## Build

| Command | Description |
|---------|-------------|
| `npm run build` | Compile TypeScript to `build/` |
| `npm run build:plugin` | Compile the config plugin to `plugin/build/` |
| `npm run clean` | Remove build artifacts |
| `npm run lint` | Run ESLint |
| `npm run test` | Run tests |

All scripts delegate to `expo-module-scripts`. `prepublishOnly` runs `build:plugin`, since `plugin/build/`
is published but not committed.

## Project Layout

```
src/                          # TypeScript source (public API)
├── index.ts                  # Exported functions and types
├── ExpoGattServerModule.ts   # Native module bridge (auto-generated reference)
└── ExpoGattServer.types.ts   # Type definitions

ios/                          # iOS native implementation (Swift)
├── ExpoGattServer.podspec    # CocoaPods spec
├── ExpoGattServerModule.swift
└── GattServerManager.swift

android/                      # Android native implementation (Kotlin)
├── build.gradle
└── src/main/
    ├── AndroidManifest.xml
    └── java/expo/modules/gattserver/
        ├── ExpoGattServerModule.kt
        └── GattServerManager.kt

plugin/src/                   # Expo config plugin (built to plugin/build/)
├── index.ts                  # Entry point, options and their defaults
├── withGattServerIos.ts      # Info.plist mods
└── withGattServerAndroid.ts  # AndroidManifest mods

app.plugin.js                 # What Expo CLI looks for; re-exports plugin/build
```

### Key Files

| File | Role |
|------|------|
| `ExpoGattServerModule.swift/.kt` | Expo module definition -- parses configs, checks permissions, emits events |
| `GattServerManager.swift/.kt` | Owns the native BLE peripheral -- all Bluetooth state lives here |
| `ExpoGattServer.types.ts` | Single source of truth for the TypeScript API surface |
| `expo-module.config.json` | Tells Expo which native classes to load per platform |

## Testing

BLE peripheral functionality requires physical devices or simulators with Bluetooth support.

**Manual testing workflow:**

1. Create an example Expo app that imports the module locally
2. Run on a physical device (simulators have limited BLE support)
3. Use a BLE scanner app (e.g., nRF Connect) as the central to verify:
   - Service and characteristic discovery
   - Read/write operations
   - Notification delivery
   - Connection/disconnection events

**Automated testing:**

`npm run test` runs the TypeScript layer's unit tests in `src/__tests__/`, covering UUID normalisation,
configuration validation, the ATT constants and the unsupported-platform guard. The native module is
mocked, so they need no device and no native build. The `expo-module-scripts` preset runs every suite
once per platform; `jest.config.js` keeps the iOS and Android projects and drops the web and node ones,
since this package declares no web platform.

Native layer testing requires platform-specific test harnesses.

## Code Style

- **TypeScript:** Follow the linting rules in the project (via `expo-module-scripts`)
- **Swift:** Standard Swift conventions, Expo module patterns
- **Kotlin:** Standard Kotlin conventions, Expo module patterns

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run `npm run lint` and `npm run build`
5. Test on at least one physical device
6. Submit a pull request with a clear description of the change

### Commit Messages

Use short, imperative-mood messages that describe the change:

```
Add MTU validation for sendNotification
Fix permission check on Android 12+
```
