// swift-tools-version:5.9
//
// Test-only package for host testing of iOS ATT logic (not consumed by apps, which use the podspec).
// Compiles GattServerManager.swift and GattConfigurationParsing.swift (Core only, no ExpoModulesCore).
//
// Parsing extracted to GattConfigurationParsing.swift so it can build and be tested here.
// The binding is tested via ios-integration CI job (uses prebuilt example app).
import PackageDescription

let package = Package(
  name: "ExpoGattServer",
  platforms: [.macOS(.v11)],
  targets: [
    .target(
      name: "GattServerCore",
      path: "ios",
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
