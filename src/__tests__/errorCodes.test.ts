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

// Every entry point that can reject with a native code, listed once each — the wrapper has no
// per-code logic, so this pins that each entry point still passes the native rejection through.
const CODED_ENTRY_POINTS: [string, jest.Mock, () => Promise<unknown>][] = [
  ['createServer', nativeModuleMock.createServer, () => createServer([])],
  ['startAdvertising', nativeModuleMock.startAdvertising, () => startAdvertising()],
  [
    'sendNotification',
    nativeModuleMock.sendNotification,
    () => sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [1]),
  ],
  ['sendResponse', nativeModuleMock.sendResponse, () => sendResponse(DEVICE, 1, 0, 0, [1])],
  [
    'updateCharacteristicValue',
    nativeModuleMock.updateCharacteristicValue,
    () => updateCharacteristicValue(SERVICE, CHARACTERISTIC, [1]),
  ],
  ['getMtu', nativeModuleMock.getMtu, () => getMtu(DEVICE)],
  ['disconnectDevice', nativeModuleMock.disconnectDevice, () => disconnectDevice(DEVICE)],
];

describe('a native rejection reaches the caller unchanged', () => {
  it.each(CODED_ENTRY_POINTS)(
    '%s keeps the code and the message',
    async (_name, native, invoke) => {
      native.mockRejectedValueOnce(codedError('ERR_BLUETOOTH'));

      await expect(invoke()).rejects.toMatchObject({ code: 'ERR_BLUETOOTH' });

      native.mockRejectedValueOnce(codedError('ERR_BLUETOOTH'));
      await expect(invoke()).rejects.toThrow('Rejected with ERR_BLUETOOTH');
    },
  );
});

// The converse does not hold: `serverStoppedError` and `advertisingCancelledError` are raised here
// with a code and never reach a platform.
describe('a validation failure carries no code and never reaches the platform', () => {
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
    jest.clearAllMocks();

    await expect(invoke()).rejects.not.toMatchObject({ code: expect.anything() });

    for (const [, native] of CODED_ENTRY_POINTS) {
      expect(native).not.toHaveBeenCalled();
    }
  });
});
