import type { NativeModule } from 'expo';

import type { BluetoothState, ConnectedDevice, GattServerEvents } from '../ExpoGattServer.types';
import type { ExpoGattServerModuleType } from '../ExpoGattServerModule';

/**
 * Stands in for the native module. Every method resolves successfully by default, so a rejection in
 * a test is attributable to the validation under test rather than to the mock. Arguments are
 * untyped so assertions can reach into the normalised payloads.
 */
export const nativeModuleMock = {
  createServer: jest.fn(async (..._args: any[]) => undefined),
  startAdvertising: jest.fn(async (..._args: any[]) => undefined),
  stopAdvertising: jest.fn((): void => {}),
  sendNotification: jest.fn(async (..._args: any[]) => undefined),
  sendResponse: jest.fn(async (..._args: any[]) => undefined),
  updateCharacteristicValue: jest.fn(async (..._args: any[]) => undefined),
  stopServer: jest.fn((): void => {}),
  getBluetoothState: jest.fn(async (): Promise<BluetoothState> => 'poweredOn'),
  getMtu: jest.fn(async () => ({ deviceId: 'AA:BB', mtu: 23, maxNotificationPayload: 20 })),
  // Annotated rather than inferred: an empty literal would fix the element type as `never` and reject
  // any populated list a test hands to `mockResolvedValueOnce`.
  getConnectedDevices: jest.fn(async (): Promise<ConnectedDevice[]> => []),
  disconnectDevice: jest.fn(async (..._args: any[]) => undefined),
  isServerRunning: jest.fn(async () => true),
  isAdvertising: jest.fn(async () => true),
  addListener: jest.fn((..._args: any[]) => ({ remove: jest.fn() })),
} satisfies Record<DeclaredMethod, jest.Mock> & { addListener: jest.Mock };

/** Methods this module declares itself, excluding the ones it inherits from `NativeModule`. */
type DeclaredMethod = Exclude<
  keyof ExpoGattServerModuleType,
  // `NativeModule<T>` aliases the *constructor* type, so its own `keyof` is `prototype` and friends —
  // `InstanceType` is what names the inherited members this module does not declare.
  keyof InstanceType<NativeModule<GattServerEvents>>
>;

/**
 * Compile-time only. Ensures a method added to or renamed on the native module type cannot leave the
 * mock silently short of it. Does not check that `ExpoGattServerModule.ts`'s declaration still
 * matches the Kotlin/Swift — `nativeSurface.test.ts` covers that.
 */
type MissingFromMock = Exclude<DeclaredMethod, keyof typeof nativeModuleMock>;
type ReturnTypeMismatches = {
  [K in DeclaredMethod & keyof typeof nativeModuleMock]: (typeof nativeModuleMock)[K] extends (
    ...args: never[]
  ) => infer Mocked
    ? ExpoGattServerModuleType[K] extends (...args: never[]) => infer Declared
      ? Awaited<Mocked> extends Awaited<Declared>
        ? never
        : K
      : never
    : never;
}[DeclaredMethod & keyof typeof nativeModuleMock];

/** Fails to compile unless `T` is `never`; the `extends never` constraint is what makes it an error. */
type AssertNever<T extends never> = T;

export type MockIsComplete = AssertNever<MissingFromMock>;
export type MockReturnsMatch = AssertNever<ReturnTypeMismatches>;

/** The arguments of the `nth` call to `method`; fails the test rather than returning `undefined`. */
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
