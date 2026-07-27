import { createServer, startAdvertising, type GattServiceConfig } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';
const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';

/**
 * A value of the wrong *shape* reaches the caller as the module's own error rather than as a
 * `TypeError` raised from inside it — `.map is not a function` names neither the field nor the fix.
 */
describe('a container of the wrong shape', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  const service = (extra: Record<string, unknown>): GattServiceConfig[] =>
    [{ uuid: SERVICE, characteristics: [], ...extra }] as unknown as GattServiceConfig[];

  it.each([
    ['service characteristics', service({ characteristics: {} })],
    ['service characteristics', service({ characteristics: 'none' })],
  ])('rejects %s that is not an array', async (field, services) => {
    await expect(createServer(services)).rejects.toThrow(new RegExp(`Invalid ${field}`));
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it('rejects descriptors that are not an array', async () => {
    const services = [
      {
        uuid: SERVICE,
        characteristics: [
          {
            uuid: CHARACTERISTIC,
            properties: ['read'],
            permissions: ['readable'],
            descriptors: {},
          },
        ],
      },
    ] as unknown as GattServiceConfig[];
    await expect(createServer(services)).rejects.toThrow(/Invalid characteristic descriptors/);
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it.each(['serviceUuids', 'manufacturerData', 'serviceData'])(
    'rejects advertising %s that is not an array',
    async (key) => {
      await expect(startAdvertising({ [key]: 'not-a-list' } as never)).rejects.toThrow(
        new RegExp(`Invalid advertising ${key}`),
      );
      expect(nativeModuleMock.startAdvertising).not.toHaveBeenCalled();
    },
  );

  it.each([null, 42, 'options', []])(
    'rejects an advertising config of %p by name rather than as a TypeError',
    async (config) => {
      await expect(startAdvertising(config as never)).rejects.toThrow(
        /Invalid advertising options/,
      );
      expect(nativeModuleMock.startAdvertising).not.toHaveBeenCalled();
    },
  );

  it.each([null, 42, 'options', []])(
    'rejects createServer options of %p by name rather than as a TypeError',
    async (options) => {
      await expect(createServer([], options as never)).rejects.toThrow(
        /Invalid createServer options/,
      );
      expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
    },
  );

  /** `Platform.OS === 'android' ? { … } : null` is how the block comes to be null. */
  it('reads a null android block as absent', async () => {
    await expect(
      startAdvertising({ localName: 'x', android: null as never }),
    ).resolves.toBeUndefined();
    expect(nativeModuleMock.startAdvertising).toHaveBeenCalledTimes(1);
  });

  it('still rejects an android block of the wrong type', async () => {
    await expect(startAdvertising({ android: 42 as never })).rejects.toThrow(
      /Invalid advertising android options/,
    );
    expect(nativeModuleMock.startAdvertising).not.toHaveBeenCalled();
  });
});
