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
├── ExpoGattServer.types.ts   # Type definitions
└── __tests__/                # Jest suites for the TypeScript layer

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

example/                      # Runnable harness app (depends on the repo via file:..)
├── App.tsx                   # One button per public API call, plus an event log
└── app.json                  # Applies the config plugin from the repo

app.plugin.js                 # What Expo CLI looks for; re-exports plugin/build
jest.config.js                # Keeps only the iOS and Android Jest projects
```

### Key Files

| File | Role |
|------|------|
| `index.ts` | The shared layer: UUID normalisation, argument validation, graceful degradation when the native module is absent |
| `ExpoGattServerModule.swift/.kt` | Expo module definition -- parses configs, checks permissions, emits events |
| `GattServerManager.swift/.kt` | Owns the native BLE peripheral -- all Bluetooth state lives here |
| `ExpoGattServer.types.ts` | Single source of truth for the TypeScript API surface |
| `expo-module.config.json` | Tells Expo which native classes to load per platform |

## Testing

BLE peripheral functionality requires physical devices or simulators with Bluetooth support.

**Manual testing workflow:**

The `example/` app is a harness with a button for every public call and a log of every event. It
consumes the repo directly (`"expo-gatt-server": "file:.."`, with `autolinking.nativeModulesDir` pointing
at the parent), so native changes show up after a rebuild.

```bash
cd example
npm install
npx expo run:android   # or run:ios
```

Then use a BLE scanner app (e.g. nRF Connect) as the central to verify:

- Service, characteristic and descriptor discovery
- Read/write operations, including long writes
- Subscription and notification delivery
- Connection/disconnection events, and what each platform can actually observe
- Behaviour across a Bluetooth power cycle, which drops and re-publishes the database

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
4. Run `npm run lint`, `npm run test` and `npm run build`
5. Test on at least one physical device, on both platforms if the change is not platform-specific
6. Update `docs/` and `CHANGELOG.md` for anything API-visible, including a platform limitation
7. Submit a pull request with a clear description of the change

### Commit Messages

Use short, imperative-mood messages that describe the change:

```
Add MTU validation for sendNotification
Fix permission check on Android 12+
```
