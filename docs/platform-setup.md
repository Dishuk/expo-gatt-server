# Platform Setup

Detailed iOS and Android configuration for BLE peripheral mode.

- [iOS](#ios)
  - [Minimum Version](#minimum-version)
  - [Permissions](#permissions)
  - [Background Modes](#background-modes)
  - [Troubleshooting](#troubleshooting)
- [Android](#android)
  - [Minimum Version](#minimum-version-1)
  - [Permissions](#permissions-1)
  - [Hardware Requirements](#hardware-requirements)
  - [Troubleshooting](#troubleshooting-1)

## iOS

### Minimum Version

iOS 15.1+. Set in the podspec and enforced at build time.

### Permissions

Add a Bluetooth usage description. Without this, the app crashes on launch when accessing CoreBluetooth.

**Config plugin** (recommended -- see [Getting Started](./getting-started.md#config-plugin)):

```json
{
  "expo": {
    "plugins": [
      ["expo-gatt-server", { "bluetoothAlwaysPermission": "This app uses Bluetooth to communicate with nearby devices." }]
    ]
  }
}
```

Adding the plugin with no options writes a generic description instead, so the key is never simply missing.

**app.json:**

```json
{
  "expo": {
    "ios": {
      "infoPlist": {
        "NSBluetoothAlwaysUsageDescription": "This app uses Bluetooth to communicate with nearby devices."
      }
    }
  }
}
```

An `ios.infoPlist` value set this way wins, as long as the plugin's `bluetoothAlwaysPermission` is left unset.

**Raw Info.plist:**

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices.</string>
```

The module checks `CBPeripheralManager.authorization` at runtime in `createServer`. If authorization is denied or restricted, the promise rejects.

### Background Modes

To advertise and handle requests while the app is backgrounded, enable the `bluetooth-peripheral` background mode:

**Config plugin:**

```json
{
  "expo": {
    "plugins": [["expo-gatt-server", { "bluetoothPeripheralBackgroundMode": true }]]
  }
}
```

**app.json:**

```json
{
  "expo": {
    "ios": {
      "infoPlist": {
        "UIBackgroundModes": ["bluetooth-peripheral"]
      }
    }
  }
}
```

> **Note:** Background advertising on iOS is heavily throttled. The system may reduce advertising frequency, remove the local name from advertisements, and merge your advertisement with others.

### Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `createServer` rejects with permission error | Bluetooth authorization not granted | Check `NSBluetoothAlwaysUsageDescription` is set, user accepted the prompt |
| `startAdvertising` rejects | Bluetooth not powered on | Ensure Bluetooth is enabled in Settings |
| `sendNotification` rejects with `ERR_NO_SUBSCRIBER` | No subscribers | The central must enable notifications or indications on the characteristic first -- wait for `onCharacteristicSubscribed` |
| `onDeviceConnected` not firing | `CBPeripheralManagerDelegate` has no connection callback, so iOS reports a central on its first ATT activity -- a subscribe, read or write | Expected. A central that connects but never touches an attribute is invisible to the peripheral role |
| `onDeviceDisconnected` not firing | iOS can only infer a disconnection from the loss of the last subscription, or from Bluetooth being turned off | Expected for a central that never subscribed. See [Platform Differences](./architecture.md#platform-differences) |

## Android

### Minimum Version

API 24 (Android 7.0). Set in `build.gradle` via `minSdkVersion 24`.

### Permissions

The module's `AndroidManifest.xml` declares only the permissions a GATT **peripheral** needs. They are merged into your app's manifest automatically, so the module deliberately declares nothing your app might not want.

**Declared permissions:**

| Permission | API Level | Purpose |
|------------|-----------|---------|
| `BLUETOOTH` | <= 30 | GATT server operations (`@RequiresLegacyBluetoothPermission`) |
| `BLUETOOTH_ADMIN` | <= 30 | BLE advertisement (`@RequiresLegacyBluetoothAdminPermission`) |
| `BLUETOOTH_ADVERTISE` | 31+ | BLE advertisement |
| `BLUETOOTH_CONNECT` | 31+ | GATT server operations |

**No location permission is declared.** Location is a *scanning* concern -- Android requires it "because, on Android 11 and lower, a Bluetooth scan could potentially be used to gather information about the location of the user" -- and this module only advertises and serves GATT, never scans. If your app also scans, declare `BLUETOOTH_SCAN` (and `ACCESS_FINE_LOCATION`, or `usesPermissionFlags="neverForLocation"`) yourself.

Earlier versions declared `ACCESS_FINE_LOCATION` unconditionally, which every consuming app inherited.

**Runtime permissions (Android 12+ / API 31):**

`BLUETOOTH_CONNECT` and `BLUETOOTH_ADVERTISE` require runtime requests. The module checks these before `createServer` and `startAdvertising` respectively, and rejects with a descriptive error if not granted.

Request them in your app before calling the module:

```typescript
import { PermissionsAndroid, Platform } from 'react-native';

async function requestBlePermissions() {
  if (Platform.OS !== 'android' || Platform.Version < 31) return;

  const result = await PermissionsAndroid.requestMultiple([
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
  ]);

  const allGranted = Object.values(result).every(
    (v) => v === PermissionsAndroid.RESULTS.GRANTED,
  );

  if (!allGranted) {
    throw new Error('BLE permissions not granted');
  }
}
```

### Hardware Requirements

The module declares `android.hardware.bluetooth_le` with `required="false"`, so it appears in your merged manifest without filtering your app off Google Play on devices that lack BLE hardware. Whether BLE is essential is your app's decision, not a dependency's.

If your app genuinely cannot work without BLE, ask the config plugin to declare it required. The manifest merger ORs `android:required`, so the entry the plugin writes into your app's manifest takes the stronger value:

```json
{
  "expo": {
    "plugins": [["expo-gatt-server", { "requireBluetoothLeHardware": true }]]
  }
}
```

Or write it in your own manifest directly:

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

Otherwise, check at runtime and degrade gracefully:

```typescript
// PackageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE), or:
import { getBluetoothState } from 'expo-gatt-server';
const state = await getBluetoothState(); // 'unsupported' when there is no BLE adapter
```

Earlier versions declared `required="true"`, which every consuming app inherited.

### Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `createServer` rejects with permission error | `BLUETOOTH_CONNECT` not granted | Request runtime permission first (API 31+) |
| `startAdvertising` rejects | `BLUETOOTH_ADVERTISE` not granted, or Bluetooth adapter off | Request permission and check `BluetoothAdapter.isEnabled()` |
| MTU errors on notification | Central hasn't negotiated a larger MTU | Default MTU is 23 octets (20-byte payload). Size payloads against `getMtu(deviceId).maxNotificationPayload`, or wait for `addMtuChangedListener` to report a larger one |
| `deviceId` is a MAC address | Expected on Android | iOS uses UUID, Android uses MAC address. Normalize in your app logic if needed |
| App crashes on API < 31 | Legacy permissions missing | Ensure `BLUETOOTH` and `BLUETOOTH_ADMIN` are in the merged manifest (they are by default) |
