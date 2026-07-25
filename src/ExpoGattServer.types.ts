/**
 * Per-characteristic opt-in delegation of ATT request handling to JavaScript.
 *
 * Every flag defaults to `false`, which keeps the module's automatic behaviour, so an existing
 * configuration behaves exactly as it did before this option existed.
 */
export interface CharacteristicDelegateConfig {
  /**
   * Do not acknowledge writes automatically. `onCharacteristicWriteRequest` is emitted with a
   * live `requestId` and the write stays unanswered until `sendResponse` is called with
   * `GATT_SUCCESS` or one of the `ATT_ERROR_*` codes — the only way to reject a write.
   *
   * On iOS a single write callback can carry several requests. Apple requires exactly one
   * response per callback, taken from the first request of the batch, and documents the batch as
   * all-or-nothing, so every event produced by one batch shares a single `requestId` and the
   * first `sendResponse` for that id answers the whole batch.
   */
  write?: boolean;
}

export interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  value?: number[];
  /** Opt out of the module's automatic responses for this characteristic. */
  delegate?: CharacteristicDelegateConfig;
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
  /**
   * `true` when the module is waiting for JavaScript to answer this request with `sendResponse`.
   * Only ever `true` for a characteristic configured with `delegate.write` whose write actually
   * carries a response; the module answers every other write itself before emitting the event.
   */
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
