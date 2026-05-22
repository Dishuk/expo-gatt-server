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

export type GattServerEvents = {
  onDeviceConnected(event: DeviceConnectedEvent): void;
  onDeviceDisconnected(event: DeviceDisconnectedEvent): void;
  onCharacteristicReadRequest(event: CharacteristicReadRequestEvent): void;
  onCharacteristicWriteRequest(event: CharacteristicWriteRequestEvent): void;
  onNotificationSent(event: NotificationSentEvent): void;
};

export const GATT_SUCCESS = 0;
export const GATT_FAILURE = 257;
