import { startAdvertising } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

// The one value the module reads from `expo-modules-core`, overridden per test below. The rest has to
// stay real: `expo` itself loads through it.
jest.mock('expo-modules-core', () => ({
  ...jest.requireActual('expo-modules-core'),
  Platform: { OS: 'android' },
}));

const { Platform } = jest.requireMock('expo-modules-core') as { Platform: { OS: string } };

/**
 * The places the shared layer behaves differently per platform.
 *
 * Mocked rather than reached by running the whole suite twice under the preset's per-platform Jest
 * projects: that cost several hundred duplicated assertions to arrive at these few conditionals, and
 * still only exercised one side of each per run. Here both sides are asserted together, so a branch
 * that stops firing — or starts firing everywhere — fails.
 */
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

// The other `Platform.OS` branch — the message for a binary that simply does not contain the module —
// is asserted in `unsupported.test.ts`, where the native module is already mocked away, and its web
// counterpart in `unsupportedWeb.test.ts`.
