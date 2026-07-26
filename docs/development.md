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

`npm install` runs `prepare`, which compiles `src/` to `build/`. That matters because the package's
`main` points at `build/index.js` — the example app resolves the module through it, so a checkout with
no `build/` cannot bundle. If you ever clear it, `npm run build` puts it back.

## Build

| Command | Description |
|---------|-------------|
| `npm run build` | Compile TypeScript to `build/`, which is what the package's `main` points at |
| `npm run build:plugin` | Compile the config plugin to `plugin/build/` |
| `npm run clean` | Remove build artifacts |
| `npm run lint` | Run ESLint |
| `npm run typecheck` | Type-check everything, test suites included |
| `npm test` | Jest, for the TypeScript layer |
| `npm run test:ios` | XCTest, for the iOS peripheral's ATT logic |
| `npm run test:android` | JUnit and Robolectric, for the Android peripheral's ATT logic |

Most scripts delegate to `expo-module-scripts`. `prepublishOnly` cleans and rebuilds both `build/` and
`plugin/build/`, neither of which is committed.

`tsconfig.json` drives the published build and excludes the test suites, so they are never emitted into
`build/`. `tsconfig.check.json` includes them, and is what `npm run typecheck` uses.

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

The suites target the logic that is **invisible until a peer connects**: a mis-assembled write, a
response aligned to the wrong offset, a subscription that bypasses the value's own encryption. None of
that raises an error at runtime -- it just puts the wrong bytes on the air -- so it is exactly what is
worth pinning.

| Suite | Covers |
|---|---|
| `src/__tests__/` | UUID normalisation and expansion, configuration validation, argument bounds, the ATT constants, delegation to the native module, the unsupported-platform fallbacks, and the three `Platform.OS` branches |
| `tests/swift/` | Write assembly and the queued-write distinction, response rebasing, the ATT error-code mapping, UUID spelling, adapter-state mapping, MTU arithmetic, every rejection code and the messages that have to say something specific |
| `tests/android/` | The same ATT contracts as the Swift suite, case for case, plus configuration parsing against the real `android.bluetooth` classes -- including the CCCD permission derivation that stops an unbonded central subscribing to an encrypted characteristic |

Several ATT cases are duplicated deliberately across `tests/swift/` and `tests/android/`. The platforms
implement those contracts independently, and asserting that the same inputs produce the same bytes on
each is the only thing holding them together -- the module's premise is that one configuration behaves
the same either side, and a divergence there is otherwise found by a user.

**Not covered, and honestly so:** the concurrency. Lock ordering, the advertising generation counter,
exactly-once settling of notification promises across a disconnect, and the publication state machine
are all reviewed by reading and exercised by hand, not by tests. Mock-driven "concurrency tests" would
pass regardless of whether the real races are handled, which is worse than not having them. Treat any
change in that area as needing the manual pass below.

### Running the native suites

`swift test` uses the root `Package.swift`, which compiles `ios/GattServerManager.swift` on its own --
it imports nothing but CoreBluetooth, which macOS provides. The package is test-only; apps get the
module through the podspec and Expo autolinking, and it is excluded from the npm tarball.

`npm run test:android` runs `tests/android/`, a standalone Gradle project that compiles the module's
Kotlin sources in place. It exists separately from `android/build.gradle` because that build is driven
by `expo-module-gradle-plugin`, which only resolves inside a host app's Gradle build -- running tests
through it would mean prebuilding the example app and dragging in the whole React Native toolchain.

That harness excludes `ExpoGattServerModule.kt`, the only source that imports Expo. CI closes the gap
with a job that runs `expo prebuild` on the example app and compiles `:expo-gatt-server` for real, so a
binding that no longer matches the parsers fails there rather than in someone's app. To run it locally:

```bash
cd example && npx expo prebuild --platform android
cd android && ./gradlew :expo-gatt-server:compileDebugKotlin
```

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

A change to write assembly, response rebasing, CCCD handling or the MTU checks needs the matching case
in **both** `tests/swift/` and `tests/android/`, not just the platform you edited — that pairing is what
keeps the two implementations agreeing.

A new test should fail if the behaviour it describes is removed. Reintroducing a bug and watching the
suite go red is a cheap way to check that, and worth doing for anything security- or wire-visible.

### Keeping the suites honest

Test *count* is not the goal, and two patterns are worth resisting because they inflate it without
adding detection:

- **Cross products.** Where one axis is a shared predicate and the other is the call sites that consult
  it, test each axis once. `byteArrays.test.ts` is written this way: the interesting byte values go
  through one entry point, and every entry point gets one bad value. Multiplying the two asserted the
  same predicate seventy times.
- **Enumerating data instead of behaviour.** The set of error codes a platform can raise is
  documentation; the wrapper has no per-code logic. One representative code per entry point pins the
  pass-through — forty of them pinned it forty times over.

Reaching a conditional by running the whole suite twice is the same trap: `platformBranching.test.ts`
mocks `Platform` and asserts both sides in one run, which is why there is a single Jest project.

### Commit Messages

Use short, imperative-mood messages that describe the change:

```
Add MTU validation for sendNotification
Fix permission check on Android 12+
```
