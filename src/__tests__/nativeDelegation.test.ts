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
  addServerPublicationFailedListener,
  createServer,
  getBluetoothState,
  getConnectedDevices,
  isAdvertising,
  isServerRunning,
  isSupported,
  disconnectDevice,
  getMtu,
  sendResponse,
  updateCharacteristicValue,
  sendNotification,
  startAdvertising,
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

// Mirror of `unsupported.test.ts`: pins that each call answers from the native module rather than a
// fallback value when the module is present.
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
  // Each helper exists to spell one event name correctly on the caller's behalf.
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
    [
      'addServerPublicationFailedListener',
      'onServerPublicationFailed',
      addServerPublicationFailedListener,
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

  it('hands back the native subscription rather than wrapping it', () => {
    const subscription = { remove: jest.fn() };
    nativeModuleMock.addListener.mockReturnValueOnce(subscription);

    expect(addMtuChangedListener(() => {})).toBe(subscription);
  });
});

// Pins that validated arguments are actually forwarded to the native module, not only checked and
// dropped.
describe('argument forwarding', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    nativeModuleMock.createServer.mockImplementation(async () => undefined);
    nativeModuleMock.startAdvertising.mockImplementation(async () => undefined);
  });

  it('forwards createServer options rather than validating and discarding them', async () => {
    await createServer([], { requestTimeoutMs: 2500 });

    expect(nativeModuleMock.createServer).toHaveBeenCalledWith([], { requestTimeoutMs: 2500 });
  });

  it('forwards every advertising option, not only the normalised ones', async () => {
    await startAdvertising({
      localName: 'Harness',
      serviceUuids: ['180d'],
      connectable: false,
      timeoutMs: 5000,
      manufacturerData: [{ companyId: 0xffff, data: [1, 2] }],
      serviceData: [{ uuid: '180d', data: [3] }],
      android: { setAdapterName: true, includeDeviceName: false },
    });

    expect(nativeModuleMock.startAdvertising).toHaveBeenCalledWith({
      localName: 'Harness',
      // Normalised to the 128-bit form on the way through, which is the one thing that does change.
      serviceUuids: ['0000180d-0000-1000-8000-00805f9b34fb'],
      connectable: false,
      timeoutMs: 5000,
      manufacturerData: [{ companyId: 0xffff, data: [1, 2] }],
      serviceData: [{ uuid: '0000180d-0000-1000-8000-00805f9b34fb', data: [3] }],
      android: { setAdapterName: true, includeDeviceName: false },
    });
  });

  it('forwards the notification arguments in the order the native module declares', async () => {
    await sendNotification('AA:BB', '180d', '2a37', [1, 2], true, { requireSubscription: false });

    expect(nativeModuleMock.sendNotification).toHaveBeenCalledWith(
      'AA:BB',
      '0000180d-0000-1000-8000-00805f9b34fb',
      '00002a37-0000-1000-8000-00805f9b34fb',
      [1, 2],
      true,
      false,
    );
  });

  it('defaults requireSubscription to true rather than leaving it undefined', async () => {
    await sendNotification('AA:BB', '180d', '2a37', [1]);

    expect(nativeModuleMock.sendNotification).toHaveBeenCalledWith(
      'AA:BB',
      expect.any(String),
      expect.any(String),
      [1],
      false,
      true,
    );
  });
  // `sendResponse` takes three consecutive numbers (requestId, status, offset); pins the order, not
  // only the values.
  it('forwards the response arguments in the order the native module declares', async () => {
    await sendResponse('AA:BB', 7, 0x0e, 3, [9]);

    expect(nativeModuleMock.sendResponse).toHaveBeenCalledWith('AA:BB', 7, 0x0e, 3, [9]);
  });

  it('forwards the characteristic value arguments in declaration order', async () => {
    await updateCharacteristicValue('180d', '2a37', [4, 5]);

    expect(nativeModuleMock.updateCharacteristicValue).toHaveBeenCalledWith(
      '0000180d-0000-1000-8000-00805f9b34fb',
      '00002a37-0000-1000-8000-00805f9b34fb',
      [4, 5],
    );
  });

  it('forwards the device id to the single-argument calls', async () => {
    await getMtu('AA:BB');
    expect(nativeModuleMock.getMtu).toHaveBeenCalledWith('AA:BB');

    await disconnectDevice('CC:DD');
    expect(nativeModuleMock.disconnectDevice).toHaveBeenCalledWith('CC:DD');
  });
});
