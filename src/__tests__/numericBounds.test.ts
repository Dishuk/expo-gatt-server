import { Platform } from 'expo-modules-core';

import {
  ATT_TRANSACTION_TIMEOUT_MS,
  GATT_SUCCESS,
  createServer,
  sendResponse,
  startAdvertising,
  type AdvertisingMode,
  type AdvertisingTxPower,
} from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

/** The bound `AdvertiseSettings.Builder.setTimeout` enforces, applied on both platforms. */
const MAX_ADVERTISING_TIMEOUT_MS = 180000;

let warn: jest.SpyInstance;

beforeEach(() => {
  warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  warn.mockRestore();
});

describe('createServer request timeout', () => {
  it.each([0, 1, 9999, ATT_TRANSACTION_TIMEOUT_MS - 1])(
    'accepts %d ms',
    async (requestTimeoutMs) => {
      await expect(createServer([], { requestTimeoutMs })).resolves.toBeUndefined();
    },
  );

  it.each([ATT_TRANSACTION_TIMEOUT_MS, ATT_TRANSACTION_TIMEOUT_MS + 1, -1, 1.5, NaN, Infinity])(
    'rejects %p',
    async (requestTimeoutMs) => {
      await expect(createServer([], { requestTimeoutMs })).rejects.toThrow(
        /Invalid request timeout/,
      );
    },
  );

  it('names the exclusive upper bound in the error', async () => {
    await expect(createServer([], { requestTimeoutMs: 30000 })).rejects.toThrow(
      /between 0 and 29999 milliseconds/,
    );
  });

  it('leaves the timeout to the native default when unset', async () => {
    await createServer([]);
    expect(nativeModuleMock.createServer).toHaveBeenCalledWith([], {});
  });
});

describe('advertising timeout', () => {
  it.each([0, 1, MAX_ADVERTISING_TIMEOUT_MS])('accepts %d ms', async (timeoutMs) => {
    await expect(startAdvertising({ timeoutMs })).resolves.toBeUndefined();
  });

  it.each([MAX_ADVERTISING_TIMEOUT_MS + 1, -1, 0.5, NaN])('rejects %p', async (timeoutMs) => {
    await expect(startAdvertising({ timeoutMs })).rejects.toThrow(/Invalid advertising timeout/);
  });
});

describe('advertising enumerations', () => {
  it.each<AdvertisingMode>(['lowPower', 'balanced', 'lowLatency'])(
    'accepts the %s mode',
    async (mode) => {
      await expect(startAdvertising({ mode })).resolves.toBeUndefined();
    },
  );

  it.each(['LOW_POWER', 'lowpower', 'fast', ''])('rejects %p as a mode', async (mode) => {
    await expect(startAdvertising({ mode: mode as AdvertisingMode })).rejects.toThrow(
      /Invalid advertising mode/,
    );
  });

  it.each<AdvertisingTxPower>(['ultraLow', 'low', 'medium', 'high'])(
    'accepts the %s tx power level',
    async (txPowerLevel) => {
      await expect(startAdvertising({ txPowerLevel })).resolves.toBeUndefined();
    },
  );

  it.each(['ULTRA_LOW', 'maximum', ''])('rejects %p as a tx power level', async (txPowerLevel) => {
    await expect(
      startAdvertising({ txPowerLevel: txPowerLevel as AdvertisingTxPower }),
    ).rejects.toThrow(/Invalid advertising tx power level/);
  });

  it('warns about the radio options only where they cannot be honoured', async () => {
    await startAdvertising({ mode: 'balanced', txPowerLevel: 'high', includeTxPowerLevel: true });

    if (Platform.OS === 'ios') {
      expect(warn).toHaveBeenCalledWith(
        expect.stringContaining('iOS ignores mode, txPowerLevel, includeTxPowerLevel'),
      );
    } else {
      expect(warn).not.toHaveBeenCalled();
    }
    expect(nativeModuleMock.startAdvertising).toHaveBeenCalled();
  });
});

describe('manufacturer company identifier', () => {
  it.each([0, 1, 0xffff])('accepts %d', async (companyId) => {
    await expect(
      startAdvertising({ manufacturerData: [{ companyId, data: [1] }] }),
    ).resolves.toBeUndefined();
  });

  it.each([0x10000, -1, 1.5, NaN])('rejects %p', async (companyId) => {
    await expect(
      startAdvertising({ manufacturerData: [{ companyId, data: [1] }] }),
    ).rejects.toThrow(/Invalid manufacturer company id/);
  });

  it('rejects a missing company identifier', async () => {
    await expect(startAdvertising({ manufacturerData: [{ data: [1] } as never] })).rejects.toThrow(
      /Invalid manufacturer company id/,
    );
  });
});

describe('sendResponse status', () => {
  it.each([0, 1, 255])('accepts %d', async (status) => {
    await expect(sendResponse('AA:BB', 1, status, 0, [])).resolves.toBeUndefined();
  });

  it.each([256, -1, 1.5, NaN])('rejects %p', async (status) => {
    await expect(sendResponse('AA:BB', 1, status, 0, [])).rejects.toThrow(
      /Invalid response status/,
    );
  });
});

describe('sendResponse offset', () => {
  it.each([0, 1, 0xffff])('accepts %d', async (offset) => {
    await expect(sendResponse('AA:BB', 1, GATT_SUCCESS, offset, [])).resolves.toBeUndefined();
  });

  it.each([0x10000, -1, 1.5, NaN])('rejects %p', async (offset) => {
    await expect(sendResponse('AA:BB', 1, GATT_SUCCESS, offset, [])).rejects.toThrow(
      /Invalid response offset/,
    );
  });

  it('never reaches the native module with an out-of-range offset', async () => {
    await expect(sendResponse('AA:BB', 1, GATT_SUCCESS, -1, [])).rejects.toThrow();
    expect(nativeModuleMock.sendResponse).not.toHaveBeenCalled();
  });
});
