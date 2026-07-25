import { requireNativeModule, NativeModule } from 'expo';

import type {
  GattServiceConfig,
  AdvertiseConfig,
  BluetoothState,
  DeviceMtu,
  GattServerEvents,
} from './ExpoGattServer.types';

declare class ExpoGattServerModuleType extends NativeModule<GattServerEvents> {
  createServer(services: GattServiceConfig[]): Promise<void>;
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
  ): void;
  stopServer(): void;
  getBluetoothState(): Promise<BluetoothState>;
  getMtu(deviceId: string): Promise<DeviceMtu>;
}

export default requireNativeModule<ExpoGattServerModuleType>('ExpoGattServer');
