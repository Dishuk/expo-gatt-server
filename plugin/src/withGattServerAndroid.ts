import { withAndroidManifest, type ConfigPlugin } from 'expo/config-plugins';

import type { ExpoGattServerPluginProps } from './index';

const BLUETOOTH_LE_FEATURE = 'android.hardware.bluetooth_le';

export const withGattServerAndroid: ConfigPlugin<ExpoGattServerPluginProps> = (config, props) =>
  withAndroidManifest(config, (config) => {
    const { manifest } = config.modResults;

    const features = (manifest['uses-feature'] ??= []);
    const declared = features.find(
      (feature) => feature.$['android:name'] === BLUETOOTH_LE_FEATURE,
    );
    if (declared) {
      // A declaration another plugin or the app config already made is left alone unless this one is
      // raising it: overwriting it with the module's own `false` would take the app off Google Play's
      // BLE filter with nothing in the build reporting it. An entry carrying no `android:required` is
      // left alone for the same reason — the attribute defaults to `true`.
      if (props.requireBluetoothLeHardware) {
        declared.$['android:required'] = 'true';
      }
    } else {
      features.push({
        $: {
          'android:name': BLUETOOTH_LE_FEATURE,
          'android:required': props.requireBluetoothLeHardware ? 'true' : 'false',
        },
      });
    }

    return config;
  });
