import { startAdvertising } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

// The one value the module reads from expo-modules-core, overridden per test below.
jest.mock('expo-modules-core', () => ({
  ...jest.requireActual('expo-modules-core'),
  Platform: { OS: 'android' },
}));

const { Platform } = jest.requireMock('expo-modules-core') as { Platform: { OS: string } };

// Both sides of the platform branch are asserted here, mocked rather than run under separate
// per-platform Jest projects.
describe('the advertising options iOS cannot honour', () => {
  let warn: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warn.mockRestore();
    Platform.OS = 'android';
  });

  // Warned about rather than rejected, because these only tune the radio: failing the call would force
  // every cross-platform caller to branch on the platform just to set Android's battery behaviour.
  it('warns on iOS, naming each option CoreBluetooth has nowhere to put', async () => {
    Platform.OS = 'ios';

    await startAdvertising({ mode: 'balanced', txPowerLevel: 'high', includeTxPowerLevel: true });

    expect(warn).toHaveBeenCalledWith(
      expect.stringContaining('iOS ignores mode, txPowerLevel, includeTxPowerLevel'),
    );
  });

  it('names only the options actually supplied', async () => {
    Platform.OS = 'ios';

    await startAdvertising({ txPowerLevel: 'high' });

    expect(warn).toHaveBeenCalledWith(expect.stringContaining('iOS ignores txPowerLevel'));
    expect(warn).not.toHaveBeenCalledWith(expect.stringContaining('mode'));
  });

  it('stays silent on iOS when none of them were supplied', async () => {
    Platform.OS = 'ios';

    await startAdvertising({ localName: 'Device' });

    expect(warn).not.toHaveBeenCalled();
  });

  // Android honours all three, so a warning there would be telling the caller to stop doing something
  // that works.
  it('stays silent on Android, where the options are honoured', async () => {
    Platform.OS = 'android';

    await startAdvertising({ mode: 'balanced', txPowerLevel: 'high', includeTxPowerLevel: true });

    expect(warn).not.toHaveBeenCalled();
  });

  // The warning is advisory: the advertisement still has to go out, with the options passed through for
  // the platform that can use them.
  it('advertises anyway, whichever platform it is', async () => {
    Platform.OS = 'ios';
    await startAdvertising({ mode: 'balanced' });

    expect(nativeModuleMock.startAdvertising).toHaveBeenCalledWith(
      expect.objectContaining({ mode: 'balanced' }),
    );
  });
});

// The other Platform.OS branch (missing-module message) is asserted in unsupported.test.ts and its
// web counterpart in unsupportedWeb.test.ts.
