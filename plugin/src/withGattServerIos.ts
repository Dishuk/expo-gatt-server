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
 * Apply iOS Bluetooth keys. Precedence: string replaces, false leaves untouched, undefined fills default if absent.
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
    // Normalize to array—spreading a string produces per-character array.
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
