import { createServer, startAdvertising, stopServer, type GattServiceConfig } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

// A stopServer issued while a createServer is still in flight must win — the same hazard
// advertisingCancellation.test.ts pins for advertising. stopServer is synchronous and createServer
// is async, so a stop issued second can reach the native side first and the create behind it
// publishes the database anyway. iOS's native serverStopEpoch does not catch this (it is read after
// the JS call has returned), and Android has no equivalent, so the shared JS layer enforces it.

const SERVICES: GattServiceConfig[] = [
  {
    uuid: '180d',
    characteristics: [{ uuid: '2a37', properties: ['read'], permissions: ['readable'] }],
  },
];

/** Holds native createServer in flight until released, so a stop can be issued in between. */
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
    // Twice: once from the app's own stop, once from the guard's compensating stop.
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

    // Only the app's own stop: the abandoned create must not issue a compensating one, since the
    // server it would take down belongs to the create that succeeded after it.
    expect(nativeModuleMock.stopServer).toHaveBeenCalledTimes(1);
  });

  // A create that never reached the native side replaced nothing, so it must not take ownership
  // away from the create that did.
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

  // A native create that fails may still have published part of a database first, so the
  // compensating stop has to run on the failure path too, not just on success.
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

  // Both natives stop advertising as part of accepting a new server, so an advertising start still
  // in flight has had the radio taken from under it.
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

  // The compensating stop takes the radio with the server, exactly as the app's own stopServer
  // does, so it must also count as a stop of the advertisement.
  it('counts its compensating stop as a stop of the advertisement too', async () => {
    const releaseCreate = deferNativeCreate();
    let releaseAdvertise: () => void = () => {};
    nativeModuleMock.startAdvertising.mockImplementationOnce(
      () =>
        new Promise<undefined>((resolve) => {
          releaseAdvertise = () => resolve(undefined);
        }),
    );

    const create = createServer(SERVICES);
    stopServer();
    // Issued after the stop, so it reads the stopped state as its own baseline.
    const advertise = startAdvertising({});
    releaseCreate();

    await expect(create).rejects.toThrow(/cancelled by a stopServer/);
    releaseAdvertise();

    await expect(advertise).rejects.toThrow(/cancelled by a stopAdvertising/);
  });

  // A create the native side refused took no radio, so it owes an in-flight start nothing.
  it('leaves an in-flight advertisement alone when the create is rejected natively', async () => {
    let releaseAdvertise: () => void = () => {};
    nativeModuleMock.startAdvertising.mockImplementationOnce(
      () =>
        new Promise<undefined>((resolve) => {
          releaseAdvertise = () => resolve(undefined);
        }),
    );
    nativeModuleMock.createServer.mockImplementationOnce(async () => {
      throw Object.assign(new Error('Bluetooth permission denied'), { code: 'ERR_PERMISSION' });
    });

    const advertise = startAdvertising({});
    await expect(createServer(SERVICES)).rejects.toMatchObject({ code: 'ERR_PERMISSION' });
    releaseAdvertise();

    await expect(advertise).resolves.toBeUndefined();
    expect(nativeModuleMock.stopAdvertising).not.toHaveBeenCalled();
  });
});
