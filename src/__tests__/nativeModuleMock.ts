import type { NativeModule } from 'expo';

import type { BluetoothState, ConnectedDevice, GattServerEvents } from '../ExpoGattServer.types';
import type { ExpoGattServerModuleType } from '../ExpoGattServerModule';

/**
 * Stands in for the native module so the JavaScript layer can be exercised without a device or a
 * native build. Every method resolves successfully, which makes a rejection in a test attributable to
 * the validation under test rather than to the mock.
 *
 * Arguments are deliberately untyped so assertions can reach into the normalised payloads; the checks
 * below keep the method names and return types honest against the native module's own declaration.
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

/**
 * The methods this module declares itself, as opposed to the ones it inherits from `NativeModule`
 * (`addListener`, `removeAllListeners`, and the rest of Expo's event plumbing). Only the former are
 * the module's own contract, and only they need mocking in full.
 */
type DeclaredMethod = Exclude<
  keyof ExpoGattServerModuleType,
  // `NativeModule<T>` aliases the *constructor* type, so its own `keyof` is `prototype` and friends —
  // `InstanceType` is what names the inherited members this module does not declare.
  keyof InstanceType<NativeModule<GattServerEvents>>
>;

/**
 * Compile-time only, and the reason the `satisfies` above is not `Partial`.
 *
 * `Partial<Record<…>>` checked that no *extra* names appeared, and nothing else: a method added to the
 * native module, or renamed on it, left the mock silently short of it while every suite stayed green.
 * These two aliases turn both into type errors. They are types, so they cost nothing at runtime.
 *
 * What no TypeScript check here can reach is whether `ExpoGattServerModule.ts`'s hand-written
 * declaration still matches the Kotlin and the Swift. That is what the `android-integration` and
 * `ios-integration` jobs in `.github/workflows/ci.yml` compile for.
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

/**
 * Fails to compile unless `T` is `never`.
 *
 * A bare `type X = … ? true : never` would not do: an alias that resolves to `never` is perfectly legal
 * and reports nothing. Putting the union in a `extends never` constraint is what turns a non-empty one
 * into a type error that names the offending method.
 */
type AssertNever<T extends never> = T;

export type MockIsComplete = AssertNever<MissingFromMock>;
export type MockReturnsMatch = AssertNever<ReturnTypeMismatches>;

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
