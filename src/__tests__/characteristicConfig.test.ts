import {
  createServer,
  startAdvertising,
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

  // Property names are case-sensitive; an empty string is not treated as a no-op.
  it.each([
    ['the wrong case', 'Read'],
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

  it('rejects a bare string in place of the properties array', async () => {
    await expect(
      publish({ properties: 'read' as unknown as CharacteristicProperty[] }),
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

  it('rejects a missing characteristics array', async () => {
    await expect(createServer([{ uuid: SERVICE } as GattServiceConfig])).rejects.toThrow(
      /Invalid service characteristics/,
    );
  });

  it('accepts an explicitly empty characteristics array', async () => {
    await expect(createServer([{ uuid: SERVICE, characteristics: [] }])).resolves.toBeUndefined();
    expect(publishedServices()[0].characteristics).toEqual([]);
  });

  // An empty database (advertise-only) must be distinguished from a missing `services` argument.
  it('publishes an explicitly empty database', async () => {
    await expect(createServer([])).resolves.toBeUndefined();
    expect(nativeModuleMock.createServer).toHaveBeenCalledWith([], {});
  });

  it.each([
    ['undefined', undefined],
    ['a single service object', { uuid: SERVICE, characteristics: [] }],
  ])('rejects %s in place of a services array', async (_label, services) => {
    await expect(createServer(services as unknown as GattServiceConfig[])).rejects.toThrow(
      /Invalid services/,
    );
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
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

  // Two User Description descriptors on one `CBMutableCharacteristic` raise
  // `NSInternalInconsistencyException` on iOS, which Swift cannot catch — this must be rejected here.
  const described = (uuid: string) => ({ uuid, value: [0x41] });

  it.each([
    ['the User Description descriptor', '2901'],
    ['a vendor descriptor', '0000fe01-0000-1000-8000-00805f9b34fb'],
  ])('rejects %s declared twice on one characteristic', async (_label, uuid) => {
    await expect(
      createServer([
        {
          uuid: SERVICE,
          characteristics: [
            { ...readable(CHARACTERISTIC), descriptors: [described(uuid), described(uuid)] },
          ],
        },
      ]),
    ).rejects.toThrow(/Duplicate descriptor UUID/);
    expect(nativeModuleMock.createServer).not.toHaveBeenCalled();
  });

  it('recognises a short and a long descriptor spelling as the same UUID', async () => {
    await expect(
      createServer([
        {
          uuid: SERVICE,
          characteristics: [
            {
              ...readable(CHARACTERISTIC),
              descriptors: [described('2901'), described('00002901-0000-1000-8000-00805f9b34fb')],
            },
          ],
        },
      ]),
    ).rejects.toThrow(/Duplicate descriptor UUID/);
  });

  it('accepts the same descriptor UUID on two different characteristics', async () => {
    await expect(
      createServer([
        {
          uuid: SERVICE,
          characteristics: [
            { ...readable(CHARACTERISTIC), descriptors: [described('2901')] },
            { ...readable(OTHER_CHARACTERISTIC), descriptors: [described('2901')] },
          ],
        },
      ]),
    ).resolves.toBeUndefined();
  });
});

// Native code reads delegate flags with a `?: false` fallback, so a bad shape must be rejected here
// rather than silently published as a fully automatic characteristic.
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

// Native parsers ignore keys they don't recognise, so an unknown key must be caught here instead.
describe('an unrecognised configuration key', () => {
  it('is rejected on a characteristic, where it would publish one fully automatic', async () => {
    await expect(
      createServer([
        {
          uuid: '180d',
          characteristics: [
            {
              uuid: '2a37',
              properties: ['read'],
              permissions: ['readable'],
              delegat: { read: true },
            } as never,
          ],
        },
      ]),
    ).rejects.toThrow(/Unknown characteristic option "delegat"/);
  });

  it('is rejected on a service', async () => {
    await expect(createServer([{ uuid: '180d', characteristic: [] } as never])).rejects.toThrow(
      /Unknown service option "characteristic"/,
    );
  });

  it('is rejected on a descriptor', async () => {
    await expect(
      createServer([
        {
          uuid: '180d',
          characteristics: [
            {
              uuid: '2a37',
              properties: ['read'],
              permissions: ['readable'],
              descriptors: [{ uuid: '2901', value: [1], permission: ['readable'] } as never],
            },
          ],
        },
      ]),
    ).rejects.toThrow(/Unknown descriptor option "permission"/);
  });

  it('is rejected in createServer options, where it would keep the default timeout', async () => {
    await expect(createServer([], { requestTimeoutMS: 2000 } as never)).rejects.toThrow(
      /Unknown createServer option "requestTimeoutMS"/,
    );
  });

  it('is rejected in an advertising config', async () => {
    await expect(startAdvertising({ serviceUUIDs: ['180d'] } as never)).rejects.toThrow(
      /Unknown advertising option "serviceUUIDs"/,
    );
  });

  it('is rejected in the android advertising options', async () => {
    await expect(startAdvertising({ android: { setAdapterNam: true } } as never)).rejects.toThrow(
      /Unknown advertising android option "setAdapterNam"/,
    );
  });

  it('leaves every recognised key accepted', async () => {
    await expect(
      startAdvertising({
        localName: 'Harness',
        serviceUuids: ['180d'],
        connectable: true,
        timeoutMs: 1000,
        manufacturerData: [{ companyId: 1, data: [1] }],
        serviceData: [{ uuid: '180d', data: [1] }],
        android: { includeDeviceName: false, setAdapterName: false },
      }),
    ).resolves.toBeUndefined();
  });
});
