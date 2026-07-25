import {
  GATT_SUCCESS,
  createServer,
  sendNotification,
  sendResponse,
  startAdvertising,
  updateCharacteristicValue,
} from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';
const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';

/** Every entry point that takes raw bytes, so the check cannot be lost from one of them unnoticed. */
const byteConsumers: [string, (value: unknown) => Promise<unknown>][] = [
  [
    'a characteristic value',
    (value) =>
      createServer([
        {
          uuid: SERVICE,
          characteristics: [
            {
              uuid: CHARACTERISTIC,
              properties: ['read'],
              permissions: ['readable'],
              value: value as number[],
            },
          ],
        },
      ]),
  ],
  [
    'a descriptor value',
    (value) =>
      createServer([
        {
          uuid: SERVICE,
          characteristics: [
            {
              uuid: CHARACTERISTIC,
              properties: ['read'],
              permissions: ['readable'],
              descriptors: [{ uuid: '2901', value: value as number[] }],
            },
          ],
        },
      ]),
  ],
  [
    'a notification value',
    (value) => sendNotification('AA:BB', SERVICE, CHARACTERISTIC, value as number[]),
  ],
  ['a response value', (value) => sendResponse('AA:BB', 1, GATT_SUCCESS, 0, value as number[])],
  [
    'an updated characteristic value',
    (value) => updateCharacteristicValue(SERVICE, CHARACTERISTIC, value as number[]),
  ],
  [
    'manufacturer data',
    (value) =>
      startAdvertising({ manufacturerData: [{ companyId: 0xffff, data: value as number[] }] }),
  ],
  [
    'service data',
    (value) => startAdvertising({ serviceData: [{ uuid: '180d', data: value as number[] }] }),
  ],
];

const invalidBytes: [string, unknown][] = [
  ['a value above 255', [256]],
  ['a value far above 255', [1000]],
  ['a negative value', [-1]],
  ['negative zero-adjacent values', [0, -128]],
  ['a non-integer', [1.5]],
  ['NaN', [NaN]],
  ['Infinity', [Infinity]],
  ['a numeric string', ['1']],
  ['null inside the array', [null]],
  ['undefined inside the array', [undefined]],
];

const nonArrays: [string, unknown][] = [
  ['a string', 'abc'],
  ['a number', 1],
  ['null', null],
  ['an object', { 0: 1 }],
  ['a Uint8Array', new Uint8Array([1, 2])],
];

describe.each(byteConsumers)('byte validation for %s', (_label, call) => {
  it.each(invalidBytes)('rejects %s', async (_case, value) => {
    await expect(call(value)).rejects.toThrow(/Every element must be an integer between 0 and 255/);
  });

  it.each(nonArrays)('rejects %s', async (_case, value) => {
    await expect(call(value)).rejects.toThrow(/Expected an array of byte values/);
  });

  it('accepts the 0 and 255 boundaries', async () => {
    await expect(call([0, 255])).resolves.toBeUndefined();
  });

  it('accepts an empty array', async () => {
    await expect(call([])).resolves.toBeUndefined();
  });
});

describe('byte validation reporting', () => {
  it('reports the index of the first offending byte', async () => {
    await expect(sendNotification('AA:BB', SERVICE, CHARACTERISTIC, [0, 1, 300])).rejects.toThrow(
      /Invalid notification byte 300 at index 2/,
    );
  });

  it('names the field the bytes belong to', async () => {
    await expect(sendResponse('AA:BB', 1, GATT_SUCCESS, 0, [-1])).rejects.toThrow(
      /Invalid response byte -1 at index 0/,
    );
  });

  it('never reaches the native module with invalid bytes', async () => {
    await expect(sendNotification('AA:BB', SERVICE, CHARACTERISTIC, [256])).rejects.toThrow();
    expect(nativeModuleMock.sendNotification).not.toHaveBeenCalled();
  });
});
