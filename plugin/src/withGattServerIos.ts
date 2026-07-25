import { withInfoPlist, type ConfigPlugin } from 'expo/config-plugins';

import type { ExpoGattServerPluginProps } from './index';

const PERIPHERAL_BACKGROUND_MODE = 'bluetooth-peripheral';

const DEFAULT_BLUETOOTH_ALWAYS_PERMISSION =
  'Allow $(PRODUCT_NAME) to use Bluetooth to make this device discoverable to nearby devices.';

export const withGattServerIos: ConfigPlugin<ExpoGattServerPluginProps> = (config, props) =>
  withInfoPlist(config, (config) => {
    const infoPlist = config.modResults;

    if (props.bluetoothAlwaysPermission !== false) {
      infoPlist.NSBluetoothAlwaysUsageDescription =
        props.bluetoothAlwaysPermission ??
        infoPlist.NSBluetoothAlwaysUsageDescription ??
        DEFAULT_BLUETOOTH_ALWAYS_PERMISSION;
    }

    if (props.bluetoothPeripheralBackgroundMode) {
      const modes = infoPlist.UIBackgroundModes ?? [];
      if (!modes.includes(PERIPHERAL_BACKGROUND_MODE)) {
        infoPlist.UIBackgroundModes = [...modes, PERIPHERAL_BACKGROUND_MODE];
      }
    }

    return config;
  });
