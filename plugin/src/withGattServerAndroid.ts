import { AndroidConfig, withAndroidManifest, type ConfigPlugin } from 'expo/config-plugins';

import type { ExpoGattServerPluginProps } from './index';

const BLUETOOTH_LE_FEATURE = 'android.hardware.bluetooth_le';

type AndroidManifestRoot = AndroidConfig.Manifest.AndroidManifest['manifest'];

/**
 * Declares `android.hardware.bluetooth_le` on the app manifest, raising an existing declaration but
 * never relaxing one.
 *
 * Separated from the mod so the rule can be exercised without Expo's mod pipeline: a declaration this
 * quietly overwrote would take the app off Google Play's BLE filter with nothing in the build saying so,
 * which is invisible until a release reaches devices that should not have been offered it.
 */
export function applyBluetoothLeFeature(manifest: AndroidManifestRoot, required: boolean): void {
  // `xml2js` parses an attribute-less `<uses-feature />` to the string `""` — so `feature.$` threw a
  // `TypeError` naming neither this plugin nor the manifest, mid-prebuild. A lone node is normalised
  // into the list rather than replaced, so a declaration written that way is still honoured.
  const existing = manifest['uses-feature'];
  const features = Array.isArray(existing) ? existing : existing == null ? [] : [existing];
  manifest['uses-feature'] = features;
  const declared = features.find(
    (feature) => feature?.$?.['android:name'] === BLUETOOTH_LE_FEATURE,
  );
  if (declared) {
    // A declaration another plugin or the app config already made is left alone unless this one is
    // raising it: overwriting it with the module's own `false` would take the app off Google Play's
    // BLE filter with nothing in the build reporting it. An entry carrying no `android:required` is
    // left alone for the same reason — the attribute defaults to `true`.
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
