import {
  GATT_SUCCESS,
  MAX_ATTRIBUTE_VALUE_LENGTH,
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

// Byte validation is one predicate (`assertValidBytes`) checked from many call sites. This file tests
// the predicate once (below) and, separately, that every call site consults it (second `describe`).
const badValueUnderTest = [256];

describe('what counts as a byte', () => {
  // Checked through one entry point, since they all reach the same predicate.
  const reject = (value: unknown) =>
    sendNotification('AA:BB', SERVICE, CHARACTERISTIC, value as number[]);

  it.each([
    ['a value above 255', [256]],
    ['a negative value', [-1]],
    ['a non-integer', [1.5]],
    ['NaN', [NaN]],
    ['undefined inside the array', [undefined]],
  ])('rejects %s', async (_case, value) => {
    await expect(reject(value)).rejects.toThrow(
      /Every element must be an integer between 0 and 255/,
    );
  });

  // A different message: the fault is the container, not an element.
  it.each([
    ['a string', 'abc'],
    ['null', null],
    ['a Uint8Array', new Uint8Array([1, 2])],
  ])('rejects %s in place of the array', async (_case, value) => {
    await expect(reject(value)).rejects.toThrow(/Expected an array of byte values/);
  });

  it('accepts the 0 and 255 boundaries, and an empty array', async () => {
    await expect(reject([0, 255])).resolves.toBeUndefined();
    await expect(reject([])).resolves.toBeUndefined();
  });

  it('reports the offending byte, its index and the field it belongs to', async () => {
    await expect(sendNotification('AA:BB', SERVICE, CHARACTERISTIC, [0, 1, 300])).rejects.toThrow(
      /Invalid notification byte 300 at index 2/,
    );
    await expect(sendResponse('AA:BB', 1, GATT_SUCCESS, 0, [-1])).rejects.toThrow(
      /Invalid response byte -1 at index 0/,
    );
  });
});

describe('every entry point taking raw bytes consults the check', () => {
  const byteConsumers: [string, (value: number[]) => Promise<unknown>][] = [
    [
      'a characteristic value',
      (value) =>
        createServer([
          {
            uuid: SERVICE,
            characteristics: [
              { uuid: CHARACTERISTIC, properties: ['read'], permissions: ['readable'], value },
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
                descriptors: [{ uuid: '2901', value }],
              },
            ],
          },
        ]),
    ],
    ['a notification value', (value) => sendNotification('AA:BB', SERVICE, CHARACTERISTIC, value)],
    ['a response value', (value) => sendResponse('AA:BB', 1, GATT_SUCCESS, 0, value)],
    [
      'an updated characteristic value',
      (value) => updateCharacteristicValue(SERVICE, CHARACTERISTIC, value),
    ],
    [
      'manufacturer data',
      (value) => startAdvertising({ manufacturerData: [{ companyId: 0xffff, data: value }] }),
    ],
    ['service data', (value) => startAdvertising({ serviceData: [{ uuid: '180d', data: value }] })],
  ];

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it.each(byteConsumers)('%s is validated', async (_label, call) => {
    await expect(call(badValueUnderTest)).rejects.toThrow(
      /Every element must be an integer between 0 and 255/,
    );
  });

  // Validation that runs after the call has already been made is no validation at all. Checked via one
  // representative consumer per native call group.
  it.each(
    byteConsumers.filter(([label]) =>
      ['a characteristic value', 'a notification value', 'manufacturer data'].includes(label),
    ),
  )('%s reaches no native call when invalid', async (_label, call) => {
    await expect(call(badValueUnderTest)).rejects.toThrow();

    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
    expect(nativeModuleMock.sendNotification).not.toHaveBeenCalled();
    expect(nativeModuleMock.sendResponse).not.toHaveBeenCalled();
    expect(nativeModuleMock.updateCharacteristicValue).not.toHaveBeenCalled();
    expect(nativeModuleMock.startAdvertising).not.toHaveBeenCalled();
  });

  // An application can publish an attribute longer than the 512-octet spec limit through its own
  // configuration or `updateCharacteristicValue`, so the bound is enforced on the stored value itself.
  describe('the attribute value length limit', () => {
    const storesAnAttributeValue = byteConsumers.filter(([label]) =>
      ['a characteristic value', 'a descriptor value', 'an updated characteristic value'].includes(
        label,
      ),
    );

    it.each(storesAnAttributeValue)('%s accepts exactly 512 octets', async (_label, call) => {
      await expect(call(new Array(MAX_ATTRIBUTE_VALUE_LENGTH).fill(0))).resolves.toBeUndefined();
    });

    it.each(storesAnAttributeValue)('%s rejects 513 octets', async (_label, call) => {
      await expect(call(new Array(MAX_ATTRIBUTE_VALUE_LENGTH + 1).fill(0))).rejects.toThrow(
        /may hold at most 512 octets/,
      );
    });

    // Deliberately unbounded: a response continues a Read Blob rather than being an attribute, and a
    // notification is bounded by the link's MTU instead, which only the native side knows.
    it.each(
      byteConsumers.filter(([label]) =>
        ['a notification value', 'a response value'].includes(label),
      ),
    )('%s is not bounded by the attribute limit in JavaScript', async (_label, call) => {
      await expect(
        call(new Array(MAX_ATTRIBUTE_VALUE_LENGTH + 1).fill(0)),
      ).resolves.toBeUndefined();
    });
  });
});
