import {
  addBluetoothStateChangedListener,
  addCharacteristicWriteRequestListener,
  addDeviceConnectedListener,
  addMtuChangedListener,
  createServer,
  disconnectDevice,
  getBluetoothState,
  getConnectedDevices,
  getMtu,
  isAdvertising,
  isServerRunning,
  isSupported,
  sendNotification,
  sendResponse,
  startAdvertising,
  stopAdvertising,
  stopServer,
  updateCharacteristicValue,
  type EventSubscription,
} from '../index';

jest.mock('../ExpoGattServerModule', () => ({ __esModule: true, default: null }));

const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';
const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';

describe('isSupported', () => {
  it('reports the absent native module', () => {
    expect(isSupported()).toBe(false);
  });
});

// Every call is listed because the guard is per call site — one that forgets to call it does not
// fail here, it throws "Cannot read property of null" from inside the module instead.
describe('calls that cannot be approximated', () => {
  const rejecting: [string, () => Promise<unknown>][] = [
    ['createServer', () => createServer([])],
    ['startAdvertising', () => startAdvertising()],
    ['sendNotification', () => sendNotification('AA:BB', SERVICE, CHARACTERISTIC, [1])],
    ['sendResponse', () => sendResponse('AA:BB', 1, 0, 0, [])],
    ['updateCharacteristicValue', () => updateCharacteristicValue(SERVICE, CHARACTERISTIC, [1])],
    ['getMtu', () => getMtu('AA:BB')],
    ['disconnectDevice', () => disconnectDevice('AA:BB')],
  ];

  it.each(rejecting)(
    '%s rejects with the explanation, not a null dereference',
    async (_n, call) => {
      await expect(call()).rejects.toThrow(/\[expo-gatt-server\]/);
      await expect(call()).rejects.toThrow(/development build/);
      await expect(call()).rejects.not.toThrow(/of null|null is not an object/);
    },
  );

  // The message names the platform since the advice differs by platform — unsupportedWeb.test.ts
  // covers the web wording, which must not mention a rebuild at all.
  it('names the platform whose binary is missing the module', async () => {
    await expect(createServer([])).rejects.toThrow(/not present in this android binary/);
  });
});

describe('calls that degrade gracefully', () => {
  it('reports the Bluetooth state as unsupported', async () => {
    await expect(getBluetoothState()).resolves.toBe('unsupported');
  });

  it('reports no connected devices', async () => {
    await expect(getConnectedDevices()).resolves.toEqual([]);
  });

  it('reports no running server', async () => {
    await expect(isServerRunning()).resolves.toBe(false);
  });

  it('reports no advertising', async () => {
    await expect(isAdvertising()).resolves.toBe(false);
  });

  // A teardown path is the wrong place to raise a configuration error the setup path already reported.
  it('lets the teardown calls be made without throwing', () => {
    expect(stopAdvertising()).toBeUndefined();
    expect(stopServer()).toBeUndefined();
  });
});

describe('listener helpers', () => {
  // Representative sample of the thin per-event wrappers around the same addListener helper — first,
  // an interior, and the last.
  const helpers: [string, (listener: (event: any) => void) => EventSubscription][] = [
    ['addMtuChangedListener', addMtuChangedListener],
    ['addCharacteristicWriteRequestListener', addCharacteristicWriteRequestListener],
    ['addBluetoothStateChangedListener', addBluetoothStateChangedListener],
  ];

  it.each(helpers)('%s returns a subscription rather than throwing', (_name, subscribe) => {
    expect(typeof subscribe(() => {}).remove).toBe('function');
  });

  it('hands back a subscription that can be removed repeatedly', () => {
    const subscription = addDeviceConnectedListener(() => {});

    expect(() => {
      subscription.remove();
      subscription.remove();
    }).not.toThrow();
  });

  it('never delivers an event to a listener registered without a native module', () => {
    const listener = jest.fn();
    addDeviceConnectedListener(listener);
    expect(listener).not.toHaveBeenCalled();
  });
});
