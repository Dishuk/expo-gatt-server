import type { BluetoothState, ConnectedDevice } from '../ExpoGattServer.types';
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
  getBluetoothState: jest.fn(async (): Promise<BluetoothState> => 'poweredOn'),
  getMtu: jest.fn(async () => ({ deviceId: 'AA:BB', mtu: 23, maxNotificationPayload: 20 })),
  // Annotated rather than inferred: an empty literal would fix the element type as `never` and reject
  // any populated list a test hands to `mockResolvedValueOnce`.
  getConnectedDevices: jest.fn(async (): Promise<ConnectedDevice[]> => []),
  disconnectDevice: jest.fn(async (..._args: any[]) => undefined),
  isServerRunning: jest.fn(async () => true),
  isAdvertising: jest.fn(async () => true),
  addListener: jest.fn((..._args: any[]) => ({ remove: jest.fn() })),
} satisfies Partial<Record<keyof ExpoGattServerModuleType, jest.Mock>>;

/**
 * The arguments of the `nth` call to `method`, failing the test rather than returning `undefined` when
 * there was no such call.
 *
 * Reaching into `mock.calls[n]` directly reads as an assertion but is not one: an index that does not
 * exist yields `undefined`, and the property accesses that follow then throw a `TypeError` naming a
 * line rather than the missing call. Checking here turns "the module was never called" into that
 * sentence.
 */
export function callArgs<Method extends keyof typeof nativeModuleMock>(
  method: Method,
  nth = 0,
): any[] {
  const calls = nativeModuleMock[method].mock.calls;
  if (nth >= calls.length) {
    throw new Error(
      `Expected ${String(method)} to have been called at least ${nth + 1} time(s), ` +
        `but it was called ${calls.length} time(s).`,
    );
  }
  return calls[nth] as any[];
}

/** The normalised service list `createServer` was handed, which most assertions are about. */
export function publishedServices(nth = 0): any[] {
  return callArgs('createServer', nth)[0];
}
