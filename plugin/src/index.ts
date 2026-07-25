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

const withGattServer: ConfigPlugin<ExpoGattServerPluginProps> = (config, props = {}) => {
  config = withGattServerIos(config, props);
  return withGattServerAndroid(config, props);
};

// Named rather than read from package.json, which lies outside the plugin's `rootDir` and would drag
// `@types/node` in for the `require`.
export default createRunOncePlugin(withGattServer, 'expo-gatt-server');
