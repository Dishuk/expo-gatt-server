import type { EventSubscription } from 'expo-modules-core';

import ExpoGattServerModule from './ExpoGattServerModule';
import type {
  GattServiceConfig,
  AdvertiseConfig,
  SendNotificationOptions,
  DeviceConnectedEvent,
  DeviceDisconnectedEvent,
  CharacteristicReadRequestEvent,
  CharacteristicWriteRequestEvent,
  NotificationSentEvent,
  CharacteristicSubscribedEvent,
  CharacteristicUnsubscribedEvent,
  BluetoothState,
  BluetoothStateChangedEvent,
  DeviceMtu,
  MtuChangedEvent,
} from './ExpoGattServer.types';

export type { EventSubscription };

export {
  type GattServiceConfig,
  type GattCharacteristicConfig,
  type CharacteristicDelegateConfig,
  type AdvertiseConfig,
  type SendNotificationOptions,
  type CharacteristicProperty,
  type CharacteristicPermission,
  type DeviceConnectedEvent,
  type DeviceDisconnectedEvent,
  type CharacteristicReadRequestEvent,
  type CharacteristicWriteRequestEvent,
  type NotificationSentEvent,
  type CharacteristicSubscribedEvent,
  type CharacteristicUnsubscribedEvent,
  type BluetoothState,
  type BluetoothStateChangedEvent,
  type DeviceMtu,
  type MtuChangedEvent,
  type GattServerEvents,
  GATT_SUCCESS,
  ATT_ERROR_INVALID_HANDLE,
  ATT_ERROR_READ_NOT_PERMITTED,
  ATT_ERROR_WRITE_NOT_PERMITTED,
  ATT_ERROR_INVALID_PDU,
  ATT_ERROR_INSUFFICIENT_AUTHENTICATION,
  ATT_ERROR_REQUEST_NOT_SUPPORTED,
  ATT_ERROR_INVALID_OFFSET,
  ATT_ERROR_INSUFFICIENT_AUTHORIZATION,
  ATT_ERROR_PREPARE_QUEUE_FULL,
  ATT_ERROR_ATTRIBUTE_NOT_FOUND,
  ATT_ERROR_ATTRIBUTE_NOT_LONG,
  ATT_ERROR_INSUFFICIENT_ENCRYPTION_KEY_SIZE,
  ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH,
  ATT_ERROR_UNLIKELY_ERROR,
  ATT_ERROR_INSUFFICIENT_ENCRYPTION,
  ATT_ERROR_UNSUPPORTED_GROUP_TYPE,
  ATT_ERROR_INSUFFICIENT_RESOURCES,
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

/**
 * Sends a notification (or, with `confirm`, an indication) to a connected central.
 *
 * The promise settles when the platform reports the notification as delivered, not when the call
 * reaches the Bluetooth stack. A device may only have one notification outstanding at a time, so
 * sends issued while an earlier one is still in flight are queued in order rather than dropped;
 * awaiting the promise is what paces a stream against the link.
 *
 * Rejects with `ERR_NO_SUBSCRIBER` when the device has not enabled notifications or indications on
 * the characteristic — a notification to nobody is a failure, not a success. See
 * `options.requireSubscription` to send anyway where the platform allows it.
 */
export async function sendNotification(
  deviceId: string,
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
  confirm: boolean = false,
  options: SendNotificationOptions = {},
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
    options.requireSubscription ?? true,
  );
}

/**
 * Answers a pending read or write request.
 *
 * `offset` states where `value` begins within the attribute, and the response is rebased onto the
 * offset the request actually asked for. Passing `offset: 0` with the whole value therefore answers
 * a Read Blob continuation correctly, and passing the request event's own `offset` with an
 * already-sliced value works too. Both platforms honour this identically.
 *
 * Rejects with `REQUEST_NOT_FOUND` when the request is unknown or already answered,
 * `REQUEST_DEVICE_MISMATCH` when the request belongs to a different device, and
 * `ERR_RESPONSE_OFFSET` when `offset` is past the offset the request asked for, which would leave
 * the requested bytes missing.
 *
 * `value` is not size-checked against the MTU: a read response longer than one PDU can carry is
 * normal ATT, and the central continues it with a Read Blob request.
 */
export async function sendResponse(
  deviceId: string,
  requestId: number,
  status: number,
  offset: number,
  value: number[],
): Promise<void> {
  // An ATT error code is a single octet, and Android narrows the status to a byte on its way into
  // the Bluetooth stack, so a wider value would be truncated into an unrelated error rather than
  // rejected. Catch it here instead.
  if (!Number.isInteger(status) || status < 0 || status > 255) {
    throw new Error(
      `Invalid response status ${JSON.stringify(status)}. An ATT error code is a single byte, ` +
        'so it must be an integer between 0 and 255.',
    );
  }
  // An ATT offset is an unsigned 16-bit value, and a negative one would be rebased into a slice
  // beyond the value's end on both platforms rather than reported.
  if (!Number.isInteger(offset) || offset < 0 || offset > 0xffff) {
    throw new Error(
      `Invalid response offset ${JSON.stringify(offset)}. An ATT offset is an unsigned 16-bit ` +
        'value, so it must be an integer between 0 and 65535.',
    );
  }
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

/**
 * Fires once per connected central, independently of any subscription.
 *
 * Android reports the connection itself, via `onConnectionStateChange`. iOS has no equivalent —
 * `CBPeripheralManagerDelegate` declares no connection-level callback — so a central is reported on
 * its first ATT activity instead: a subscribe, a read request or a write request. A central that
 * connects and never touches an attribute is not observable from the peripheral role at all.
 */
/**
 * Reads the current ATT MTU for a connected device, so payloads can be sized before they are sent.
 *
 * Rejects with `ERR_DEVICE_DISCONNECTED` when the device is not connected, and with
 * `ERR_NO_SERVER` when no server exists. A device that has not negotiated an MTU reports the
 * specification default of 23 rather than failing — that default is what the link carries until a
 * negotiation happens.
 */
export async function getMtu(deviceId: string): Promise<DeviceMtu> {
  return ExpoGattServerModule.getMtu(deviceId);
}

/**
 * Fires when a connection's MTU changes. See `MtuChangedEvent` for the difference in timing between
 * Android, which reports the change as it happens, and iOS, which can only sample the value when
 * the central next produces activity.
 */
export function addMtuChangedListener(
  listener: (event: MtuChangedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onMtuChanged', listener);
}

export function addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onDeviceConnected', listener);
}

/**
 * Fires once when a central goes away.
 *
 * Android reports the disconnection itself. On iOS it is inferred, because CoreBluetooth never
 * reports one: losing the last subscription is treated as a disconnection, and every known central
 * is reported as disconnected when Bluetooth leaves `poweredOn`. A central that only ever read or
 * wrote therefore may not produce this event until Bluetooth is turned off or the server stops, and
 * a central that deliberately unsubscribes but stays connected produces it early — CoreBluetooth
 * delivers the same callback for both and offers nothing to tell them apart.
 */
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
 * Fires when a central enables notifications or indications on a characteristic. This is the
 * signal to start streaming: before it arrives the central receives nothing, and on Android
 * `sendNotification` has no subscriber to send to.
 */
export function addCharacteristicSubscribedListener(
  listener: (event: CharacteristicSubscribedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onCharacteristicSubscribed', listener);
}

/**
 * Fires when a central stops receiving updates for a characteristic, including when it
 * disconnects while still subscribed. This is the signal to stop streaming.
 */
export function addCharacteristicUnsubscribedListener(
  listener: (event: CharacteristicUnsubscribedEvent) => void,
): EventSubscription {
  return ExpoGattServerModule.addListener('onCharacteristicUnsubscribed', listener);
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
