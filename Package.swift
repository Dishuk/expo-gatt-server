// swift-tools-version:5.9
//
// A test-only package. It exists so the iOS peripheral's ATT logic can be exercised on the host with
// `swift test`, and is deliberately **not** how the module is consumed — apps get it through
// `ios/ExpoGattServer.podspec` and Expo autolinking, and this file is excluded from the npm tarball.
//
// Only `GattServerManager.swift` is compiled: it imports nothing but CoreBluetooth, which macOS
// provides, so it builds and runs off-device. `ExpoGattServerModule.swift` is left out because it
// imports ExpoModulesCore, which has no host build — the configuration parsing that lives there is
// covered by the TypeScript suite, which validates the same configuration before it is ever handed
// over.
//
// That leaves the binding itself compiled by nothing here, so `.github/workflows/ci.yml` has an
// `ios-integration` job that prebuilds the example app and builds the `ExpoGattServer` pod target for
// real — the counterpart of `android-integration`. Without it a binding that no longer matched the
// manager would reach consumers unnoticed.
import PackageDescription

let package = Package(
  name: "ExpoGattServer",
  platforms: [.macOS(.v11)],
  targets: [
    .target(
      name: "GattServerCore",
      path: "ios",
      sources: ["GattServerManager.swift"]
    ),
    .testTarget(
      name: "GattServerCoreTests",
      dependencies: ["GattServerCore"],
      path: "tests/swift"
    ),
  ]
)
