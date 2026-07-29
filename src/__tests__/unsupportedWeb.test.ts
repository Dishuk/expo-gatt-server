import { createServer, getBluetoothState, isSupported } from '../index';

jest.mock('../ExpoGattServerModule', () => ({ __esModule: true, default: null }));

// This package declares no web platform, so the web branch of the guard is reached by overriding
// the only value the module reads from expo-modules-core; the rest stays real since expo loads
// through it.
jest.mock('expo-modules-core', () => ({
  ...jest.requireActual('expo-modules-core'),
  Platform: { OS: 'web' },
}));

describe('the web guard', () => {
  it('reports the platform as unsupported', () => {
    expect(isSupported()).toBe(false);
  });

  it('explains that Web Bluetooth has no peripheral role rather than blaming the build', async () => {
    await expect(createServer([])).rejects.toThrow(/Not supported on web/);
    await expect(createServer([])).rejects.toThrow(/only the central role/);
    await expect(createServer([])).rejects.not.toThrow(/development build/);
  });

  it('still reports the Bluetooth state as unsupported rather than throwing', async () => {
    await expect(getBluetoothState()).resolves.toBe('unsupported');
  });
});
