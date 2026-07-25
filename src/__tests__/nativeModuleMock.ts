import type { ExpoGattServerModuleType } from '../ExpoGattServerModule';

/**
 * Stands in for the native module so the JavaScript layer can be exercised without a device or a
 * native build. Every method resolves successfully, which makes a rejection in a test attributable to
 * the validation under test rather than to the mock.
 *
 * Arguments are deliberately untyped so assertions can reach into the normalised payloads;
 * `satisfies` still keeps the method names honest against the native module's own declaration.
 */
export const nativeModuleMock = {
  createServer: jest.fn(async (..._args: any[]) => undefined),
  startAdvertising: jest.fn(async (..._args: any[]) => undefined),
  stopAdvertising: jest.fn(),
  sendNotification: jest.fn(async (..._args: any[]) => undefined),
  sendResponse: jest.fn(async (..._args: any[]) => undefined),
  updateCharacteristicValue: jest.fn(async (..._args: any[]) => undefined),
  stopServer: jest.fn(),
  getBluetoothState: jest.fn(async () => 'poweredOn'),
  getMtu: jest.fn(async () => ({ deviceId: 'AA:BB', mtu: 23, maxNotificationPayload: 20 })),
  getConnectedDevices: jest.fn(async () => []),
  disconnectDevice: jest.fn(async (..._args: any[]) => undefined),
  isServerRunning: jest.fn(async () => true),
  isAdvertising: jest.fn(async () => true),
  addListener: jest.fn((..._args: any[]) => ({ remove: jest.fn() })),
} satisfies Partial<Record<keyof ExpoGattServerModuleType, jest.Mock>>;
