import {
  createServer,
  sendNotification,
  startAdvertising,
  updateCharacteristicValue,
} from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

/** The Bluetooth Base UUID as an integer, so expansion can be checked against the specification's own arithmetic. */
const BLUETOOTH_BASE_UUID = 0x0000000000001000800000805f9b34fbn;

/** `short_value * 2^96 + Bluetooth_Base_UUID` — Core Spec Vol 3, Part B, §2.5.1. */
function expandBySpecification(shortValue: bigint): string {
  const hex = ((shortValue << 96n) + BLUETOOTH_BASE_UUID).toString(16).padStart(32, '0');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join('-');
}

function serviceWith(uuid: string) {
  return [{ uuid, characteristics: [] }];
}

async function publishedServiceUuid(uuid: string): Promise<string> {
  await createServer(serviceWith(uuid));
  return nativeModuleMock.createServer.mock.calls[0][0][0].uuid;
}

const aliases16: [string, bigint][] = [
  ['180D', 0x180dn],
  ['180d', 0x180dn],
  ['0000', 0x0000n],
  ['ffff', 0xffffn],
  ['FFFF', 0xffffn],
];

const aliases32: [string, bigint][] = [
  ['0000180D', 0x180dn],
  ['00000000', 0x0n],
  ['ffffffff', 0xffffffffn],
  ['DEADBEEF', 0xdeadbeefn],
];

describe('short-form UUID expansion', () => {
  it.each(aliases16)('expands the 16-bit alias %s by the specification', async (alias, value) => {
    await expect(publishedServiceUuid(alias)).resolves.toBe(expandBySpecification(value));
  });

  it.each(aliases32)('expands the 32-bit alias %s by the specification', async (alias, value) => {
    await expect(publishedServiceUuid(alias)).resolves.toBe(expandBySpecification(value));
  });

  it('places the alias in the leading 32 bits and leaves the base UUID groups intact', async () => {
    await expect(publishedServiceUuid('180D')).resolves.toBe(
      '0000180d-0000-1000-8000-00805f9b34fb',
    );
  });

  it('expands the all-zero alias to the base UUID itself', async () => {
    await expect(publishedServiceUuid('0000')).resolves.toBe(
      '00000000-0000-1000-8000-00805f9b34fb',
    );
  });

  it('expands the largest 16-bit alias without disturbing the 32-bit field', async () => {
    await expect(publishedServiceUuid('FFFF')).resolves.toBe(
      '0000ffff-0000-1000-8000-00805f9b34fb',
    );
  });

  it('lowercases a 128-bit UUID without otherwise altering it', async () => {
    await expect(publishedServiceUuid('0000180D-0000-1000-8000-00805F9B34FB')).resolves.toBe(
      '0000180d-0000-1000-8000-00805f9b34fb',
    );
  });
});

const malformedUuids: [string, unknown][] = [
  ['three hex digits', '180'],
  ['five hex digits', '180da'],
  ['seven hex digits', '0000180'],
  ['nine hex digits', '0000180da'],
  ['a non-hex digit', '180G'],
  ['an empty string', ''],
  ['a 128-bit form without hyphens', '0000180d00001000800000805f9b34fb'],
  ['a 128-bit form with the wrong group lengths', '0000180d-000-1000-8000-00805f9b34fb'],
  ['a 128-bit form with a truncated final group', '0000180d-0000-1000-8000-00805f9b34f'],
  ['a 128-bit form with an over-long final group', '0000180d-0000-1000-8000-00805f9b34fbb'],
  ['surrounding whitespace', ' 180d '],
  ['a 0x prefix', '0x180d'],
  ['a number', 0x180d],
  ['null', null],
  ['undefined', undefined],
  ['an object', {}],
  ['an array', ['180d']],
];

describe('UUID validation', () => {
  it.each(malformedUuids)('rejects %s', async (_label, uuid) => {
    await expect(createServer(serviceWith(uuid as string))).rejects.toThrow(/Invalid service UUID/);
  });

  it('never reaches the native module with an invalid UUID', async () => {
    await expect(createServer(serviceWith('nope'))).rejects.toThrow();
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it('names the field and the offending value', async () => {
    await expect(
      createServer([
        { uuid: '180d', characteristics: [{ uuid: 'zzzz', properties: [], permissions: [] }] },
      ]),
    ).rejects.toThrow(/Invalid characteristic UUID "zzzz"/);
  });
});

describe('normalisation at every UUID entry point', () => {
  const expandedService = '0000180d-0000-1000-8000-00805f9b34fb';
  const expandedCharacteristic = '00002a37-0000-1000-8000-00805f9b34fb';

  it('normalises service, characteristic and descriptor UUIDs', async () => {
    await createServer([
      {
        uuid: '180D',
        characteristics: [
          {
            uuid: '2A37',
            properties: ['read'],
            permissions: ['readable'],
            descriptors: [{ uuid: '2901', value: [1] }],
          },
        ],
      },
    ]);

    const [services] = nativeModuleMock.createServer.mock.calls[0];
    expect(services[0].uuid).toBe(expandedService);
    expect(services[0].characteristics[0].uuid).toBe(expandedCharacteristic);
    expect(services[0].characteristics[0].descriptors[0].uuid).toBe(
      '00002901-0000-1000-8000-00805f9b34fb',
    );
  });

  it('normalises advertised service UUIDs and service data UUIDs', async () => {
    await startAdvertising({
      serviceUuids: ['180D'],
      serviceData: [{ uuid: '180D', data: [1, 2] }],
    });

    const [config] = nativeModuleMock.startAdvertising.mock.calls[0];
    expect(config.serviceUuids).toEqual([expandedService]);
    expect(config.serviceData[0].uuid).toBe(expandedService);
  });

  it('normalises the UUIDs sendNotification is given', async () => {
    await sendNotification('AA:BB', '180D', '2A37', [1]);

    expect(nativeModuleMock.sendNotification).toHaveBeenCalledWith(
      'AA:BB',
      expandedService,
      expandedCharacteristic,
      [1],
      false,
      true,
    );
  });

  it('normalises the UUIDs updateCharacteristicValue is given', async () => {
    await updateCharacteristicValue('180D', '2A37', [1]);

    expect(nativeModuleMock.updateCharacteristicValue).toHaveBeenCalledWith(
      expandedService,
      expandedCharacteristic,
      [1],
    );
  });

  it("leaves the caller's own configuration object untouched", async () => {
    const services = [
      {
        uuid: '180D',
        characteristics: [
          {
            uuid: '2A37',
            properties: [],
            permissions: [],
            descriptors: [{ uuid: '2901', value: [1] }],
          },
        ],
      },
    ];

    await createServer(services);

    expect(services[0].uuid).toBe('180D');
    expect(services[0].characteristics[0].uuid).toBe('2A37');
    expect(services[0].characteristics[0].descriptors[0].uuid).toBe('2901');
  });
});
