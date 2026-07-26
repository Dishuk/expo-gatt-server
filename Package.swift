// swift-tools-version:5.9
//
// A test-only package. It exists so the iOS peripheral's ATT logic can be exercised on the host with
// `swift test`, and is deliberately **not** how the module is consumed — apps get it through
// `ios/ExpoGattServer.podspec` and Expo autolinking, and this file is excluded from the npm tarball.
//
// `GattServerManager.swift` and `GattConfigurationParsing.swift` are compiled: both import nothing
// beyond CoreBluetooth and Foundation, which macOS provides, so they build and run off-device.
// `ExpoGattServerModule.swift` is left out because it imports ExpoModulesCore, which has no host
// build.
//
// The parsing was moved into its own file precisely so it could be compiled here. It used to sit in
// the binding, justified by the claim that "the TypeScript suite validates the same configuration
// before it is ever handed over" — which was not true in the way that mattered: the TypeScript suite
// validates its own copy of the rules and cannot see the Swift decoding at all. Two bugs lived in
// that blind spot, both of the form `map["value"] as? [Int]` against a dictionary whose numbers
// arrive as `Double`, silently discarding every configured characteristic and descriptor value.
//
// The binding that remains is still compiled by nothing here, so `.github/workflows/ci.yml` has an
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
      // Named so the two files SwiftPM cannot build here stop being reported as unhandled resources.
      exclude: ["ExpoGattServerModule.swift", "ExpoGattServer.podspec"],
      sources: ["GattServerManager.swift", "GattConfigurationParsing.swift"]
    ),
    .testTarget(
      name: "GattServerCoreTests",
      dependencies: ["GattServerCore"],
      path: "tests/swift"
    ),
  ]
)
