import { createRunOncePlugin, type ConfigPlugin } from 'expo/config-plugins';

import { withGattServerAndroid } from './withGattServerAndroid';
import { withGattServerIos } from './withGattServerIos';

export type ExpoGattServerPluginProps = {
  /**
   * NSBluetoothAlwaysUsageDescription. String replaces config, false leaves untouched, undefined fills default if absent.
   * @platform ios
   */
  bluetoothAlwaysPermission?: string | false;
  /**
   * Add bluetooth-peripheral to UIBackgroundModes. Only adds, never removes—turning off requires prebuild --clean.
   * @platform ios
   */
  bluetoothPeripheralBackgroundMode?: boolean;
  /**
   * Declare android.hardware.bluetooth_le required; only adds, never relaxes existing declarations.
   * @platform android
   */
  requireBluetoothLeHardware?: boolean;
};

/**
 * Plugin props are untyped JSON that lands directly in plist/manifest. Wrong types succeed silently.
 */
function assertValidProps(props: ExpoGattServerPluginProps): void {
  if (props === null || typeof props !== 'object' || Array.isArray(props)) {
    throw new Error(
      `expo-gatt-server config plugin: expected an options object, received ${JSON.stringify(props)}.`,
    );
  }

  // Misspellings are silently ignored—every layer reads only keys it knows and ignores the rest.
  const recognised = [
    'bluetoothAlwaysPermission',
    'bluetoothPeripheralBackgroundMode',
    'requireBluetoothLeHardware',
  ];
  for (const key of Object.keys(props)) {
    if (!recognised.includes(key)) {
      throw new Error(
        `expo-gatt-server config plugin: unknown option ${JSON.stringify(key)}. Recognised options ` +
          `are ${recognised.map((option) => JSON.stringify(option)).join(', ')}. An unrecognised one ` +
          'is read by nothing, so it would be silently ignored rather than applied.',
      );
    }
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
 * Exported unwrapped for testing; default export wraps this in createRunOncePlugin.
 */
export const withGattServer: ConfigPlugin<ExpoGattServerPluginProps> = (config, props = {}) => {
  assertValidProps(props);
  config = withGattServerIos(config, props);
  return withGattServerAndroid(config, props);
};

// Hardcoded to avoid requiring @types/node (package.json is outside plugin rootDir).
export default createRunOncePlugin(withGattServer, 'expo-gatt-server');
