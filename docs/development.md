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

`npm install` runs `prepare`, which compiles `src/` to `build/`. The package's `main` points at `build/index.js`. Clear it with `npm run clean`, restore it with `npm run build`.

## Build

| Command | Description |
|---------|-------------|
| `npm run build` | Compile TypeScript to `build/`, which is what the package's `main` points at |
| `npm run build:plugin` | Compile the config plugin to `plugin/build/` |
| `npm run clean` | Remove build artifacts |
| `npm run lint` | Run ESLint over `src` and `plugin/src` |
| `npm run typecheck` | Type-check `src` and `plugin/src`, test suites included |
| `npm test` | Jest, for the TypeScript layer and the config plugin |
| `npm run test:ios` | XCTest, for the iOS peripheral's ATT logic |
| `npm run test:android` | JUnit and Robolectric, for the Android peripheral's ATT logic |

Most scripts delegate to `expo-module-scripts`. `prepublishOnly` cleans and rebuilds both `build/` and
`plugin/build/`, neither of which is committed.

`tsconfig.json` excludes test suites; `tsconfig.check.json` includes them for `npm run typecheck`. The plugin has the same pair (`plugin/tsconfig.json` and `plugin/tsconfig.check.json`).

The example app type-checks against emitted declarations:

```bash
npm run build                                    # in the repo root, first
cd example && npx tsc --noEmit -p tsconfig.json
```

This reads `build/index.d.ts` through the `file:..` symlink and catches stale declarations before they reach consumers. CI runs both builds before this check.

## Project Layout

```
src/                          # TypeScript source (public API)
├── index.ts                  # Exported functions and types
├── ExpoGattServerModule.ts   # Native module bridge (auto-generated reference)
├── ExpoGattServer.types.ts   # Type definitions
└── __tests__/                # Jest suites for the TypeScript layer

ios/                          # iOS native implementation (Swift)
├── ExpoGattServer.podspec    # CocoaPods spec
├── ExpoGattServerModule.swift  # Expo binding: parses configs, emits events
└── GattServerManager.swift     # CoreBluetooth peripheral, and the ATT logic

android/                      # Android native implementation (Kotlin)
├── build.gradle
└── src/main/
    ├── AndroidManifest.xml
    └── java/expo/modules/gattserver/
        ├── ExpoGattServerModule.kt  # Expo binding: bridge arguments, permissions, events
        ├── GattConfiguration.kt     # What a configuration means, free of any Expo import
        ├── AttOperations.kt         # ATT arithmetic, free of the Android framework
        └── GattServerManager.kt     # BluetoothGatt server and its state

plugin/src/                   # Expo config plugin (built to plugin/build/)
├── index.ts                  # Entry point, options and their defaults
├── withGattServerIos.ts      # Info.plist mods
└── withGattServerAndroid.ts  # AndroidManifest mods

tests/                        # Native suites; the TypeScript ones live in src/__tests__
├── swift/                    # XCTest, run by `swift test` via the root Package.swift
└── android/                  # Standalone Gradle project, run by scripts/test-android.sh

example/                      # Runnable harness app (depends on the repo via file:..)
├── App.tsx                   # One button per public API call, plus an event log
└── app.json                  # Applies the config plugin from the repo

Package.swift                 # Test-only SPM package; apps consume the module via the podspec
app.plugin.js                 # What Expo CLI looks for; re-exports plugin/build
jest.config.js                # Runs one Jest project, not one per platform — see Testing
.github/workflows/ci.yml      # Runs every suite, plus a real Expo build of the Android module
```

### Key Files

| File | Role |
|------|------|
| `index.ts` | The shared layer: UUID normalisation, argument validation, graceful degradation when the native module is absent |
| `ExpoGattServerModule.swift/.kt` | Expo module definition -- reads bridge arguments, checks permissions, emits events |
| `GattConfiguration.kt` | What a configuration turns into: attributes, properties, permissions, delegation |
| `AttOperations.kt` | Write assembly, response rebasing, CCCD bits, MTU and transmission checks |
| `GattServerManager.swift/.kt` | Owns the native BLE peripheral -- all Bluetooth state lives here |
| `ExpoGattServer.types.ts` | Single source of truth for the TypeScript API surface |
| `expo-module.config.json` | Tells Expo which native classes to load per platform |

## Testing

Three automated suites plus a manual pass. All of the automated ones run on the host -- no device, no
emulator, no native app build -- and CI runs every one of them on each push.

```bash
npm test              # TypeScript layer            (jest)
npm run test:ios      # iOS peripheral logic        (XCTest, via swift test)
npm run test:android  # Android peripheral logic    (JUnit + Robolectric, via Gradle)
```

### What is covered, and what is not

| Suite | Covers |
|---|---|
| `src/__tests__/` | UUID normalisation and expansion, configuration validation, argument bounds, the ATT constants, delegation to the native module, the unsupported-platform fallbacks, and the three `Platform.OS` branches |
| `plugin/src/__tests__/` | Config plugin decisions: raising existing `android.hardware.bluetooth_le` requirements without relaxing them, and the three-way precedence of `bluetoothAlwaysPermission` |
| `tests/swift/` | Write assembly, queued-write distinction, response rebasing, ATT error-code mapping, UUID spelling, adapter-state mapping, MTU arithmetic, all rejection codes with specific messages |
| `tests/android/` | Same ATT contracts as the Swift suite (platform parity), configuration parsing against `android.bluetooth` classes, and CCCD permission derivation that blocks unbonded centrals from subscribing to encrypted characteristics |

ATT cases are duplicated across `tests/swift/` and `tests/android/` to verify platform parity.

Concurrency (lock ordering, notification settling, state machines) is reviewed by reading and exercised by hand, not by automated tests. Treat changes in that area as requiring the manual pass below.

### Running the native suites

`swift test` uses the root `Package.swift`, which compiles `ios/GattServerManager.swift` on its own --
it imports nothing but CoreBluetooth, which macOS provides. The package is test-only; apps get the
module through the podspec and Expo autolinking, and it is excluded from the npm tarball.

`npm run test:android` runs `tests/android/`, a standalone Gradle project that compiles the module's
Kotlin sources in place. It exists separately from `android/build.gradle` because that build is driven
by `expo-module-gradle-plugin`, which only resolves inside a host app's Gradle build -- running tests
through it would mean prebuilding the example app and dragging in the whole React Native toolchain.

Each harness excludes exactly one source — the platform's Expo binding, which cannot compile on the host:
`ExpoGattServerModule.kt` for Android, `ExpoGattServerModule.swift` for iOS. CI closes both gaps with a
job per platform that runs `expo prebuild` on the example app and compiles the module for real, so a
binding that no longer matches the parsers or the manager fails there rather than in someone's app. To run
them locally:

```bash
cd example && npx expo prebuild --platform android
cd android && ./gradlew :expo-gatt-server:compileDebugKotlin
```

```bash
cd example && npx expo prebuild --platform ios
cd ios && pod install
xcodebuild -workspace expogattserverexample.xcworkspace -scheme ExpoGattServer \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  build CODE_SIGNING_ALLOWED=NO
```

Building the `ExpoGattServer` scheme rather than the app's compiles the module and its dependencies and
nothing else, which is all that is needed and much the faster of the two.

### Manual testing

Everything above stops at the point where CoreBluetooth or the Bluetooth stack takes over. The
`example/` app is a harness with a button for every public call and a log of every event. It consumes
the repo directly (`"expo-gatt-server": "file:.."`, with `autolinking.nativeModulesDir` pointing at the
parent), so native changes show up after a rebuild.

```bash
npm run build          # in the repo root, if you have not already — the example resolves build/
cd example
npm install
npx expo run:android   # or run:ios
```

TypeScript changes need that `npm run build` again; native changes need a rebuild of the app.

Then use a BLE scanner app (e.g. nRF Connect) as the central to verify:

- Service, characteristic and descriptor discovery
- Read/write operations, including long writes -- and that a long write stopping short of the
  attribute's end leaves the remainder, on both platforms
- Subscription and notification delivery, including that an unbonded central cannot subscribe to a
  characteristic declared `readEncrypted`
- Connection/disconnection events, and what each platform can actually observe
- Behaviour across a Bluetooth power cycle, which drops and re-publishes the database
- Sustained notification streams, and a central renegotiating the MTU mid-stream

## Code Style

- **TypeScript:** Follow the linting rules in the project (via `expo-module-scripts`)
- **Swift:** Standard Swift conventions, Expo module patterns
- **Kotlin:** Standard Kotlin conventions, Expo module patterns

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`, and the native suite for any
   platform you touched (`npm run test:ios` / `npm run test:android`)
5. Test on at least one physical device, on both platforms if the change is not platform-specific
6. Update `docs/` and `CHANGELOG.md` for anything API-visible, including a platform limitation
7. Submit a pull request with a clear description of the change

### Changing the ATT logic

Changes to write assembly, response rebasing, CCCD handling, or MTU checks require matching test cases in **both** `tests/swift/` and `tests/android/`. New tests should fail if their behavior is removed; verify this by reintroducing the bug and watching the suite fail.

### Keeping the suites honest

Avoid inflating test count with cross-products or data enumeration:
- Test each axis once, not their product (e.g., `byteArrays.test.ts`: one bad value per entry point).
- One representative error code per entry point pins pass-through behavior; all 40 codes asserted it 40 times over.
- `platformBranching.test.ts` mocks `Platform` in one run, testing both sides together (hence a single Jest project).

### Commit Messages

Use short, imperative-mood messages that describe the change:

```
Add MTU validation for sendNotification
Fix permission check on Android 12+
```
