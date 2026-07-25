export interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  value?: number[];
}

export type CharacteristicProperty = 'read' | 'write' | 'writeNoResponse' | 'notify' | 'indicate';
export type CharacteristicPermission = 'readable' | 'writeable';

export interface GattServiceConfig {
  uuid: string;
  characteristics: GattCharacteristicConfig[];
}

export interface AdvertiseConfig {
  localName?: string;
  serviceUuids?: string[];
  includeTxPowerLevel?: boolean;
  connectable?: boolean;
}

export interface DeviceConnectedEvent {
  deviceId: string;
  name?: string;
}

export interface DeviceDisconnectedEvent {
  deviceId: string;
}

export interface CharacteristicReadRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
}

export interface CharacteristicWriteRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
  value: number[];
  responseNeeded: boolean;
}

export interface NotificationSentEvent {
  deviceId: string;
  characteristicUuid: string;
  status: number;
}

/**
 * Bluetooth adapter state, normalised so consumers never have to branch on platform.
 *
 * - `poweredOn` — adapter is on and usable. The only state in which a server can advertise.
 * - `poweredOff` — adapter is off. iOS `CBManagerState.poweredOff`, Android `STATE_OFF`.
 * - `resetting` — transient; a further state change is coming, so do not act yet. iOS
 *   `CBManagerState.resetting`, Android `STATE_TURNING_ON` / `STATE_TURNING_OFF` (both of which
 *   the platform documents as "not yet usable").
 * - `unsupported` — this device has no BLE peripheral support. iOS `CBManagerState.unsupported`,
 *   Android: no `BluetoothAdapter`.
 * - `unauthorized` — the app may not use Bluetooth. iOS `CBManagerState.unauthorized`.
 * - `unknown` — not determined yet. iOS reports this until the first state callback arrives.
 *
 * Note that `poweredOff` destroys the published GATT database on both platforms; the module
 * re-publishes services automatically on the next transition to `poweredOn`, but advertising
 * must be restarted by the consumer.
 */
export type BluetoothState =
  | 'unknown'
  | 'resetting'
  | 'unsupported'
  | 'unauthorized'
  | 'poweredOff'
  | 'poweredOn';

export interface BluetoothStateChangedEvent {
  state: BluetoothState;
}

export type GattServerEvents = {
  onDeviceConnected(event: DeviceConnectedEvent): void;
  onDeviceDisconnected(event: DeviceDisconnectedEvent): void;
  onCharacteristicReadRequest(event: CharacteristicReadRequestEvent): void;
  onCharacteristicWriteRequest(event: CharacteristicWriteRequestEvent): void;
  onNotificationSent(event: NotificationSentEvent): void;
  onBluetoothStateChanged(event: BluetoothStateChangedEvent): void;
};

export const GATT_SUCCESS = 0;
export const GATT_FAILURE = 257;
