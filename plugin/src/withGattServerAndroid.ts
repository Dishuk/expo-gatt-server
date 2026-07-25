import { withAndroidManifest, type ConfigPlugin } from 'expo/config-plugins';

import type { ExpoGattServerPluginProps } from './index';

const BLUETOOTH_LE_FEATURE = 'android.hardware.bluetooth_le';

export const withGattServerAndroid: ConfigPlugin<ExpoGattServerPluginProps> = (config, props) =>
  withAndroidManifest(config, (config) => {
    const { manifest } = config.modResults;
    // The manifest merger ORs `android:required` across manifests, so the app's own entry is the only
    // place a `true` can override the `false` this module declares for its consumers' benefit.
    const required = props.requireBluetoothLeHardware ? 'true' : 'false';

    const features = (manifest['uses-feature'] ??= []);
    const declared = features.find(
      (feature) => feature.$['android:name'] === BLUETOOTH_LE_FEATURE,
    );
    if (declared) {
      declared.$['android:required'] = required;
    } else {
      features.push({
        $: { 'android:name': BLUETOOTH_LE_FEATURE, 'android:required': required },
      });
    }

    return config;
  });
