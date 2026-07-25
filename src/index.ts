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
  BluetoothState,
  BluetoothStateChangedEvent,
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
  type BluetoothState,
  type BluetoothStateChangedEvent,
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

function assertValidBytes(value: unknown, field: string): void {
  if (!Array.isArray(value)) {
    throw new Error(
      `Invalid ${field} value ${JSON.stringify(value)}. Expected an array of byte values.`,
    );
  }
  for (const [index, byte] of value.entries()) {
    if (!Number.isInteger(byte) || byte < 0 || byte > 255) {
      throw new Error(
        `Invalid ${field} byte ${JSON.stringify(byte)} at index ${index}. ` +
          'Every element must be an integer between 0 and 255.',
      );
    }
  }
}

export async function createServer(services: GattServiceConfig[]): Promise<void> {
  for (const service of services ?? []) {
    assertValidUuid(service?.uuid, 'service');
    for (const characteristic of service?.characteristics ?? []) {
      assertValidUuid(characteristic?.uuid, 'characteristic');
      if (characteristic.value !== undefined) {
        assertValidBytes(characteristic.value, 'characteristic');
      }
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
  assertValidBytes(value, 'notification');
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
  assertValidBytes(value, 'response');
  return ExpoGattServerModule.sendResponse(deviceId, requestId, status, offset, value);
}

export function updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
): void {
  assertValidUuid(serviceUuid, 'service');
  assertValidUuid(characteristicUuid, 'characteristic');
  assertValidBytes(value, 'characteristic');
  ExpoGattServerModule.updateCharacteristicValue(serviceUuid, characteristicUuid, value);
}

export function stopServer(): void {
  ExpoGattServerModule.stopServer();
}

/**
 * Reads the current Bluetooth adapter state. Safe to call before `createServer`, though iOS
 * cannot report anything more specific than `unknown` or `unauthorized` until a server exists,
 * because `CBPeripheralManager.state` requires an instantiated manager.
 */
export async function getBluetoothState(): Promise<BluetoothState> {
  return ExpoGattServerModule.getBluetoothState();
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

/**
 * Fires whenever the Bluetooth adapter state changes. Delivered only while a server exists,
 * since state monitoring is tied to the server lifecycle on both platforms.
 */
export function addBluetoothStateChangedListener(
  listener: (event: BluetoothStateChangedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onBluetoothStateChanged', listener);
}
