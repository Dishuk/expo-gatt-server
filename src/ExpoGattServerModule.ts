import { requireOptionalNativeModule, NativeModule } from 'expo';

import type {
  GattServiceConfig,
  CreateServerOptions,
  AdvertiseConfig,
  BluetoothState,
  ConnectedDevice,
  DeviceMtu,
  GattServerEvents,
} from './ExpoGattServer.types';

declare class ExpoGattServerModuleType extends NativeModule<GattServerEvents> {
  createServer(services: GattServiceConfig[], options: CreateServerOptions): Promise<void>;
  startAdvertising(config: AdvertiseConfig): Promise<void>;
  stopAdvertising(): void;
  sendNotification(
    deviceId: string,
    serviceUuid: string,
    characteristicUuid: string,
    value: number[],
    confirm: boolean,
    requireSubscription: boolean,
  ): Promise<void>;
  sendResponse(
    deviceId: string,
    requestId: number,
    status: number,
    offset: number,
    value: number[],
  ): Promise<void>;
  updateCharacteristicValue(
    serviceUuid: string,
    characteristicUuid: string,
    value: number[],
  ): Promise<void>;
  stopServer(): void;
  getBluetoothState(): Promise<BluetoothState>;
  getMtu(deviceId: string): Promise<DeviceMtu>;
  getConnectedDevices(): Promise<ConnectedDevice[]>;
  disconnectDevice(deviceId: string): Promise<void>;
  isServerRunning(): Promise<boolean>;
  isAdvertising(): Promise<boolean>;
}

export type { ExpoGattServerModuleType };

/**
 * `null` wherever the native module was never installed — on web, and in a binary that does not
 * contain it, such as Expo Go.
 *
 * `requireNativeModule` throws from this line, which runs at *import* time, so merely importing this
 * package used to take down any bundle that reached the import — including one that only ever calls
 * into it behind a platform check. The optional variant defers that to the call, where the public
 * API can report it properly.
 */
export default requireOptionalNativeModule<ExpoGattServerModuleType>('ExpoGattServer');
