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
  getBluetoothState,
  getConnectedDevices,
  isAdvertising,
  isServerRunning,
  isSupported,
  stopAdvertising,
  stopServer,
  type ConnectedDevice,
  type EventSubscription,
} from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

/**
 * The calls that answer with a fallback when the native module is absent, and the listener helpers that
 * degrade to a no-op subscription. `unsupported.test.ts` pins the fallbacks; without the mirror image
 * here, every one of them could be replaced by its own fallback — `false`, `[]`, `'unsupported'`,
 * nothing at all — and still satisfy the suite.
 */
describe('delegation to the native module', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('reports the present native module', () => {
    expect(isSupported()).toBe(true);
  });

  it('answers isServerRunning from the native module', async () => {
    await expect(isServerRunning()).resolves.toBe(true);
    expect(nativeModuleMock.isServerRunning).toHaveBeenCalledTimes(1);
  });

  it('answers isAdvertising from the native module', async () => {
    await expect(isAdvertising()).resolves.toBe(true);
    expect(nativeModuleMock.isAdvertising).toHaveBeenCalledTimes(1);
  });

  it('answers getBluetoothState from the native module', async () => {
    await expect(getBluetoothState()).resolves.toBe('poweredOn');
    expect(nativeModuleMock.getBluetoothState).toHaveBeenCalledTimes(1);
  });

  it('reports a state the native module invents rather than a state of its own', async () => {
    nativeModuleMock.getBluetoothState.mockResolvedValueOnce('resetting');

    await expect(getBluetoothState()).resolves.toBe('resetting');
  });

  it('answers getConnectedDevices with the list the native module returns', async () => {
    const devices: ConnectedDevice[] = [
      { deviceId: 'AA:BB:CC:DD:EE:FF', name: 'Central' },
      { deviceId: '11:22:33:44:55:66' },
    ];
    nativeModuleMock.getConnectedDevices.mockResolvedValueOnce(devices);

    await expect(getConnectedDevices()).resolves.toEqual(devices);
    expect(nativeModuleMock.getConnectedDevices).toHaveBeenCalledTimes(1);
  });

  it('reaches the native module to stop the server', () => {
    stopServer();

    expect(nativeModuleMock.stopServer).toHaveBeenCalledTimes(1);
  });

  it('reaches the native module to stop advertising', () => {
    stopAdvertising();

    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(1);
  });
});

describe('listener helpers', () => {
  // Each helper exists to spell one event name correctly on the caller's behalf, so the name is the
  // whole of what it can get wrong — and a helper subscribed to a sibling's event is silent rather
  // than broken.
  const helpers: [string, string, (listener: (event: any) => void) => EventSubscription][] = [
    ['addMtuChangedListener', 'onMtuChanged', addMtuChangedListener],
    ['addDeviceConnectedListener', 'onDeviceConnected', addDeviceConnectedListener],
    ['addDeviceDisconnectedListener', 'onDeviceDisconnected', addDeviceDisconnectedListener],
    [
      'addCharacteristicReadRequestListener',
      'onCharacteristicReadRequest',
      addCharacteristicReadRequestListener,
    ],
    [
      'addCharacteristicWriteRequestListener',
      'onCharacteristicWriteRequest',
      addCharacteristicWriteRequestListener,
    ],
    ['addNotificationSentListener', 'onNotificationSent', addNotificationSentListener],
    [
      'addCharacteristicSubscribedListener',
      'onCharacteristicSubscribed',
      addCharacteristicSubscribedListener,
    ],
    [
      'addCharacteristicUnsubscribedListener',
      'onCharacteristicUnsubscribed',
      addCharacteristicUnsubscribedListener,
    ],
    [
      'addBluetoothStateChangedListener',
      'onBluetoothStateChanged',
      addBluetoothStateChangedListener,
    ],
  ];

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it.each(helpers)('%s subscribes to %s', (_name, eventName, subscribe) => {
    const listener = jest.fn();

    subscribe(listener);

    expect(nativeModuleMock.addListener).toHaveBeenCalledTimes(1);
    expect(nativeModuleMock.addListener).toHaveBeenCalledWith(eventName, listener);
  });

  it.each(helpers)('%s hands back the native subscription', (_name, _eventName, subscribe) => {
    const subscription = { remove: jest.fn() };
    nativeModuleMock.addListener.mockReturnValueOnce(subscription);

    expect(subscribe(() => {})).toBe(subscription);
  });

  it('subscribes each helper to an event name no other helper claims', () => {
    const eventNames = helpers.map(([, eventName]) => eventName);

    expect(new Set(eventNames).size).toBe(helpers.length);
  });
});
