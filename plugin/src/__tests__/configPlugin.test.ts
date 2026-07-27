import { withGattServer, type ExpoGattServerPluginProps } from '../index';
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

/**
 * Props arrive from `app.json`, which is untyped JSON at prebuild time — so the declared types
 * constrain nobody, and a wrong type does not fail. It succeeds into something quietly wrong: a
 * non-string usage description is not a valid plist string, so iOS reads the key as absent and
 * terminates the app on first Bluetooth use; `"false"` is a truthy string, so it filters the app off
 * Google Play. Both used to prebuild clean.
 */
describe('plugin prop validation', () => {
  const config = { name: 'harness', slug: 'harness' } as never;
  const apply = (props: unknown) => withGattServer(config, props as ExpoGattServerPluginProps);

  it('accepts an omitted options object', () => {
    expect(() => withGattServer(config, undefined as never)).not.toThrow();
  });

  it('accepts well-formed props', () => {
    expect(() =>
      apply({
        bluetoothAlwaysPermission: 'We use Bluetooth to talk to nearby devices.',
        bluetoothPeripheralBackgroundMode: true,
        requireBluetoothLeHardware: false,
      }),
    ).not.toThrow();
  });

  it('accepts false for the usage description, which means "leave the key alone"', () => {
    expect(() => apply({ bluetoothAlwaysPermission: false })).not.toThrow();
  });

  it('rejects a non-string usage description', () => {
    expect(() => apply({ bluetoothAlwaysPermission: true })).toThrow(
      /bluetoothAlwaysPermission must be a string/,
    );
  });

  it('rejects an empty usage description, which iOS treats as missing', () => {
    expect(() => apply({ bluetoothAlwaysPermission: '' })).toThrow(/cannot be empty/);
  });

  it('rejects a string where a boolean is required, since "false" is truthy', () => {
    expect(() => apply({ requireBluetoothLeHardware: 'false' })).toThrow(
      /requireBluetoothLeHardware must be a boolean/,
    );
    expect(() => apply({ bluetoothPeripheralBackgroundMode: 'true' })).toThrow(
      /bluetoothPeripheralBackgroundMode must be a boolean/,
    );
  });

  /** `props = {}` defaults only for `undefined`, so an explicit null used to reach a property access. */
  it('rejects null with a message naming the plugin', () => {
    expect(() => apply(null)).toThrow(/expo-gatt-server config plugin: expected an options object/);
  });

  /**
   * A key no layer below reads is the same silent-drop failure `createServer` rejects an unknown option
   * for. `requireBluetoothLEHardware` is one capital away from the real name, so the app prebuilds clean
   * and ships `android:required="false"` — staying on Google Play for devices with no BLE radio.
   */
  it('rejects an option no layer below would read', () => {
    expect(() => apply({ requireBluetoothLEHardware: true })).toThrow(
      /unknown option "requireBluetoothLEHardware"/,
    );
    expect(() => apply({ nonsense: 1 })).toThrow(/unknown option "nonsense"/);
  });
});

/**
 * The helpers above are exercised directly, which leaves the two mods — the wiring that decides *what*
 * they are called with — asserted by nothing. Swapping the iOS and Android mods, or defaulting
 * `requireBluetoothLeHardware` to `true`, kept every other test in this file green while every app that
 * prebuilt got the opposite of what it asked for.
 */
describe('the mods the plugin registers', () => {
  // Built fresh per run: `withInfoPlist` and `withAndroidManifest` register by mutating the config they
  // are given and chaining onto whatever mod is already there, so a shared object would run every
  // previous test's props again on top of this one's.
  const base = () => ({ name: 'harness', slug: 'harness' }) as never;

  async function runIosMod(props: ExpoGattServerPluginProps, infoPlist: BluetoothInfoPlist) {
    const config = withGattServer(base(), props) as {
      mods?: {
        ios?: { infoPlist?: (config: unknown) => Promise<{ modResults: BluetoothInfoPlist }> };
      };
    };
    const mod = config.mods?.ios?.infoPlist;
    if (!mod) {
      throw new Error('the plugin registered no iOS infoPlist mod');
    }
    const result = await mod({ ...config, modResults: infoPlist, modRequest: {} });
    return result.modResults;
  }

  async function runAndroidMod(props: ExpoGattServerPluginProps, root: Manifest) {
    const config = withGattServer(base(), props) as {
      mods?: {
        android?: {
          manifest?: (config: unknown) => Promise<{ modResults: { manifest: Manifest } }>;
        };
      };
    };
    const mod = config.mods?.android?.manifest;
    if (!mod) {
      throw new Error('the plugin registered no Android manifest mod');
    }
    const result = await mod({
      ...config,
      modResults: { manifest: root },
      modRequest: {},
    });
    return result.modResults.manifest;
  }

  it('writes the usage description through the iOS mod', async () => {
    const plist = await runIosMod({}, {});
    expect(plist.NSBluetoothAlwaysUsageDescription).toEqual(expect.any(String));
    expect(plist.UIBackgroundModes).toBeUndefined();
  });

  it('adds the background mode through the iOS mod only when asked', async () => {
    const enabled = await runIosMod({ bluetoothPeripheralBackgroundMode: true }, {});
    expect(enabled.UIBackgroundModes).toEqual(['bluetooth-peripheral']);

    const disabled = await runIosMod({ bluetoothPeripheralBackgroundMode: false }, {});
    expect(disabled.UIBackgroundModes).toBeUndefined();
  });

  /** The default the module's own manifest carries, so a consumer is not filtered off Google Play. */
  it('declares the BLE feature as not required through the Android mod by default', async () => {
    const root = await runAndroidMod({}, manifest());
    expect(soleBleRequirement(root)).toBe('false');
  });

  it('raises the BLE feature through the Android mod when asked', async () => {
    const root = await runAndroidMod({ requireBluetoothLeHardware: true }, manifest());
    expect(soleBleRequirement(root)).toBe('true');
  });

  /** Each mod must reach its own platform's file, which a swap of the two would not survive. */
  it('leaves the Android manifest alone from the iOS props and vice versa', async () => {
    const root = await runAndroidMod({ bluetoothPeripheralBackgroundMode: true }, manifest());
    expect(soleBleRequirement(root)).toBe('false');

    const plist = await runIosMod({ requireBluetoothLeHardware: true }, {});
    expect(plist.UIBackgroundModes).toBeUndefined();
  });
});
