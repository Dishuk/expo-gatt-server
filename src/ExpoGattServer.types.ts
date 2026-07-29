/**
 * Bytes accepted anywhere this module takes a value. A `Uint8Array` is converted to `number[]` at
 * the boundary, since neither native bridge marshals typed arrays.
 *
 * Event payloads always come back as `number[]`.
 */
export type Bytes = number[] | Uint8Array;

/** Opt-in delegation of ATT request handling to JavaScript. Defaults to automatic responses. */
export interface CharacteristicDelegateConfig {
  /** Emit `onCharacteristicReadRequest` even when a value exists, for computed/dynamic reads. */
  read?: boolean;
  /**
   * Do not acknowledge writes automatically. `onCharacteristicWriteRequest` carries a live `requestId`.
   * Android never delegates Write Without Response; iOS cannot tell them apart, so both delegate.
   * On iOS, one `sendResponse` answers all events in an atomic batch; on Android, per-request.
   * See `CharacteristicWriteRequestEvent`.
   */
  write?: boolean;
}

export interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  /** Value that reads are answered from. Omit to delegate all reads. `[]` is present but zero-length. */
  value?: Bytes;
  /** Additional descriptors beyond Client Characteristic Configuration. See `GattDescriptorConfig`. */
  descriptors?: GattDescriptorConfig[];
  /** Opt out of the module's automatic responses for this characteristic. */
  delegate?: CharacteristicDelegateConfig;
}

/**
 * Core Spec Vol 3, Part G, §3.3.3.3. Module publishes it for `notify`/`indicate` characteristics.
 * Do not declare it in `descriptors`.
 */
export const CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = '00002902-0000-1000-8000-00805f9b34fb';

/**
 * iOS accepts only 0x2901 (User Description, UTF-8) and 0x2904 (Presentation Format).
 * `CLIENT_CHARACTERISTIC_CONFIGURATION_UUID` is rejected on both platforms.
 */
export interface GattDescriptorConfig {
  uuid: string;
  /** Required and immutable once published. For 0x2901, must be valid UTF-8. */
  value: Bytes;
  /** Android only, defaults to `['readable']`. Ignored on iOS. */
  permissions?: CharacteristicPermission[];
}

/**
 * Core Spec Vol 3, Part G, Table 3.5.
 * iOS rejects `broadcast` and `extendedProperties`.
 */
export type CharacteristicProperty =
  | 'read'
  | 'write'
  | 'writeNoResponse'
  | 'notify'
  | 'indicate'
  | 'broadcast'
  | 'signedWrite'
  | 'extendedProperties';

/**
 * iOS rejects MITM and signed variants with `ERR_UNSUPPORTED`.
 * Encrypted permission raises subscription security: unpaired centrals cannot subscribe to notify/indicate.
 */
export type CharacteristicPermission =
  | 'readable'
  | 'writeable'
  | 'readEncrypted'
  | 'readEncryptedMitm'
  | 'writeEncrypted'
  | 'writeEncryptedMitm'
  | 'writeSigned'
  | 'writeSignedMitm';

/** Secondary service (Core Spec Vol 3, Part G, §3.1): published but not in primary discovery. */
export type GattServiceType = 'primary' | 'secondary';

/** Duplicate service or characteristic UUIDs within a service are rejected; UUIDs across services are allowed. */
export interface GattServiceConfig {
  uuid: string;
  /** Defaults to `primary`. */
  type?: GattServiceType;
  characteristics: GattCharacteristicConfig[];
}

/** Core Spec Vol 3, Part F, §3.3.3: ATT transaction timeout before bearer drops. Upper bound for requestTimeoutMs. */
export const ATT_TRANSACTION_TIMEOUT_MS = 30_000;

/** Default `CreateServerOptions.requestTimeoutMs`. */
export const DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

/** Core Spec Vol 3, Part F, §3.2.9: maximum attribute value length. */
export const MAX_ATTRIBUTE_VALUE_LENGTH = 512;

export interface CreateServerOptions {
  /**
   * Timeout (ms) before unanswered delegated requests auto-fail. Prevents handler stalls from
   * dropping the ATT bearer. Defaults to 10000. Must be 0 to ATT_TRANSACTION_TIMEOUT_MS - 1.
   */
  requestTimeoutMs?: number;
}

export interface SendNotificationOptions {
  /**
   * Send an indication instead of a notification. Defaults to `false`. The characteristic must
   * declare the matching property (Core Spec Vol 3, Part G, Table 3.5) or the call rejects with
   * `ERR_CONFIRM_UNSUPPORTED`.
   *
   * iOS never receives this: `updateValue(_:for:onSubscribedCentrals:)` has no confirm parameter and
   * CoreBluetooth decides from the declared properties alone.
   */
  confirm?: boolean;
  /**
   * Reject with `ERR_NO_SUBSCRIBER` if not subscribed to this transmission mode. Defaults to `true`.
   * Android checks the CCCD bit, and `false` sends anyway. iOS ignores `false`: `updateValue` drops
   * unsubscribed centrals, so a forced send has nowhere to go.
   */
  requireSubscription?: boolean;
}

/** Android: 1s/250ms/100ms intervals. Ignored on iOS. */
export type AdvertisingMode = 'lowPower' | 'balanced' | 'lowLatency';

/** Ignored on iOS. */
export type AdvertisingTxPower = 'ultraLow' | 'low' | 'medium' | 'high';

/** Android only; rejected on iOS with `ERR_UNSUPPORTED`. */
export interface ManufacturerDataEntry {
  companyId: number;
  data: Bytes;
}

/** Android only; rejected on iOS with `ERR_UNSUPPORTED`. Use 16-bit UUID for portability. */
export interface ServiceDataEntry {
  uuid: string;
  data: Bytes;
}

/** Android-only options. Ignored on iOS. */
export interface AndroidAdvertiseOptions {
  /** Include device's Bluetooth name in scan response. Defaults to `true` when `localName` is set. */
  includeDeviceName?: boolean;
  /**
   * Rename system Bluetooth adapter. Changes system-wide name, not just advertisement.
   * Restored on stopAdvertising/stopServer, or at the next createServer or power-on when the adapter
   * was off then (`setName` fails while it is). A rename outlives the process that applied it.
   * Requires BLUETOOTH_CONNECT API 31+. Rejects with ERR_ADVERTISE unless localName is set.
   */
  setAdapterName?: boolean;
}

export interface AdvertiseConfig {
  /**
   * iOS: verbatim, in advertisement payload. Android: ignored; use android.setAdapterName to rename.
   * Competes with 31-byte budget on iOS only.
   */
  localName?: string;
  serviceUuids?: string[];
  /** Include the radio's transmit power level in the scan response. Ignored on iOS, with a warning. */
  includeTxPowerLevel?: boolean;
  /** Defaults to `true`. iOS rejects `false` with `ERR_UNSUPPORTED`. */
  connectable?: boolean;
  /** Android only. Defaults to `lowPower`. */
  mode?: AdvertisingMode;
  /** Android only. Defaults to `medium`. */
  txPowerLevel?: AdvertisingTxPower;
  /** Stop after ms. Defaults to 0 (no timeout). Must be 0–180000. iOS emulates with timer. */
  timeoutMs?: number;
  /** Android only; rejected on iOS. Shares 31-byte budget with service UUIDs. */
  manufacturerData?: ManufacturerDataEntry[];
  /** Android only; rejected on iOS. Shares 31-byte budget with manufacturer data. */
  serviceData?: ServiceDataEntry[];
  android?: AndroidAdvertiseOptions;
}

export interface DeviceConnectedEvent {
  deviceId: string;
  /** Empty on iOS; CoreBluetooth does not expose remote central names. */
  name?: string;
}

/**
 * Android: from onConnectionStateChange. iOS: derived from ATT activity (subscribe/read/write);
 * absent if central never touches an attribute, dropped if unsubscribes from all.
 */
export interface ConnectedDevice {
  deviceId: string;
  /** Android only. */
  name?: string;
}

export interface DeviceDisconnectedEvent {
  deviceId: string;
}

export interface CharacteristicReadRequestEvent {
  deviceId: string;
  /** Unique per device only. Key bookkeeping on (deviceId, requestId) pair. */
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
}

/** Long/reliable write reported once at offset 0. iOS cannot distinguish Write Without Response. */
export interface CharacteristicWriteRequestEvent {
  deviceId: string;
  /** Unique per device only. */
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
  value: number[];
  /**
   * `true` only if characteristic has `delegate.write`.
   * Android: `false` for Write Without Response. iOS: cannot distinguish, always `true` for delegated writes.
   */
  responseNeeded: boolean;
}

export interface NotificationSentEvent {
  deviceId: string;
  characteristicUuid: string;
  status: number;
}

/** ATT MTU and link budget. */
export interface DeviceMtu {
  deviceId: string;
  /**
   * Default 23 (Core Spec Vol 3, Part G, §5.2.1). Exact on Android.
   * On iOS, derived from CBCentral.maximumUpdateValueLength + 3.
   */
  mtu: number;
  /** Max octets per notification/indication: min(mtu - 3, 512). Reject if larger with PAYLOAD_EXCEEDS_MTU. */
  maxNotificationPayload: number;
}

/**
 * Android: delivered as onMtuChanged fires. iOS: sampled on ATT activity, emitted if changed.
 * Always reported once on onDeviceConnected.
 */
export type MtuChangedEvent = DeviceMtu;

/** Central enabled notifications or indications. Notification vs. indication not distinguished. */
export interface CharacteristicSubscribedEvent {
  deviceId: string;
  serviceUuid: string;
  characteristicUuid: string;
}

/**
 * Central stopped or disconnected. On iOS, losing last subscription reports as disconnection too.
 * Pending delegated requests are not cancelled; they expire via requestTimeoutMs.
 */
export interface CharacteristicUnsubscribedEvent {
  deviceId: string;
  serviceUuid: string;
  characteristicUuid: string;
}

/**
 * Normalized adapter state. `poweredOn` only state allowing advertising/scanning.
 * `poweredOff` destroys published database; call createServer again after recovery.
 * `unauthorized` is iOS only; Android reports missing permissions as ERR_PERMISSION on the call.
 */
export type BluetoothState =
  'unknown' | 'resetting' | 'unsupported' | 'unauthorized' | 'poweredOff' | 'poweredOn';

export interface BluetoothStateChangedEvent {
  state: BluetoothState;
}

/**
 * Published database is absent; call createServer to recover.
 * Emitted when re-publication fails; not on normal Bluetooth off (that's onBluetoothStateChanged).
 */
export interface ServerPublicationFailedEvent {
  code: string;
  message: string;
}

/**
 * Every serviceUuid and characteristicUuid in these payloads is the lowercase 128-bit form (Core Spec
 * Vol 3, Part B, §2.5.1) — the only canonical spelling across platforms. Use it for equality checks.
 */
export type GattServerEvents = {
  onDeviceConnected(event: DeviceConnectedEvent): void;
  onDeviceDisconnected(event: DeviceDisconnectedEvent): void;
  onCharacteristicReadRequest(event: CharacteristicReadRequestEvent): void;
  onCharacteristicWriteRequest(event: CharacteristicWriteRequestEvent): void;
  onNotificationSent(event: NotificationSentEvent): void;
  onMtuChanged(event: MtuChangedEvent): void;
  onCharacteristicSubscribed(event: CharacteristicSubscribedEvent): void;
  onCharacteristicUnsubscribed(event: CharacteristicUnsubscribedEvent): void;
  onBluetoothStateChanged(event: BluetoothStateChangedEvent): void;
  onServerPublicationFailed(event: ServerPublicationFailedEvent): void;
};

/** ATT error codes (Core Spec Vol 3, Part F, §3.4.1.1, Table 3.4). Only 0x01–0x11 portable. */
export const GATT_SUCCESS = 0x00;

/** The attribute handle given was not valid on this server. */
export const ATT_ERROR_INVALID_HANDLE = 0x01;
/** The attribute cannot be read. */
export const ATT_ERROR_READ_NOT_PERMITTED = 0x02;
/** The attribute cannot be written. */
export const ATT_ERROR_WRITE_NOT_PERMITTED = 0x03;
/** The attribute PDU was invalid. */
export const ATT_ERROR_INVALID_PDU = 0x04;
/** The attribute requires authentication before it can be read or written. */
export const ATT_ERROR_INSUFFICIENT_AUTHENTICATION = 0x05;
/** The server does not support the request received from the client. */
export const ATT_ERROR_REQUEST_NOT_SUPPORTED = 0x06;
/** Offset specified was past the end of the attribute. */
export const ATT_ERROR_INVALID_OFFSET = 0x07;
/** The attribute requires authorization before it can be read or written. */
export const ATT_ERROR_INSUFFICIENT_AUTHORIZATION = 0x08;
/** Too many prepare writes have been queued. */
export const ATT_ERROR_PREPARE_QUEUE_FULL = 0x09;
/** No attribute found within the given attribute handle range. */
export const ATT_ERROR_ATTRIBUTE_NOT_FOUND = 0x0a;
/** The attribute cannot be read using the read blob request. */
export const ATT_ERROR_ATTRIBUTE_NOT_LONG = 0x0b;
/** The encryption key size used for encrypting this link is too short. */
export const ATT_ERROR_INSUFFICIENT_ENCRYPTION_KEY_SIZE = 0x0c;
/** The attribute value length is invalid for the operation. */
export const ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH = 0x0d;
/** The request encountered an error that was unlikely, so it could not be completed. */
export const ATT_ERROR_UNLIKELY_ERROR = 0x0e;
/** The attribute requires encryption before it can be read or written. */
export const ATT_ERROR_INSUFFICIENT_ENCRYPTION = 0x0f;
/** The attribute type is not a supported grouping attribute. */
export const ATT_ERROR_UNSUPPORTED_GROUP_TYPE = 0x10;
/** Insufficient resources to complete the request. */
export const ATT_ERROR_INSUFFICIENT_RESOURCES = 0x11;
