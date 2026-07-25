import { sendNotification, updateCharacteristicValue } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

const DEVICE = 'AA:BB:CC:DD:EE:FF';
const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';
const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';

/**
 * **Not a check that a notification leaves the stored value alone** — that separation lives in the
 * native layers, which are mocked out here, so nothing in this file could observe it being broken.
 *
 * What it pins is narrower and entirely a property of the JavaScript wrapper: that `sendNotification`
 * and `updateCharacteristicValue` stay one native call each. Undoing the separation from here would
 * mean the wrapper quietly issuing the second call for the caller, or growing an option that asks it
 * to, and that is what these assertions would catch.
 */
describe('sendNotification and updateCharacteristicValue as separate wrapper calls', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('issues no updateCharacteristicValue call of its own when a notification is sent', async () => {
    await sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [1, 2, 3]);

    expect(nativeModuleMock.sendNotification).toHaveBeenCalledTimes(1);
    expect(nativeModuleMock.updateCharacteristicValue).not.toHaveBeenCalled();
  });

  it('issues no sendNotification call of its own when the stored value is updated', async () => {
    await updateCharacteristicValue(SERVICE, CHARACTERISTIC, [1, 2, 3]);

    expect(nativeModuleMock.updateCharacteristicValue).toHaveBeenCalledTimes(1);
    expect(nativeModuleMock.sendNotification).not.toHaveBeenCalled();
  });

  it('makes exactly one native call each when a value is pushed and stored', async () => {
    await updateCharacteristicValue(SERVICE, CHARACTERISTIC, [7]);
    await sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [7]);

    expect(nativeModuleMock.updateCharacteristicValue).toHaveBeenCalledTimes(1);
    expect(nativeModuleMock.sendNotification).toHaveBeenCalledTimes(1);
  });

  it('forwards exactly the six documented arguments, so no value-mirroring flag is smuggled in', async () => {
    await sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [1], true, {
      requireSubscription: false,
    });

    expect(nativeModuleMock.sendNotification).toHaveBeenCalledWith(
      DEVICE,
      SERVICE,
      CHARACTERISTIC,
      [1],
      true,
      false,
    );
  });

  it('drops an unrecognised option instead of forwarding it', async () => {
    await sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [1], false, {
      // An option the module does not define must not reach the native layer, where it could be read
      // as a mirroring opt-in that no platform implements.
      updateValue: true,
    } as never);

    const call = nativeModuleMock.sendNotification.mock.calls[0];
    expect(call).toHaveLength(6);
    expect(call).toEqual([DEVICE, SERVICE, CHARACTERISTIC, [1], false, true]);
  });
});
