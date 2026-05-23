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

**Raw Info.plist:**

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices.</string>
```

The module checks `CBPeripheralManager.authorization` at runtime in `createServer`. If authorization is denied or restricted, the promise rejects.

### Background Modes

To advertise and handle requests while the app is backgrounded, enable the `bluetooth-peripheral` background mode:

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
| Notifications fail silently | No subscribers | The central must subscribe to the characteristic's CCCD before receiving notifications |
| `onDeviceConnected` not firing | iOS fires this on first subscription, not raw connection | This is expected behavior -- wait for the central to subscribe |

## Android

### Minimum Version

API 24 (Android 7.0). Set in `build.gradle` via `minSdkVersion 24`.

### Permissions

The module's `AndroidManifest.xml` declares all required permissions. They are merged into your app's manifest automatically.

**Declared permissions:**

| Permission | API Level | Purpose |
|------------|-----------|---------|
| `BLUETOOTH` | < 31 | Legacy Bluetooth access |
| `BLUETOOTH_ADMIN` | < 31 | Legacy Bluetooth management |
| `BLUETOOTH_ADVERTISE` | 31+ | BLE advertisement |
| `BLUETOOTH_CONNECT` | 31+ | GATT server operations |
| `ACCESS_FINE_LOCATION` | all | Required for BLE on some devices |

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
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
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

The module declares `android.hardware.bluetooth_le` as required (`required="true"`). Devices without BLE hardware will not see your app on Google Play.

To support devices without BLE (with graceful degradation), override this in your app's manifest:

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
```

### Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `createServer` rejects with permission error | `BLUETOOTH_CONNECT` not granted | Request runtime permission first (API 31+) |
| `startAdvertising` rejects | `BLUETOOTH_ADVERTISE` not granted, or Bluetooth adapter off | Request permission and check `BluetoothAdapter.isEnabled()` |
| MTU errors on notification | Central hasn't negotiated a larger MTU | Default MTU is 23 bytes (20 payload). Either send smaller payloads or wait for `onMtuChanged` |
| `deviceId` is a MAC address | Expected on Android | iOS uses UUID, Android uses MAC address. Normalize in your app logic if needed |
| App crashes on API < 31 | Legacy permissions missing | Ensure `BLUETOOTH` and `BLUETOOTH_ADMIN` are in the merged manifest (they are by default) |
