import {
  CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
  createServer,
  type CharacteristicPermission,
  type CharacteristicProperty,
  type GattCharacteristicConfig,
  type GattServiceConfig,
} from '../index';
import { nativeModuleMock, publishedServices } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';
const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';

const ALL_PROPERTIES: CharacteristicProperty[] = [
  'read',
  'write',
  'writeNoResponse',
  'notify',
  'indicate',
  'broadcast',
  'signedWrite',
  'extendedProperties',
];

const ALL_PERMISSIONS: CharacteristicPermission[] = [
  'readable',
  'writeable',
  'readEncrypted',
  'readEncryptedMitm',
  'writeEncrypted',
  'writeEncryptedMitm',
  'writeSigned',
  'writeSignedMitm',
];

function publish(characteristic: Partial<GattCharacteristicConfig>) {
  return createServer([
    {
      uuid: SERVICE,
      characteristics: [
        {
          uuid: CHARACTERISTIC,
          properties: ['read'],
          permissions: ['readable'],
          ...characteristic,
        },
      ],
    },
  ]);
}

describe('characteristic properties', () => {
  it('accepts every documented property name', async () => {
    await expect(publish({ properties: ALL_PROPERTIES })).resolves.toBeUndefined();
  });

  // Three mistake classes, not five spellings of one: names are case-sensitive, a name that reads like
  // the platform's own is not accepted, and an empty string is not a no-op.
  it.each([
    ['the wrong case', 'Read'],
    ['an Android constant name', 'PROPERTY_READ'],
    ['an empty string', ''],
  ])('rejects %s as a property', async (_label, property) => {
    await expect(publish({ properties: [property as CharacteristicProperty] })).rejects.toThrow(
      /Invalid characteristic property/,
    );
  });

  it('lists the accepted names in the error', async () => {
    await expect(publish({ properties: ['nope' as CharacteristicProperty] })).rejects.toThrow(
      /Expected one of "read", "write", "writeNoResponse", "notify", "indicate", "broadcast", "signedWrite", "extendedProperties"/,
    );
  });

  // A bare string is the plausible slip; `undefined` stands for the absent-value shapes.
  it.each([
    ['a bare string', 'read'],
    ['undefined', undefined],
  ])('rejects %s in place of the properties array', async (_label, properties) => {
    await expect(
      publish({ properties: properties as unknown as CharacteristicProperty[] }),
    ).rejects.toThrow(/Invalid characteristic property/);
  });
});

describe('characteristic permissions', () => {
  it('accepts every documented permission name', async () => {
    await expect(publish({ permissions: ALL_PERMISSIONS })).resolves.toBeUndefined();
  });

  // `writable` is the misspelling everyone makes, and `read` is a *property* name — accepting either
  // would publish an attribute one permission short of what was asked for.
  it.each([
    ['a misspelling', 'writable'],
    ['a property name', 'read'],
  ])('rejects %s as a permission', async (_label, permission) => {
    await expect(
      publish({ permissions: [permission as CharacteristicPermission] }),
    ).rejects.toThrow(/Invalid characteristic permission/);
  });

  it('rejects a bare string in place of the permissions array', async () => {
    await expect(
      publish({ permissions: 'readable' as unknown as CharacteristicPermission[] }),
    ).rejects.toThrow(/Invalid characteristic permission/);
  });
});

// Both platforms raise the security of the subscription itself from these combinations, so the
// configuration has to survive validation on either one for that to be reachable at all.
describe('encrypted subscriptions', () => {
  it.each([
    ['notify', 'readEncrypted'],
    ['indicate', 'writeEncrypted'],
  ])('accepts %s with %s', async (property, permission) => {
    nativeModuleMock.createServer.mockClear();
    await expect(
      publish({
        properties: [property as CharacteristicProperty],
        permissions: [permission as CharacteristicPermission],
      }),
    ).resolves.toBeUndefined();

    expect(nativeModuleMock.createServer).toHaveBeenCalledWith(
      [
        expect.objectContaining({
          uuid: SERVICE,
          characteristics: [
            expect.objectContaining({
              uuid: CHARACTERISTIC,
              properties: [property],
              permissions: [permission],
            }),
          ],
        }),
      ],
      expect.anything(),
    );
  });
});

describe('descriptors', () => {
  it('rejects a manually declared CCCD in its 128-bit form', async () => {
    await expect(
      publish({
        descriptors: [{ uuid: CLIENT_CHARACTERISTIC_CONFIGURATION_UUID, value: [0, 0] }],
      }),
    ).rejects.toThrow(/Client Characteristic Configuration descriptor/);
  });

  // Each spelling has to be normalised *before* the CCCD check, or a short form slips past it.
  it.each(['2902', '00002902', '00002902-0000-1000-8000-00805F9B34FB'])(
    'rejects a manually declared CCCD written as %s',
    async (uuid) => {
      await expect(publish({ descriptors: [{ uuid, value: [0, 0] }] })).rejects.toThrow(
        /Client Characteristic Configuration descriptor/,
      );
    },
  );

  it('accepts other descriptors', async () => {
    await expect(
      publish({ descriptors: [{ uuid: '2901', value: [0x41] }] }),
    ).resolves.toBeUndefined();
  });

  it('rejects an unrecognised descriptor permission', async () => {
    await expect(
      publish({
        descriptors: [
          { uuid: '2901', value: [0x41], permissions: ['writable' as CharacteristicPermission] },
        ],
      }),
    ).rejects.toThrow(/Invalid descriptor permission/);
  });

  it('accepts documented descriptor permissions', async () => {
    await expect(
      publish({ descriptors: [{ uuid: '2901', value: [0x41], permissions: ALL_PERMISSIONS }] }),
    ).resolves.toBeUndefined();
  });
});

describe('service configuration', () => {
  it.each(['primary', 'secondary'])('accepts the %s service type', async (type) => {
    await expect(
      createServer([
        { uuid: SERVICE, type: type as GattServiceConfig['type'], characteristics: [] },
      ]),
    ).resolves.toBeUndefined();
  });

  it.each(['Primary', 'included'])('rejects %s as a service type', async (type) => {
    await expect(
      createServer([
        { uuid: SERVICE, type: type as GattServiceConfig['type'], characteristics: [] },
      ]),
    ).rejects.toThrow(/Invalid service type/);
  });

  it('treats a missing characteristics array as empty rather than failing', async () => {
    await expect(createServer([{ uuid: SERVICE } as GattServiceConfig])).resolves.toBeUndefined();
    expect(publishedServices()[0].characteristics).toEqual([]);
  });

  it('treats a missing services array as empty rather than failing', async () => {
    await expect(
      createServer(undefined as unknown as GattServiceConfig[]),
    ).resolves.toBeUndefined();
    expect(nativeModuleMock.createServer).toHaveBeenCalledWith([], {});
  });

  it('never reaches the native module with an invalid characteristic', async () => {
    await expect(publish({ properties: ['nope' as CharacteristicProperty] })).rejects.toThrow();
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it('omits the descriptors key entirely when none were configured', async () => {
    await publish({});
    expect('descriptors' in publishedServices()[0].characteristics[0]).toBe(false);
  });
});

describe('duplicate UUIDs', () => {
  const OTHER_SERVICE = '0000180f-0000-1000-8000-00805f9b34fb';
  const OTHER_CHARACTERISTIC = '00002a19-0000-1000-8000-00805f9b34fb';

  const readable = (uuid: string): GattCharacteristicConfig => ({
    uuid,
    properties: ['read'],
    permissions: ['readable'],
  });

  it('rejects two services declaring the same UUID', async () => {
    await expect(
      createServer([
        { uuid: SERVICE, characteristics: [readable(CHARACTERISTIC)] },
        { uuid: SERVICE, characteristics: [readable(OTHER_CHARACTERISTIC)] },
      ]),
    ).rejects.toThrow(/Duplicate service UUID 0000180d-0000-1000-8000-00805f9b34fb/);
  });

  it.each([
    ['a 16-bit alias against its 128-bit expansion', '180d', SERVICE],
    ['a difference in case', SERVICE, SERVICE.toUpperCase()],
  ])('recognises %s as the same service UUID', async (_label, first, second) => {
    await expect(
      createServer([
        { uuid: first, characteristics: [] },
        { uuid: second, characteristics: [] },
      ]),
    ).rejects.toThrow(/Duplicate service UUID/);
  });

  it('rejects one service declaring the same characteristic UUID twice', async () => {
    await expect(
      createServer([
        {
          uuid: SERVICE,
          characteristics: [readable(CHARACTERISTIC), readable(CHARACTERISTIC)],
        },
      ]),
    ).rejects.toThrow(
      /Duplicate characteristic UUID 00002a37-0000-1000-8000-00805f9b34fb in service 0000180d-0000-1000-8000-00805f9b34fb/,
    );
  });

  it('recognises a short and a long characteristic spelling as the same UUID', async () => {
    await expect(
      createServer([
        { uuid: SERVICE, characteristics: [readable('2a37'), readable(CHARACTERISTIC)] },
      ]),
    ).rejects.toThrow(/Duplicate characteristic UUID/);
  });

  it('accepts the same characteristic UUID in two different services', async () => {
    await expect(
      createServer([
        { uuid: SERVICE, characteristics: [readable(CHARACTERISTIC)] },
        { uuid: OTHER_SERVICE, characteristics: [readable(CHARACTERISTIC)] },
      ]),
    ).resolves.toBeUndefined();
  });

  it('never reaches the native module with a duplicate UUID', async () => {
    await expect(
      createServer([
        { uuid: SERVICE, characteristics: [] },
        { uuid: SERVICE, characteristics: [] },
      ]),
    ).rejects.toThrow();
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });
});

/**
 * `delegate` was the only characteristic sub-object that reached the native layers unchecked, and both
 * of them read its flags with a `?: false` fallback — so anything that is not exactly `read` or `write`
 * holding a boolean published the characteristic as fully automatic. The listener never fired, reads
 * were answered from the cached value, and nothing reported a problem on either side.
 */
describe('delegate validation', () => {
  const withDelegate = (delegate: unknown) =>
    createServer([
      {
        uuid: SERVICE,
        characteristics: [
          {
            uuid: CHARACTERISTIC,
            properties: ['read'],
            permissions: ['readable'],
            delegate,
          } as never,
        ],
      },
    ]);

  it('accepts the two recognised flags', async () => {
    await expect(withDelegate({ read: true, write: false })).resolves.toBeUndefined();
  });

  it('accepts an omitted delegate', async () => {
    await expect(withDelegate(undefined)).resolves.toBeUndefined();
  });

  it('rejects a misspelled flag rather than publishing an automatic characteristic', async () => {
    await expect(withDelegate({ reed: true })).rejects.toThrow(/Unknown delegate option "reed"/);
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it('rejects a non-boolean flag', async () => {
    await expect(withDelegate({ read: 'yes' })).rejects.toThrow(/Invalid delegate\.read/);
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it('rejects a delegate that is not an object', async () => {
    await expect(withDelegate(true)).rejects.toThrow(/Invalid delegate/);
    await expect(withDelegate([])).rejects.toThrow(/Invalid delegate/);
  });
});
