import {
  addBluetoothStateChangedListener,
  addCharacteristicReadRequestListener,
  addCharacteristicSubscribedListener,
  addCharacteristicUnsubscribedListener,
  addCharacteristicWriteRequestListener,
  addDeviceConnectedListener,
  addDeviceDisconnectedListener,
  addMtuChangedListener,
  addNotificationSentListener,
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

  it.each(rejecting)('%s rejects', async (_name, call) => {
    await expect(call()).rejects.toThrow(/\[expo-gatt-server\]/);
  });

  it.each(rejecting)('%s explains how to get a binary containing the module', async (_name, call) => {
    await expect(call()).rejects.toThrow(/development build/);
  });

  it('rejects rather than letting a property access on null surface as the error', async () => {
    await expect(createServer([])).rejects.not.toThrow(/of null|null is not an object/);
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

  it('lets stopAdvertising be called without throwing', () => {
    expect(() => stopAdvertising()).not.toThrow();
    expect(stopAdvertising()).toBeUndefined();
  });

  it('lets stopServer be called without throwing', () => {
    expect(() => stopServer()).not.toThrow();
    expect(stopServer()).toBeUndefined();
  });
});

describe('listener helpers', () => {
  // The event payload differs per helper and is irrelevant here, so the listener is typed loosely
  // enough to be accepted by all of them.
  const helpers: [string, (listener: (event: any) => void) => EventSubscription][] = [
    ['addMtuChangedListener', addMtuChangedListener],
    ['addDeviceConnectedListener', addDeviceConnectedListener],
    ['addDeviceDisconnectedListener', addDeviceDisconnectedListener],
    ['addCharacteristicReadRequestListener', addCharacteristicReadRequestListener],
    ['addCharacteristicWriteRequestListener', addCharacteristicWriteRequestListener],
    ['addNotificationSentListener', addNotificationSentListener],
    ['addCharacteristicSubscribedListener', addCharacteristicSubscribedListener],
    ['addCharacteristicUnsubscribedListener', addCharacteristicUnsubscribedListener],
    ['addBluetoothStateChangedListener', addBluetoothStateChangedListener],
  ];

  it.each(helpers)('%s returns a subscription rather than throwing', (_name, subscribe) => {
    const subscription = subscribe(() => {});
    expect(typeof subscription.remove).toBe('function');
  });

  // An effect that registers a listener pairs it with a `remove()` in its teardown, which would
  // otherwise crash on a value it never received.
  it.each(helpers)('%s returns a subscription that can be removed twice', (_name, subscribe) => {
    const subscription = subscribe(() => {});
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
