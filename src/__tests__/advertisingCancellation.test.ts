import { startAdvertising, stopAdvertising, stopServer } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

// stopAdvertising is synchronous and startAdvertising is async, so a stop issued while a start is
// still in flight can reach the native side first. The shared JS layer enforces call order so that
// a later stop always wins, since the native ordering cannot guarantee it.

/** Holds native startAdvertising in flight until released, so a stop can be issued in between. */
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
    // Twice: once from the app's own stop, once from the guard's compensating stop.
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(2);
  });

  /** stopServer stops advertising along with the server, so it has to cancel a pending start too. */
  it('is cancelled by stopServer as well', async () => {
    const release = deferNativeStart();

    const start = startAdvertising();
    stopServer();
    release();

    await expect(start).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });
  });
});

// The compensating stop targets whatever is on the air, not a particular advertisement. A cancelled
// start must not issue one after a later start has already taken over.
describe('a start that was replaced before its cancellation could compensate', () => {
  it('leaves the newer advertisement on the air', async () => {
    const releaseFirst = deferNativeStart();

    const first = startAdvertising({ localName: 'first' });
    stopAdvertising();
    // Issued after the stop, so this one is not cancelled by it and resolves normally.
    await expect(startAdvertising({ localName: 'second' })).resolves.toBeUndefined();

    releaseFirst();
    await expect(first).rejects.toMatchObject({ code: 'ERR_ADVERTISE' });

    // Once, for the app's own stopAdvertising — a second call would mean the first start took the
    // second one off the air after it had already resolved.
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
    // The app's own stop, plus one from the newest start. The superseded start adds no third: it no
    // longer owns the radio.
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(2);
  });

  // A start that never reached the radio replaced nothing, so it must not take ownership from the
  // start genuinely in flight.
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

  /** A cancelled start still compensates when it also fails natively. */
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

  /** A stop issued after a start has already settled is an ordinary stop, not a cancellation. */
  it('leaves a later start unaffected by an earlier stop', async () => {
    await startAdvertising({ localName: 'first' });
    stopAdvertising();

    await expect(startAdvertising({ localName: 'second' })).resolves.toBeUndefined();
    expect(nativeModuleMock.stopAdvertising).toHaveBeenCalledTimes(1);
  });
});
