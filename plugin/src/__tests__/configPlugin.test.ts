import { applyBluetoothLeFeature } from '../withGattServerAndroid';
import { applyBluetoothInfoPlist, type BluetoothInfoPlist } from '../withGattServerIos';

/**
 * The config plugin decides two things a consumer cannot see until a build reaches a device: whether
 * the app is filtered off Google Play on hardware without BLE, and whether iOS has the usage
 * description without which it terminates the app the moment it touches CoreBluetooth. Neither shows
 * up in the JavaScript, so both are pinned here.
 */

type Manifest = Parameters<typeof applyBluetoothLeFeature>[0];
type Feature = NonNullable<Manifest['uses-feature']>[number];

const BLE = 'android.hardware.bluetooth_le';

function manifest(features?: Feature[]): Manifest {
  const root: Record<string, unknown> = { $: {} };
  if (features) {
    root['uses-feature'] = features;
  }
  return root as unknown as Manifest;
}

function feature(required?: string): Feature {
  const attributes: Record<string, string> = { 'android:name': BLE };
  if (required !== undefined) {
    attributes['android:required'] = required;
  }
  return { $: attributes } as unknown as Feature;
}

function bleEntries(root: Manifest): Feature[] {
  return (root['uses-feature'] ?? []).filter((entry) => entry.$['android:name'] === BLE);
}

/** Asserts the feature is declared exactly once and reports how it is declared. */
function soleBleRequirement(root: Manifest): string | undefined {
  const entries = bleEntries(root);
  expect(entries).toHaveLength(1);
  const [entry] = entries;
  if (!entry) {
    throw new Error(`no ${BLE} entry`);
  }
  return entry.$['android:required'];
}

describe('applyBluetoothLeFeature', () => {
  it('declares the feature as not required by default', () => {
    const root = manifest();

    applyBluetoothLeFeature(root, false);

    expect(soleBleRequirement(root)).toBe('false');
  });

  it('declares the feature as required when the app asks for it', () => {
    const root = manifest();

    applyBluetoothLeFeature(root, true);

    expect(soleBleRequirement(root)).toBe('true');
  });

  /**
   * The whole point of the "only ever raises" rule: writing the module's own `false` over an app that
   * genuinely needs BLE would offer it on devices that cannot run it, with nothing in the build saying
   * so.
   */
  it('leaves an existing requirement alone rather than relaxing it', () => {
    const root = manifest([feature('true')]);

    applyBluetoothLeFeature(root, false);

    expect(soleBleRequirement(root)).toBe('true');
  });

  it('raises an existing declaration when the app asks for the requirement', () => {
    const root = manifest([feature('false')]);

    applyBluetoothLeFeature(root, true);

    expect(soleBleRequirement(root)).toBe('true');
  });

  /** `android:required` defaults to `true` when absent, so an entry without it is already the stricter. */
  it('leaves an entry carrying no requirement attribute alone', () => {
    const root = manifest([feature()]);

    applyBluetoothLeFeature(root, false);

    expect(soleBleRequirement(root)).toBeUndefined();
  });

  it('never declares the feature twice', () => {
    const root = manifest();

    applyBluetoothLeFeature(root, false);
    applyBluetoothLeFeature(root, true);

    expect(bleEntries(root)).toHaveLength(1);
  });

  it('leaves unrelated features untouched', () => {
    const camera = { $: { 'android:name': 'android.hardware.camera' } } as unknown as Feature;
    const root = manifest([camera]);

    applyBluetoothLeFeature(root, false);

    expect(root['uses-feature']).toContain(camera);
    expect(bleEntries(root)).toHaveLength(1);
  });
});

describe('applyBluetoothInfoPlist', () => {
  it('fills in a description when the app config has none', () => {
    const plist: BluetoothInfoPlist = {};

    applyBluetoothInfoPlist(plist, {});

    expect(plist.NSBluetoothAlwaysUsageDescription).toContain('Bluetooth');
  });

  /** An existing `ios.infoPlist` entry wins, so the plugin never overwrites an app's own wording. */
  it('keeps a description the app config already set', () => {
    const plist: BluetoothInfoPlist = { NSBluetoothAlwaysUsageDescription: 'Ours' };

    applyBluetoothInfoPlist(plist, {});

    expect(plist.NSBluetoothAlwaysUsageDescription).toBe('Ours');
  });

  it('replaces the description when one is given explicitly', () => {
    const plist: BluetoothInfoPlist = { NSBluetoothAlwaysUsageDescription: 'Ours' };

    applyBluetoothInfoPlist(plist, { bluetoothAlwaysPermission: 'Theirs' });

    expect(plist.NSBluetoothAlwaysUsageDescription).toBe('Theirs');
  });

  it('writes no description at all when the app opts out', () => {
    const plist: BluetoothInfoPlist = {};

    applyBluetoothInfoPlist(plist, { bluetoothAlwaysPermission: false });

    expect('NSBluetoothAlwaysUsageDescription' in plist).toBe(false);
  });

  it('leaves an existing description alone when the app opts out', () => {
    const plist: BluetoothInfoPlist = { NSBluetoothAlwaysUsageDescription: 'Ours' };

    applyBluetoothInfoPlist(plist, { bluetoothAlwaysPermission: false });

    expect(plist.NSBluetoothAlwaysUsageDescription).toBe('Ours');
  });

  /** The mode is App Store reviewable, so it is never added unless the app asked for it. */
  it('adds no background mode by default', () => {
    const plist: BluetoothInfoPlist = {};

    applyBluetoothInfoPlist(plist, {});

    expect(plist.UIBackgroundModes).toBeUndefined();
  });

  it('adds the peripheral background mode when asked, alongside existing modes', () => {
    const plist: BluetoothInfoPlist = { UIBackgroundModes: ['audio'] };

    applyBluetoothInfoPlist(plist, { bluetoothPeripheralBackgroundMode: true });

    expect(plist.UIBackgroundModes).toEqual(['audio', 'bluetooth-peripheral']);
  });

  it('does not add the background mode twice', () => {
    const plist: BluetoothInfoPlist = {};

    applyBluetoothInfoPlist(plist, { bluetoothPeripheralBackgroundMode: true });
    applyBluetoothInfoPlist(plist, { bluetoothPeripheralBackgroundMode: true });

    expect(plist.UIBackgroundModes).toEqual(['bluetooth-peripheral']);
  });
});
