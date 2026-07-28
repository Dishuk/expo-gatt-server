import { AndroidConfig, withAndroidManifest, type ConfigPlugin } from 'expo/config-plugins';

import type { ExpoGattServerPluginProps } from './index';

const BLUETOOTH_LE_FEATURE = 'android.hardware.bluetooth_le';

type AndroidManifestRoot = AndroidConfig.Manifest.AndroidManifest['manifest'];

/**
 * Declare bluetooth_le feature, raising but never relaxing existing declarations.
 */
export function applyBluetoothLeFeature(manifest: AndroidManifestRoot, required: boolean): void {
  // xml2js parses attribute-less tags to ""; normalize to array to preserve lone nodes.
  const existing = manifest['uses-feature'];
  const features = Array.isArray(existing) ? existing : existing == null ? [] : [existing];
  manifest['uses-feature'] = features;
  const declared = features.find(
    (feature) => feature?.$?.['android:name'] === BLUETOOTH_LE_FEATURE,
  );
  if (declared) {
    // Existing declarations left alone unless raising. Attribute defaults to true if absent.
    if (required) {
      declared.$['android:required'] = 'true';
    }
    return;
  }
  features.push({
    $: {
      'android:name': BLUETOOTH_LE_FEATURE,
      'android:required': required ? 'true' : 'false',
    },
  });
}

export const withGattServerAndroid: ConfigPlugin<ExpoGattServerPluginProps> = (config, props) =>
  withAndroidManifest(config, (config) => {
    applyBluetoothLeFeature(config.modResults.manifest, props.requireBluetoothLeHardware ?? false);
    return config;
  });
