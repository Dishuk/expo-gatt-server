import { createServer, startAdvertising, stopServer, type GattServiceConfig } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

/**
 * A `stopServer` issued while a `createServer` is still in flight must win, and the ordering it would need
 * does not exist natively — the same hazard `advertisingCancellation.test.ts` pins for advertising.
 * `stopServer` is a synchronous Expo `Function`, so its body runs on the JavaScript thread immediately,
 * while `createServer` is an `AsyncFunction` whose body runs later on Expo's worker queue. A stop issued
 * second therefore reaches the native side first, finds no manager, and the create behind it publishes the
 * database anyway.
 *
 * This is the ordinary React case: an effect that calls `createServer` and returns a teardown calling
 * `stopServer`, unmounted before the create settles. iOS's native `serverStopEpoch` does not catch it,
 * because that epoch is read inside the `AsyncFunction` body — after the JavaScript call has returned — and
 * Android has no equivalent. JavaScript is single-threaded, so the call order here *is* the application's
 * intent, and the shared layer is the only place that knows it.
 */

const SERVICES: GattServiceConfig[] = [
  {
    uuid: '180d',
    characteristics: [{ uuid: '2a37', properties: ['read'], permissions: ['readable'] }],
  },
];

/**
 * Holds the native `createServer` in flight until the returned function is called, so a stop can be issued
 * in between — the interleaving the guard exists for.
 */
function deferNativeCreate(): () => void {
  let release: () => void = () => {};
  nativeModuleMock.createServer.mockImplementationOnce(
    () =>
      new Promise<undefined>((resolve) => {
        release = () => resolve(undefined);
      }),
  );
  return () => release();
}

// The epoch counters behind this are module-level and monotonic, so they need no resetting: every create
// reads them afresh and only ever compares them against itself. Only the mocks carry state worth clearing.
beforeEach(() => {
  jest.clearAllMocks();
  nativeModuleMock.createServer.mockImplementation(async () => undefined);
});

describe('a stop issued while a create is in flight', () => {
  it('rejects the create and stops the server again', async () => {
    const release = deferNativeCreate();

    const create = createServer(SERVICES);
    stopServer();
    release();

    await expect(create).rejects.toThrow(/cancelled by a stopServer/);
    // Twice: once from the application's own call, once from the guard — because whether the first one
    // reached the manager before the create did is exactly what is not knowable from here.
    expect(nativeModuleMock.stopServer).toHaveBeenCalledTimes(2);
  });

  it('reports ERR_NO_SERVER, the code both platforms already reject a stopped create with', async () => {
    const release = deferNativeCreate();

    const create = createServer(SERVICES);
    stopServer();
    release();

    await expect(create).rejects.toMatchObject({ code: 'ERR_NO_SERVER' });
  });

  it('leaves a create that no stop overtook alone', async () => {
    await expect(createServer(SERVICES)).resolves.toBeUndefined();

    expect(nativeModuleMock.createServer).toHaveBeenCalledTimes(1);
    expect(nativeModuleMock.stopServer).not.toHaveBeenCalled();
  });

  it('does not let a cancelled create tear down the create that replaced it', async () => {
    const releaseFirst = deferNativeCreate();

    const first = createServer(SERVICES);
    stopServer();
    const second = createServer(SERVICES);

    await expect(second).resolves.toBeUndefined();
    releaseFirst();
    await expect(first).rejects.toThrow(/cancelled by a stopServer/);

    // Only the application's own stop: the abandoned create must not issue a compensating one, because the
    // server it would take down belongs to the create that succeeded after it.
    expect(nativeModuleMock.stopServer).toHaveBeenCalledTimes(1);
  });

  /**
   * A create that never reached the native side replaced nothing, so it must not take ownership away from
   * the create that did. Claiming the generation on entry rather than at the hand-over meant a rejected
   * configuration silently disarmed the compensating stop — and the in-flight create then rejected saying
   * no database was published while one was, on Android for good, since it carries no epoch of its own.
   */
  it('does not let a create rejected by validation disarm the create in flight', async () => {
    const release = deferNativeCreate();

    const first = createServer(SERVICES);
    stopServer();
    await expect(createServer([{ uuid: 'not-a-uuid', characteristics: [] }])).rejects.toThrow(
      /Invalid service UUID/,
    );
    release();

    await expect(first).rejects.toThrow(/cancelled by a stopServer/);
    // Twice, exactly as though the rejected create had never been made.
    expect(nativeModuleMock.stopServer).toHaveBeenCalledTimes(2);
  });

  /**
   * A native create that fails may still have published part of a database before it did, and the stop the
   * application issued in the meantime has to reach it either way. Running the compensation only on the
   * success path meant a create that was both cancelled *and* rejected compensated for neither.
   */
  it('still stops the server when a cancelled create rejects natively', async () => {
    let reject: (error: Error) => void = () => {};
    nativeModuleMock.createServer.mockImplementationOnce(
      () =>
        new Promise<undefined>((_resolve, rejectPromise) => {
          reject = (error) => rejectPromise(error);
        }),
    );

    const create = createServer(SERVICES);
    stopServer();
    reject(new Error('native failure'));

    // The native failure is what the caller is told about — it is the more specific of the two.
    await expect(create).rejects.toThrow(/native failure/);
    expect(nativeModuleMock.stopServer).toHaveBeenCalledTimes(2);
  });

  /**
   * Both natives stop advertising as part of accepting a new server, so an advertising start still in
   * flight has had the radio taken from under it. Without this it resolved successfully while
   * `isAdvertising` reported false.
   */
  it('cancels an advertising start still in flight', async () => {
    let release: () => void = () => {};
    nativeModuleMock.startAdvertising.mockImplementationOnce(
      () =>
        new Promise<undefined>((resolve) => {
          release = () => resolve(undefined);
        }),
    );

    const advertise = startAdvertising({});
    await createServer(SERVICES);
    release();

    await expect(advertise).rejects.toThrow(/cancelled by a stopAdvertising/);
  });
});
