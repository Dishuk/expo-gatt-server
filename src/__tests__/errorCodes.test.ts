import {
  createServer,
  disconnectDevice,
  getMtu,
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

const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';
const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';
const DEVICE = 'AA:BB:CC:DD:EE:FF';

/** Shaped like an Expo modules rejection, which carries the native code on `error.code`. */
function codedError(code: string): Error & { code: string } {
  return Object.assign(new Error(`Rejected with ${code}`), { code });
}

/**
 * Every entry point that can reject with a code, and the codes it is documented to produce on **either**
 * platform. Some are raised by only one of them — `ERR_NO_CONTEXT` needs an Android `Context`, and
 * `disconnectDevice` rejects with `ERR_UNSUPPORTED` only on iOS, where CoreBluetooth cannot drop a
 * central at all.
 *
 * The table is deliberately **not** split by platform anyway, because what it pins down is the
 * JavaScript wrapper handing the native code back untouched — flattening one into a generic code, or
 * losing it behind a rethrown `Error`, is what stops a consumer branching on it. That pass-through is
 * the same code either side of the boundary, so feeding a platform its counterpart's code still
 * exercises it, and a JavaScript-side branch here would only fail one of the two runs.
 */
const CODED_ENTRY_POINTS: {
  name: string;
  native: jest.Mock;
  invoke: () => Promise<unknown>;
  codes: string[];
}[] = [
  {
    name: 'createServer',
    native: nativeModuleMock.createServer,
    invoke: () => createServer([]),
    codes: [
      'ERR_BLUETOOTH',
      'ERR_PERMISSION',
      'ERR_NO_CONTEXT',
      'ERR_UNSUPPORTED',
      'ERR_NO_SERVER',
      'ERR_CREATE_SERVER',
    ],
  },
  {
    name: 'startAdvertising',
    native: nativeModuleMock.startAdvertising,
    invoke: () => startAdvertising(),
    codes: [
      'ERR_BLUETOOTH',
      'ERR_PERMISSION',
      'ERR_NO_CONTEXT',
      'ERR_UNSUPPORTED',
      'ERR_NO_SERVER',
      'ERR_ADVERTISE',
    ],
  },
  {
    name: 'sendNotification',
    native: nativeModuleMock.sendNotification,
    invoke: () => sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [1]),
    codes: [
      'ERR_BLUETOOTH',
      'ERR_NO_SERVER',
      'ERR_DEVICE_DISCONNECTED',
      'ERR_CHARACTERISTIC_NOT_FOUND',
      'ERR_CONFIRM_UNSUPPORTED',
      'ERR_NO_SUBSCRIBER',
      'PAYLOAD_EXCEEDS_MTU',
      'ERR_NOTIFY_QUEUE_FULL',
      'ERR_NOTIFY',
    ],
  },
  {
    name: 'sendResponse',
    native: nativeModuleMock.sendResponse,
    invoke: () => sendResponse(DEVICE, 1, 0, 0, [1]),
    codes: [
      'ERR_BLUETOOTH',
      'ERR_NO_SERVER',
      'ERR_DEVICE_DISCONNECTED',
      'REQUEST_NOT_FOUND',
      'REQUEST_DEVICE_MISMATCH',
      'ERR_RESPONSE_OFFSET',
      'ERR_RESPONSE',
    ],
  },
  {
    name: 'updateCharacteristicValue',
    native: nativeModuleMock.updateCharacteristicValue,
    invoke: () => updateCharacteristicValue(SERVICE, CHARACTERISTIC, [1]),
    codes: [
      'ERR_BLUETOOTH',
      'ERR_NO_SERVER',
      'ERR_CHARACTERISTIC_NOT_FOUND',
      'ERR_UPDATE_VALUE',
    ],
  },
  {
    name: 'getMtu',
    native: nativeModuleMock.getMtu,
    invoke: () => getMtu(DEVICE),
    codes: ['ERR_NO_SERVER', 'ERR_DEVICE_DISCONNECTED'],
  },
  {
    name: 'disconnectDevice',
    native: nativeModuleMock.disconnectDevice,
    invoke: () => disconnectDevice(DEVICE),
    codes: [
      'ERR_UNSUPPORTED',
      'ERR_PERMISSION',
      'ERR_NO_CONTEXT',
      'ERR_NO_SERVER',
      'ERR_DEVICE_DISCONNECTED',
      'ERR_DISCONNECT',
    ],
  },
];

const codedCases = CODED_ENTRY_POINTS.flatMap(({ name, native, invoke, codes }) =>
  codes.map((code) => [name, code, native, invoke] as const),
);

describe('a native error code reaches the caller unchanged', () => {
  it.each(codedCases)('%s surfaces %s', async (_name, code, native, invoke) => {
    native.mockRejectedValueOnce(codedError(code));
    await expect(invoke()).rejects.toMatchObject({ code });
  });

  it.each(codedCases)('%s surfaces the native message for %s', async (_name, code, native, invoke) => {
    native.mockRejectedValueOnce(codedError(code));
    await expect(invoke()).rejects.toThrow(`Rejected with ${code}`);
  });
});

/**
 * A coded rejection therefore always came from the platform, which is what lets a consumer treat a
 * missing `code` as "fix the configuration" and a present one as "handle this at runtime".
 */
describe('a rejection raised in JavaScript carries no code', () => {
  const validationFailures: [string, () => Promise<unknown>][] = [
    ['createServer with a malformed service UUID', () => createServer([{ uuid: 'nope' } as never])],
    [
      'createServer with an out-of-range requestTimeoutMs',
      () => createServer([], { requestTimeoutMs: -1 }),
    ],
    ['startAdvertising with an unknown mode', () => startAdvertising({ mode: 'fastest' as never })],
    [
      'sendNotification with a byte outside 0-255',
      () => sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [256]),
    ],
    ['sendResponse with a status wider than a byte', () => sendResponse(DEVICE, 1, 256, 0, [])],
    [
      'updateCharacteristicValue with a malformed characteristic UUID',
      () => updateCharacteristicValue(SERVICE, 'nope', [1]),
    ],
  ];

  it.each(validationFailures)('%s', async (_name, invoke) => {
    await expect(invoke()).rejects.not.toMatchObject({ code: expect.anything() });
  });

  it.each(validationFailures)('%s never reaches the native module', async (_name, invoke) => {
    jest.clearAllMocks();
    await expect(invoke()).rejects.toThrow();
    for (const { native } of CODED_ENTRY_POINTS) {
      expect(native).not.toHaveBeenCalled();
    }
  });
});
