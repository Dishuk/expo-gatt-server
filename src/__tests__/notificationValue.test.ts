import { sendNotification, updateCharacteristicValue } from '../index';
import { nativeModuleMock } from './nativeModuleMock';

jest.mock('../ExpoGattServerModule', () => ({
  __esModule: true,
  default: require('./nativeModuleMock').nativeModuleMock,
}));

const DEVICE = 'AA:BB:CC:DD:EE:FF';
const CHARACTERISTIC = '00002a37-0000-1000-8000-00805f9b34fb';
const SERVICE = '0000180d-0000-1000-8000-00805f9b34fb';

// Pins that `sendNotification` and `updateCharacteristicValue` each issue exactly one native call and
// never the other's. The native-side separation of value vs notification is not exercised here — the
// native module is mocked out.
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

  // `toHaveBeenCalledWith` is exact on arity, so this also pins that no extra argument rides along.
  it('forwards exactly the six documented arguments, so no value-mirroring flag is smuggled in', async () => {
    await sendNotification(DEVICE, SERVICE, CHARACTERISTIC, [1], {
      confirm: true,
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
});
