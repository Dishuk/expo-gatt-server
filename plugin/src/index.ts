import { createRunOncePlugin, type ConfigPlugin } from 'expo/config-plugins';

import { withGattServerAndroid } from './withGattServerAndroid';
import { withGattServerIos } from './withGattServerIos';

export type ExpoGattServerPluginProps = {
  /**
   * `NSBluetoothAlwaysUsageDescription`, which iOS requires before the app may touch CoreBluetooth at
   * all. A string set here replaces whatever the app config holds; `false` leaves the key untouched,
   * for an app that would rather write it itself. Omitting it fills in a generic description only when
   * the key is otherwise absent, so an existing `ios.infoPlist` entry still wins.
   *
   * @platform ios
   */
  bluetoothAlwaysPermission?: string | false;
  /**
   * Add `bluetooth-peripheral` to `UIBackgroundModes`, letting the peripheral keep advertising and
   * answering requests while the app is backgrounded. Defaults to `false`: the mode is App Store
   * reviewable and useless to a foreground-only app.
   *
   * @platform ios
   */
  bluetoothPeripheralBackgroundMode?: boolean;
  /**
   * Whether the app declares `android.hardware.bluetooth_le` as required. Defaults to `false`,
   * matching the module's own manifest, so a device without BLE hardware can still install the app.
   * Set it to `true` if the app genuinely cannot work without BLE and should be filtered off Google
   * Play accordingly. A requirement another plugin or the app config already declared is left as it
   * is, so this only ever adds the declaration — it never relaxes one.
   *
   * @platform android
   */
  requireBluetoothLeHardware?: boolean;
};

/**
 * Checks what the types cannot.
 *
 * Plugin props come from `app.json`, which is untyped JSON at prebuild time, so
 * `ExpoGattServerPluginProps` constrains nobody. The values land straight in an `Info.plist` entry and
 * an `AndroidManifest.xml` attribute, where a wrong type does not fail — it succeeds into something
 * quietly wrong. `bluetoothAlwaysPermission: true` is not a valid plist string, so iOS reads the key as
 * absent and terminates the app the moment it touches CoreBluetooth — the exact failure this plugin
 * exists to prevent. `requireBluetoothLeHardware: "false"` is a truthy string, so it sets
 * `android:required="true"` and filters the app off Google Play. Both prebuild silently today.
 */
function assertValidProps(props: ExpoGattServerPluginProps): void {
  if (props === null || typeof props !== 'object' || Array.isArray(props)) {
    throw new Error(
      `expo-gatt-server config plugin: expected an options object, received ${JSON.stringify(props)}.`,
    );
  }

  const {
    bluetoothAlwaysPermission,
    bluetoothPeripheralBackgroundMode,
    requireBluetoothLeHardware,
  } = props;

  if (
    bluetoothAlwaysPermission !== undefined &&
    bluetoothAlwaysPermission !== false &&
    typeof bluetoothAlwaysPermission !== 'string'
  ) {
    throw new Error(
      'expo-gatt-server config plugin: bluetoothAlwaysPermission must be a string (the usage ' +
        'description iOS shows the user), or false to leave the key alone. Received ' +
        `${JSON.stringify(bluetoothAlwaysPermission)}.`,
    );
  }
  if (bluetoothAlwaysPermission === '') {
    throw new Error(
      'expo-gatt-server config plugin: bluetoothAlwaysPermission cannot be empty. iOS treats an ' +
        'empty usage description as a missing one and terminates the app on first Bluetooth use.',
    );
  }

  for (const [name, value] of [
    ['bluetoothPeripheralBackgroundMode', bluetoothPeripheralBackgroundMode],
    ['requireBluetoothLeHardware', requireBluetoothLeHardware],
  ] as const) {
    if (value !== undefined && typeof value !== 'boolean') {
      throw new Error(
        `expo-gatt-server config plugin: ${name} must be a boolean. Received ` +
          `${JSON.stringify(value)}. Note that a non-empty string such as "false" is truthy.`,
      );
    }
  }
}

/**
 * Exported unwrapped for the tests. The default export wraps this in `createRunOncePlugin`, which
 * skips every application after the first — so a test calling the default export more than once
 * exercises the guard exactly once and silently passes thereafter.
 */
export const withGattServer: ConfigPlugin<ExpoGattServerPluginProps> = (config, props = {}) => {
  assertValidProps(props);
  config = withGattServerIos(config, props);
  return withGattServerAndroid(config, props);
};

// Named rather than read from package.json, which lies outside the plugin's `rootDir` and would drag
// `@types/node` in for the `require`.
export default createRunOncePlugin(withGattServer, 'expo-gatt-server');
