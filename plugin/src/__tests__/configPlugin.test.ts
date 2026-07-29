import { withGattServer, type ExpoGattServerPluginProps } from '../index';
import { applyBluetoothLeFeature } from '../withGattServerAndroid';
import { applyBluetoothInfoPlist, type BluetoothInfoPlist } from '../withGattServerIos';

// Pins the Android BLE-filter and iOS usage-description behavior, neither of which surfaces until a
// build reaches a device.

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
  const declared = root['uses-feature'];
  // Same normalisation the plugin does.
  const features = Array.isArray(declared) ? declared : declared == null ? [] : [declared];
  return features.filter((entry) => entry?.$?.['android:name'] === BLE);
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

  // Never relaxes an existing requirement.
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

  it('never declares the feature twice', () => {
    const root = manifest();

    applyBluetoothLeFeature(root, false);
    applyBluetoothLeFeature(root, true);

    expect(bleEntries(root)).toHaveLength(1);
  });

  // xml2js parses `<uses-feature />` (no attributes) to an empty string entry.
  it('declares the feature alongside an attribute-less node rather than throwing', () => {
    const root = manifest(['' as unknown as Feature]);

    expect(() => applyBluetoothLeFeature(root, true)).not.toThrow();
    expect(bleEntries(root)).toHaveLength(1);
  });

  it('normalises a lone node into the list rather than dropping it', () => {
    const camera = { $: { 'android:name': 'android.hardware.camera' } } as unknown as Feature;
    const root = manifest([]);
    root['uses-feature'] = camera as unknown as Feature[];

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

  // An existing entry wins; the plugin never overwrites an app's own wording.
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

  // Never added unless the app asks for it; it's App Store reviewable.
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

  // Spreading a string produces a per-character array; must normalize to a list first.
  it('normalises a string into a list rather than spreading it per character', () => {
    const plist: BluetoothInfoPlist = { UIBackgroundModes: 'audio' as unknown as string[] };

    applyBluetoothInfoPlist(plist, { bluetoothPeripheralBackgroundMode: true });

    expect(plist.UIBackgroundModes).toEqual(['audio', 'bluetooth-peripheral']);
  });

  it('promotes a string that already names the mode into a list', () => {
    const plist: BluetoothInfoPlist = {
      UIBackgroundModes: 'bluetooth-peripheral' as unknown as string[],
    };

    applyBluetoothInfoPlist(plist, { bluetoothPeripheralBackgroundMode: true });

    expect(plist.UIBackgroundModes).toEqual(['bluetooth-peripheral']);
  });
});

// Plugin props come from untyped app.json; wrong types must be rejected explicitly here since
// nothing downstream catches them.
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

  // Defaulting only covers `undefined`, so an explicit null must still be rejected explicitly.
  it('rejects null with a message naming the plugin', () => {
    expect(() => apply(null)).toThrow(/expo-gatt-server config plugin: expected an options object/);
  });

  // An unrecognised key (typo or otherwise) is silently ignored by every layer unless caught here.
  it('rejects an option no layer below would read', () => {
    expect(() => apply({ requireBluetoothLEHardware: true })).toThrow(
      /unknown option "requireBluetoothLEHardware"/,
    );
    expect(() => apply({ nonsense: 1 })).toThrow(/unknown option "nonsense"/);
  });
});

// Exercises the mods the plugin registers (not the helpers directly), so a swapped iOS/Android mod
// or a wrong default would fail here even though every test above stays green.
describe('the mods the plugin registers', () => {
  // Built fresh per run: mods mutate and chain onto the config they are given, so a shared object
  // would run every previous test's props again on top of this one's.
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

  it('declares the BLE feature as not required through the Android mod by default', async () => {
    const root = await runAndroidMod({}, manifest());
    expect(soleBleRequirement(root)).toBe('false');
  });

  it('raises the BLE feature through the Android mod when asked', async () => {
    const root = await runAndroidMod({ requireBluetoothLeHardware: true }, manifest());
    expect(soleBleRequirement(root)).toBe('true');
  });

  it('leaves the Android manifest alone from the iOS props and vice versa', async () => {
    const root = await runAndroidMod({ bluetoothPeripheralBackgroundMode: true }, manifest());
    expect(soleBleRequirement(root)).toBe('false');

    const plist = await runIosMod({ requireBluetoothLeHardware: true }, {});
    expect(plist.UIBackgroundModes).toBeUndefined();
  });
});
