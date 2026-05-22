import ExpoGattServerModule from './ExpoGattServerModule';
import type {
  GattServiceConfig,
  AdvertiseConfig,
  DeviceConnectedEvent,
  DeviceDisconnectedEvent,
  CharacteristicReadRequestEvent,
  CharacteristicWriteRequestEvent,
  NotificationSentEvent,
} from './ExpoGattServer.types';

export {
  type GattServiceConfig,
  type GattCharacteristicConfig,
  type AdvertiseConfig,
  type CharacteristicProperty,
  type CharacteristicPermission,
  type DeviceConnectedEvent,
  type DeviceDisconnectedEvent,
  type CharacteristicReadRequestEvent,
  type CharacteristicWriteRequestEvent,
  type NotificationSentEvent,
  type GattServerEvents,
  GATT_SUCCESS,
  GATT_FAILURE,
} from './ExpoGattServer.types';

export async function createServer(services: GattServiceConfig[]): Promise<void> {
  return ExpoGattServerModule.createServer(services);
}

export async function startAdvertising(config: AdvertiseConfig = {}): Promise<void> {
  return ExpoGattServerModule.startAdvertising(config);
}

export function stopAdvertising(): void {
  ExpoGattServerModule.stopAdvertising();
}

export async function sendNotification(
  deviceId: string,
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
  confirm: boolean = false,
): Promise<void> {
  return ExpoGattServerModule.sendNotification(
    deviceId,
    serviceUuid,
    characteristicUuid,
    value,
    confirm,
  );
}

export async function sendResponse(
  deviceId: string,
  requestId: number,
  status: number,
  offset: number,
  value: number[],
): Promise<void> {
  return ExpoGattServerModule.sendResponse(deviceId, requestId, status, offset, value);
}

export function updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
): void {
  ExpoGattServerModule.updateCharacteristicValue(serviceUuid, characteristicUuid, value);
}

export function stopServer(): void {
  ExpoGattServerModule.stopServer();
}

export function addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
) {
  return ExpoGattServerModule.addListener('onDeviceConnected', listener);
}

export function addDeviceDisconnectedListener(
  listener: (event: DeviceDisconnectedEvent) => void,
) {
  return ExpoGattServerModule.addListener('onDeviceDisconnected', listener);
}

export function addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
) {
  return ExpoGattServerModule.addListener('onCharacteristicReadRequest', listener);
}

export function addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
) {
  return ExpoGattServerModule.addListener('onCharacteristicWriteRequest', listener);
}

export function addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
) {
  return ExpoGattServerModule.addListener('onNotificationSent', listener);
}
