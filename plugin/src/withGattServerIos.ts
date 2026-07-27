import { withInfoPlist, type ConfigPlugin } from 'expo/config-plugins';

import type { ExpoGattServerPluginProps } from './index';

const PERIPHERAL_BACKGROUND_MODE = 'bluetooth-peripheral';

const DEFAULT_BLUETOOTH_ALWAYS_PERMISSION =
  'Allow $(PRODUCT_NAME) to use Bluetooth to make this device discoverable to nearby devices.';

/** The subset of `Info.plist` this plugin touches. */
export type BluetoothInfoPlist = {
  NSBluetoothAlwaysUsageDescription?: string;
  UIBackgroundModes?: string[];
};

/**
 * Writes the iOS Bluetooth keys onto [infoPlist], in place.
 *
 * Separated from the mod so the three-way precedence of `bluetoothAlwaysPermission` can be exercised
 * without Expo's mod pipeline: a string replaces whatever the app config holds, `false` leaves the key
 * untouched, and omitting it fills in a default only when the key is otherwise absent — so an existing
 * `ios.infoPlist` entry still wins. Getting that wrong either overwrites an app's own wording or leaves
 * the key missing, and iOS terminates the app the moment it touches CoreBluetooth without it.
 */
export function applyBluetoothInfoPlist(
  infoPlist: BluetoothInfoPlist,
  props: ExpoGattServerPluginProps,
): void {
  if (props.bluetoothAlwaysPermission !== false) {
    infoPlist.NSBluetoothAlwaysUsageDescription =
      props.bluetoothAlwaysPermission ??
      infoPlist.NSBluetoothAlwaysUsageDescription ??
      DEFAULT_BLUETOOTH_ALWAYS_PERMISSION;
  }

  if (props.bluetoothPeripheralBackgroundMode) {
    // A string here is the plausible hand-edit, and spreading one produced a per-character array while
    // `includes` matched substrings — so `'bluetooth-peripheral'` was left a string and the mode never
    // became an entry. Normalised into a list instead, which is what the key has to hold.
    const declared = infoPlist.UIBackgroundModes;
    const modes = Array.isArray(declared) ? declared : declared == null ? [] : [declared];
    if (!modes.includes(PERIPHERAL_BACKGROUND_MODE)) {
      infoPlist.UIBackgroundModes = [...modes, PERIPHERAL_BACKGROUND_MODE];
    } else if (!Array.isArray(declared)) {
      infoPlist.UIBackgroundModes = modes;
    }
  }
}

export const withGattServerIos: ConfigPlugin<ExpoGattServerPluginProps> = (config, props) =>
  withInfoPlist(config, (config) => {
    applyBluetoothInfoPlist(config.modResults, props);
    return config;
  });
