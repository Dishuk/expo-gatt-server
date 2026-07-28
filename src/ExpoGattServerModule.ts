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
 * null if native module not installed. Optional variant required at import time—requireNativeModule would throw.
 */
export default requireOptionalNativeModule<ExpoGattServerModuleType>('ExpoGattServer');
