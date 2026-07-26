import { startAdvertising, stopAdvertising, stopServer } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

/**
 * A `stopAdvertising` issued while a `startAdvertising` is still in flight must win, and the ordering it
 * would need does not exist natively: `stopAdvertising` is a synchronous Expo `Function`, so its body runs
 * on the JavaScript thread immediately, while `startAdvertising` is an `AsyncFunction` whose body runs
 * later on Expo's worker queue. A stop issued second can therefore reach the manager first and be taken as
 * the *baseline* by the start behind it, which then passes its own generation check and puts the radio on
 * the air after the application asked for the opposite.
 *
 * JavaScript is single-threaded, so the call order here *is* the application's intent, and the shared
 * layer is the only place that knows it. These tests pin that it is carried across; the native ordering
 * itself cannot be exercised from the host.
 */

/**
 * Holds the native `startAdvertising` in flight until the returned function is called, so a stop can be
 * issued in between — the interleaving the guard exists for.
 */
function deferNativeStart(): () => void {
  let release: () => void = () => {};
  nativeModuleMock.startAdvertising.mockImplementationOnce(
    () =>
      new Promise<undefined>((resolve) => {
        release = () => resolve(undefined);
      }),
  );
  return () => release();
}

// The epoch counter behind this is module-level and monotonic, so it needs no resetting: every start reads
// it afresh and only ever compares it against itself. Only the mocks carry state worth clearing.
beforeEach(() => {
  jest.clearAllMocks();
  nativeModuleMock.startAdvertising.mockImplementation(async () => undefined);
});

describe('a stop issued while a start is in flight', () => {
  it('rejects the start and stops the advertisement again', async () => {
    const release = deferNativeStart();

    const start = startAdvertising({ localName: 'Harness' });
    stopAdvertising();
    release();

    await expect(start).rejects.toThrow(/cancelled by a stopAdvertising/);
    // Twice: once from the application's own call, once from the guard — because whether the first one
    // reached the manager before the start did is exactly what is not knowable from here.
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(2);
  });

  it('reports the code a native cancellation reports, so the two need not be told apart', async () => {
    const release = deferNativeStart();

    const start = startAdvertising();
    stopAdvertising();
    release();

    await expect(start).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
  });

  /** `stopServer` stops advertising along with the server, so it has to cancel a pending start too. */
  it('is cancelled by stopServer as well', async () => {
    const release = deferNativeStart();

    const start = startAdvertising();
    stopServer();
    release();

    await expect(start).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
  });

  /** One stop means one thing, whichever of several in-flight starts it arrives against. */
  it('cancels every start that was in flight', async () => {
    const releaseFirst = deferNativeStart();
    const releaseSecond = deferNativeStart();

    const first = startAdvertising({ localName: 'a' });
    const second = startAdvertising({ localName: 'b' });
    stopAdvertising();
    releaseFirst();
    releaseSecond();

    await expect(first).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
    await expect(second).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
  });
});

/**
 * The compensating stop is not addressed to a particular advertisement — `stopAdvertising` takes no
 * argument and stops whatever is on the air. So a cancelled start issuing one after a *later* start has
 * already taken over silently undoes work the application never asked to undo.
 */
describe('a start that was replaced before its cancellation could compensate', () => {
  it('leaves the newer advertisement on the air', async () => {
    const releaseFirst = deferNativeStart();

    const first = startAdvertising({ localName: 'first' });
    stopAdvertising();
    // Issued after the stop, so this one is not cancelled by it and resolves normally.
    await expect(startAdvertising({ localName: 'second' })).resolves.toBeUndefined();

    // Only now does the abandoned first start come back from the native side.
    releaseFirst();
    await expect(first).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });

    // Once, for the application's own stopAdvertising. A second call here would be the first start
    // taking the second one off the air after it had already resolved.
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(1);
  });

  /** The newest start still compensates for itself when the stop was aimed at it. */
  it('still compensates when it is the most recent start', async () => {
    const releaseFirst = deferNativeStart();
    const releaseSecond = deferNativeStart();

    const first = startAdvertising({ localName: 'first' });
    const second = startAdvertising({ localName: 'second' });
    stopAdvertising();
    releaseFirst();
    releaseSecond();

    await expect(first).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
    await expect(second).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
    // The application's own stop, plus one from the newest start. The superseded start does not add a
    // third: it no longer owns the radio.
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(2);
  });

  /**
   * A start that never reached the radio replaced nothing. Claiming the generation on entry rather than
   * at the hand-over meant a rejected configuration took ownership from the start genuinely in flight,
   * which then rejected saying nothing was on the air while the radio was still advertising.
   */
  it('is not replaced by a start that its own validation rejected', async () => {
    const releaseFirst = deferNativeStart();

    const first = startAdvertising({ localName: 'first' });
    stopAdvertising();
    await expect(startAdvertising({ timeoutMs: -1 })).rejects.toThrow(
      /Invalid advertising timeout/,
    );
    releaseFirst();

    await expect(first).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
    // Twice, exactly as though the rejected start had never been made.
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(2);
  });

  /** The same claim, released on the failure path, so a cancelled start still compensates when it fails. */
  it('still stops the advertisement when a cancelled start rejects natively', async () => {
    let reject: (error: Error) => void = () => {};
    nativeModuleMock.startAdvertising.mockImplementationOnce(
      () =>
        new Promise<undefined>((_resolve, rejectPromise) => {
          reject = (error) => rejectPromise(error);
        }),
    );

    const start = startAdvertising({ localName: 'first' });
    stopAdvertising();
    reject(new Error('native failure'));

    await expect(start).rejects.toThrow(/native failure/);
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(2);
  });
});

describe('a start with no stop against it', () => {
  it('resolves, and does not stop the advertisement it just started', async () => {
    await expect(startAdvertising({ localName: 'Harness' })).resolves.toBeUndefined();

    expect(nativeModuleMock.stopAdvertising).not.toHaveBeenCalled();
  });

  /**
   * A stop issued *after* a start has already settled is an ordinary stop, not a cancellation. Without
   * capturing the epoch per call, a single earlier stop would reject every later start forever.
   */
  it('leaves a later start unaffected by an earlier stop', async () => {
    await startAdvertising({ localName: 'first' });
    stopAdvertising();

    await expect(startAdvertising({ localName: 'second' })).resolves.toBeUndefined();
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(1);
  });
});
