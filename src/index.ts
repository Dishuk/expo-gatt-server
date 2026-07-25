import type { EventSubscription } from 'expo-modules-core';

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

export type { EventSubscription };

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

// Accepted by CBUUID(string:) on iOS: 16-bit (4 hex digits), 32-bit (8 hex digits) or the
// hyphenated 128-bit form. Anything else raises an uncatchable ObjC exception natively.
const SHORT_UUID_RE = /^(?:[0-9a-fA-F]{4}|[0-9a-fA-F]{8})$/;
const LONG_UUID_RE =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function assertValidUuid(uuid: unknown, field: string): void {
  if (typeof uuid !== 'string' || (!SHORT_UUID_RE.test(uuid) && !LONG_UUID_RE.test(uuid))) {
    throw new Error(
      `Invalid ${field} UUID ${JSON.stringify(uuid)}. Expected 4 hex digits (16-bit), ` +
        '8 hex digits (32-bit) or the hyphenated 8-4-4-4-12 form (128-bit).',
    );
  }
}

export async function createServer(services: GattServiceConfig[]): Promise<void> {
  for (const service of services ?? []) {
    assertValidUuid(service?.uuid, 'service');
    for (const characteristic of service?.characteristics ?? []) {
      assertValidUuid(characteristic?.uuid, 'characteristic');
    }
  }
  return ExpoGattServerModule.createServer(services);
}

export async function startAdvertising(config: AdvertiseConfig = {}): Promise<void> {
  for (const uuid of config.serviceUuids ?? []) {
    assertValidUuid(uuid, 'service');
  }
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
  assertValidUuid(serviceUuid, 'service');
  assertValidUuid(characteristicUuid, 'characteristic');
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
  assertValidUuid(serviceUuid, 'service');
  assertValidUuid(characteristicUuid, 'characteristic');
  ExpoGattServerModule.updateCharacteristicValue(serviceUuid, characteristicUuid, value);
}

export function stopServer(): void {
  ExpoGattServerModule.stopServer();
}

export function addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onDeviceConnected', listener);
}

export function addDeviceDisconnectedListener(
  listener: (event: DeviceDisconnectedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onDeviceDisconnected', listener);
}

export function addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onCharacteristicReadRequest', listener);
}

export function addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onCharacteristicWriteRequest', listener);
}

export function addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onNotificationSent', listener);
}
