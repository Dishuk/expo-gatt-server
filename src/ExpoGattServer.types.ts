/**
 * Per-characteristic opt-in delegation of ATT request handling to JavaScript.
 *
 * Every flag defaults to `false`, which keeps the module's automatic behaviour, so an existing
 * configuration behaves exactly as it did before this option existed.
 */
export interface CharacteristicDelegateConfig {
  /**
   * Always emit `onCharacteristicReadRequest` and wait for `sendResponse`, even when the
   * characteristic already has a value to serve.
   *
   * Without this the module answers a read from the last known value as soon as one exists — and
   * one exists as soon as `value` is configured, `updateCharacteristicValue` is called, or (on
   * iOS) a write lands — so the event stops firing permanently. Computed or dynamic reads need
   * this flag. A configured `value` is still used for notifications, it just no longer answers
   * reads.
   */
  read?: boolean;
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
  /**
   * Descriptors to publish alongside the characteristic, beyond the Client Characteristic
   * Configuration descriptor the module adds itself. See `GattDescriptorConfig` for the
   * descriptor types each platform can express.
   */
  descriptors?: GattDescriptorConfig[];
  /** Opt out of the module's automatic responses for this characteristic. */
  delegate?: CharacteristicDelegateConfig;
}

/**
 * Client Characteristic Configuration descriptor (Bluetooth Core Specification, Vol 3, Part G,
 * Section 3.3.3.3), in the 128-bit form both platforms compare against.
 *
 * The module publishes this descriptor itself for every characteristic declaring `notify` or
 * `indicate`, and owns its value: the specification gives each client its own instantiation, so the
 * per-client configuration bits are tracked per device rather than in the single descriptor object
 * the platform hands out. Declaring it in `descriptors` is therefore rejected — see
 * `GattDescriptorConfig`.
 */
export const CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = '00002902-0000-1000-8000-00805f9b34fb';

/**
 * A descriptor to publish on a characteristic.
 *
 * **iOS accepts only two descriptor types.** `CBMutableDescriptor` is documented as supporting
 * "only the `Characteristic User Description` and `Characteristic Presentation Format`
 * descriptors" — 0x2901 and 0x2904 — so any other UUID is rejected there with `ERR_UNSUPPORTED`.
 * Android publishes whatever it is given.
 *
 * Declaring `CLIENT_CHARACTERISTIC_CONFIGURATION_UUID` is rejected on both platforms: the module
 * adds it automatically and answers it per client.
 */
export interface GattDescriptorConfig {
  uuid: string;
  /**
   * Required, because iOS documents a descriptor's value as "required and cannot be updated
   * dynamically once the parent service has been published". Requiring it on both platforms keeps
   * one configuration portable.
   *
   * For 0x2901 the bytes are decoded as UTF-8 on iOS, which models that descriptor's value as an
   * `NSString`; invalid UTF-8 is rejected. Every other supported descriptor takes the bytes
   * verbatim.
   */
  value: number[];
  /**
   * Android only. `CBMutableDescriptor` has no permissions parameter — CoreBluetooth decides them
   * from the descriptor type — so this is ignored on iOS. Defaults to `['readable']`, which is what
   * the metadata descriptors iOS also supports need.
   */
  permissions?: CharacteristicPermission[];
}

/**
 * Characteristic properties, as declared in the characteristic's declaration (Bluetooth Core
 * Specification, Vol 3, Part G, Table 3.5).
 *
 * `broadcast` and `extendedProperties` are **rejected on iOS**: Apple annotates both
 * `CBCharacteristicPropertyBroadcast` and `CBCharacteristicPropertyExtendedProperties` as "Not
 * allowed for local characteristics". They are offered because Android's
 * `PROPERTY_BROADCAST` and `PROPERTY_EXTENDED_PROPS` do set the bits, and a peripheral targeting
 * Android alone can legitimately want them.
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
 * Access requirements for an attribute value.
 *
 * `readable` and `writeable` map 1:1 onto both platforms. `readEncrypted` and `writeEncrypted` map
 * onto `CBAttributePermissionsReadEncryptionRequired` and
 * `CBAttributePermissionsWriteEncryptionRequired`, which Apple documents as "trusted devices".
 *
 * **The MITM and signed variants are rejected on iOS** with `ERR_UNSUPPORTED`.
 * `CBAttributePermissions` has exactly four members, so there is nothing to map them to, and the
 * nearest approximations all *weaken* what was asked for: an MITM variant demands authenticated
 * pairing rather than any encrypted link, and a signed variant demands a signature over an
 * unencrypted one. Silently downgrading either would publish an attribute less protected than the
 * app declared, which is worse than refusing to publish it — so the call fails and names the
 * portable alternative instead.
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

/**
 * A secondary service "is a service that is included from another service" (Bluetooth Core
 * Specification, Vol 3, Part G, Section 3.1) and is not intended to be discovered on its own. This
 * module publishes each configured service at the top level and exposes no way to include one
 * service from another, so a `secondary` service is published but will not be found by a central
 * doing primary service discovery. It is offered because both platforms can express the type —
 * `BluetoothGattService.SERVICE_TYPE_SECONDARY` and `CBMutableService(type:primary:)` — and a peer
 * that already knows the handle can still use it.
 */
export type GattServiceType = 'primary' | 'secondary';

export interface GattServiceConfig {
  uuid: string;
  /** Defaults to `primary`. */
  type?: GattServiceType;
  characteristics: GattCharacteristicConfig[];
}

/**
 * The ATT transaction timeout, in milliseconds. "A transaction not completed within 30 seconds shall
 * time out", after which "no more Attribute Protocol requests, commands, indications or
 * notifications shall be sent to the target device on this ATT bearer" — recovering costs a whole
 * new bearer (Bluetooth Core Specification, Vol 3, Part F, Section 3.3.3). A server timeout at or
 * above this could never answer in time, so it is the exclusive upper bound on
 * `CreateServerOptions.requestTimeoutMs`.
 */
export const ATT_TRANSACTION_TIMEOUT_MS = 30_000;

/** Default `CreateServerOptions.requestTimeoutMs`. */
export const DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

export interface CreateServerOptions {
  /**
   * How long a request delegated to JavaScript may go unanswered before the module answers it
   * itself with `ATT_ERROR_UNLIKELY_ERROR`, in milliseconds. Defaults to
   * `DEFAULT_REQUEST_TIMEOUT_MS` (10000).
   *
   * Without it a handler that never calls `sendResponse` leaks the pending request and leaves the
   * central stalled until its own ATT transaction timeout of 30 s expires, which then bars every
   * further request, notification and indication on that bearer. Answering early enough keeps the
   * bearer usable and turns a missing response into an ordinary ATT error the central can handle.
   *
   * Must be an integer from 0 to `ATT_TRANSACTION_TIMEOUT_MS - 1`; `0` disables the timeout and
   * restores the unbounded wait.
   */
  requestTimeoutMs?: number;
}

export interface SendNotificationOptions {
  /**
   * Refuse the send with `ERR_NO_SUBSCRIBER` when the target device has not enabled the exact
   * transmission `confirm` selects — indications for `confirm: true`, notifications for
   * `confirm: false`. Defaults to `true`, so a notification nobody asked for is reported instead of
   * silently going nowhere.
   *
   * On Android this is checked against the device's own Client Characteristic Configuration bits:
   * a client that enabled only indications is no longer sent a notification, because "when a bit is
   * set, that action shall be enabled, otherwise it will not be used" (Bluetooth Core
   * Specification, Vol 3, Part G, Section 3.3.3.3). Setting it to `false` sends anyway there, since
   * the platform transmits without consulting the descriptor — useful for a peer whose descriptor
   * state the app knows better than the stack does.
   *
   * iOS cannot make the distinction: CoreBluetooth reports a subscription without saying which bit
   * the central set. It is checked as "subscribed at all", and `false` changes nothing —
   * `updateValue(_:for:onSubscribedCentrals:)` ignores centrals that have not subscribed, so there
   * is no send to force and `ERR_NO_SUBSCRIBER` is still reported.
   *
   * This never relaxes the `confirm` property check, which applies on both platforms regardless.
   */
  requireSubscription?: boolean;
}

/**
 * Discovery latency against battery. Android implements these as advertising intervals of 1 s, 250 ms
 * and 100 ms; `lowLatency` is documented as having "the highest power consumption" and as something
 * that "should not be used for continuous background advertising".
 *
 * Ignored on iOS, which chooses advertising intervals itself.
 */
export type AdvertisingMode = 'lowPower' | 'balanced' | 'lowLatency';

/**
 * Radio transmit power, which sets how far the advertisement carries. Ignored on iOS, which offers
 * no peripheral-role transmit power control.
 */
export type AdvertisingTxPower = 'ultraLow' | 'low' | 'medium' | 'high';

/**
 * A Manufacturer Specific Data advertisement structure. Android only; rejected on iOS with
 * `ERR_UNSUPPORTED`.
 */
export interface ManufacturerDataEntry {
  /** 16-bit Bluetooth SIG Company Identifier. `0xFFFF` is reserved for development and testing. */
  companyId: number;
  data: number[];
}

/** A Service Data advertisement structure. Android only; rejected on iOS with `ERR_UNSUPPORTED`. */
export interface ServiceDataEntry {
  /**
   * Android documents this as the "16-bit UUID of the service the data is associated with", so a
   * 4-hex-digit UUID is the portable choice — a 128-bit one costs 16 of the 31 available bytes.
   */
  uuid: string;
  data: number[];
}

/** Options whose concept has no CoreBluetooth counterpart. Ignored on iOS. */
export interface AndroidAdvertiseOptions {
  /**
   * Include the device's *own* Bluetooth name in the scan response. Defaults to `true` when
   * `localName` is set, so a config that asks for a name still gets one advertised — just the
   * device's own. Costs the name's length plus two bytes of the scan response's 31-byte budget.
   */
  includeDeviceName?: boolean;
  /**
   * Rename the device's Bluetooth adapter to `localName`, so a scanner sees the requested name.
   *
   * **This changes the phone's system-wide Bluetooth name**, not just this advertisement's: it
   * appears in the device's own Bluetooth settings and to every peer, over Classic as well as LE. It
   * is the only control Android offers over the advertised name, which is why it is exposed — but the
   * choice belongs to the app, so it defaults to `false`.
   *
   * The previous name is restored on `stopAdvertising`, `stopServer` or module destruction, but only
   * best-effort: `BluetoothAdapter.setName` fails while the adapter is off, and a killed process
   * never runs it. Prefer `includeDeviceName` unless the exact advertised name matters.
   *
   * Requires `BLUETOOTH_CONNECT` on API 31+. Rejects with `ERR_ADVERTISE` when set without a
   * `localName`.
   */
  setAdapterName?: boolean;
}

export interface AdvertiseConfig {
  /**
   * The local name to advertise. Honoured verbatim on iOS, as
   * `CBAdvertisementDataLocalNameKey`.
   *
   * **Android has no per-advertisement local name.** `AdvertiseData.Builder` offers only
   * `setIncludeDeviceName(boolean)`, and the name that includes is the *adapter's* —
   * `BluetoothLeAdvertiser` sizes the field from `BluetoothAdapter.getNameLengthForAdvertise()`. No
   * public API writes an arbitrary Local Name into an advertisement. Android therefore advertises the
   * device's own name instead (`android.includeDeviceName`), unless the app opts in to renaming the
   * adapter with `android.setAdapterName`.
   */
  localName?: string;
  serviceUuids?: string[];
  /** Include the radio's transmit power level in the scan response. Ignored on iOS, with a warning. */
  includeTxPowerLevel?: boolean;
  /**
   * Advertise as connectable, so centrals may open a connection. Defaults to `true`. `false` is
   * rejected on iOS with `ERR_UNSUPPORTED`: `CBPeripheralManager` only implements the connectable
   * peripheral role.
   */
  connectable?: boolean;
  /** Defaults to `lowPower`, matching the platform default. Ignored on iOS, with a warning. */
  mode?: AdvertisingMode;
  /** Defaults to `medium`, matching the platform default. Ignored on iOS, with a warning. */
  txPowerLevel?: AdvertisingTxPower;
  /**
   * Stop advertising after this many milliseconds; `0`, the default, advertises until
   * `stopAdvertising`. Must be 0–180000, the bound `AdvertiseSettings.Builder.setTimeout` enforces,
   * applied on both platforms.
   *
   * iOS has no equivalent, so the module emulates it with a timer — same observable outcome, but only
   * while the process is alive.
   */
  timeoutMs?: number;
  /**
   * Manufacturer Specific Data to advertise. Shares the advertisement's 31-byte budget with the
   * service UUIDs, costing its data length plus four bytes per entry; an overrun rejects with
   * `ERR_ADVERTISE`. Rejected on iOS with `ERR_UNSUPPORTED`.
   */
  manufacturerData?: ManufacturerDataEntry[];
  /**
   * Service Data to advertise. Shares the advertisement's 31-byte budget, as `manufacturerData` does.
   * Rejected on iOS with `ERR_UNSUPPORTED`.
   */
  serviceData?: ServiceDataEntry[];
  android?: AndroidAdvertiseOptions;
}

export interface DeviceConnectedEvent {
  deviceId: string;
  /** Always empty on iOS: CoreBluetooth exposes no name for a remote central. */
  name?: string;
}

/**
 * A central the module currently considers connected.
 *
 * **What "connected" means differs by platform, and cannot be made to agree.** Android reports
 * connections directly, through `BluetoothGattServerCallback.onConnectionStateChange`, so the list is
 * every central with a link to this server. iOS has no connection-level callback at all —
 * `CBPeripheralManagerDelegate` declares none — so membership is derived from ATT activity: a
 * central appears on its first subscribe, read request or write request, and is dropped when it
 * unsubscribes from everything or Bluetooth leaves `poweredOn`. A central that connects to an iOS
 * peripheral and never touches an attribute is invisible from the peripheral role, so it is absent
 * from this list; one that unsubscribes but stays connected is dropped from it early.
 *
 * The list is the module's own tracking on both platforms, not a platform query.
 * `BluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER)` would report centrals
 * connected to *any* GATT server on the device, including other apps'.
 */
export interface ConnectedDevice {
  deviceId: string;
  /**
   * From `BluetoothDevice.getName()` on Android. Always empty on iOS, which exposes no name for a
   * remote central.
   */
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

/**
 * A central wrote to a characteristic.
 *
 * A long or reliable write — the `ATT_PREPARE_WRITE_REQ` / `ATT_EXECUTE_WRITE_REQ` procedure a
 * central uses for a value too long for one PDU — is reported on Android as a single event per
 * attribute, carrying the reassembled value at `offset: 0`, once the execute has committed it.
 * Cancelled queues emit nothing. iOS cannot report the distinction: CoreBluetooth exposes no
 * prepared-write callback, so the fragmentation happens below the app layer.
 */
export interface CharacteristicWriteRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  /** Where `value` begins within the attribute. Always `0` for a reassembled long write. */
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
 * The link budget for one connected device.
 *
 * The public unit is the **ATT MTU**, in octets, because that is what the Bluetooth Core
 * Specification and the Android platform both call "MTU" — reporting anything else under that name
 * would be actively misleading. Every payload capacity follows from it, so
 * `maxNotificationPayload` is provided rather than left to the caller to derive.
 */
export interface DeviceMtu {
  deviceId: string;
  /**
   * ATT_MTU in octets, including the ATT header. Before any negotiation this is the specification
   * default of 23 (Core Specification, Vol 3, Part G, Section 5.2.1).
   *
   * Exact on Android, which reports the ATT MTU directly through `onMtuChanged`. Derived on iOS:
   * CoreBluetooth only exposes `CBCentral.maximumUpdateValueLength`, a payload length, so three
   * octets of `ATT_HANDLE_VALUE_NTF` header are added back. Apple does not document that identity,
   * so prefer `maxNotificationPayload` on iOS where the figure is exact.
   */
  mtu: number;
  /**
   * Octets that fit in a single notification or indication — `mtu - 3`, the maximum Attribute Value
   * length of an `ATT_HANDLE_VALUE_NTF` PDU (Core Specification, Vol 3, Part F, Section 3.4.7.1).
   *
   * This is the number to size a `sendNotification` payload against; `sendNotification` rejects
   * anything larger with `PAYLOAD_EXCEEDS_MTU` rather than letting it be truncated.
   */
  maxNotificationPayload: number;
}

/**
 * The MTU for a connection changed, or was observed for the first time.
 *
 * Android delivers this from `BluetoothGattServerCallback.onMtuChanged`, when a client requests a
 * different MTU. iOS has no equivalent callback, so the value is sampled whenever the central
 * produces ATT activity — a subscribe, read or write — and the event is emitted when it differs
 * from the value last seen. On iOS a change therefore surfaces at the next activity rather than the
 * moment it happens, and the first event for a device arrives alongside `onDeviceConnected`.
 */
export type MtuChangedEvent = DeviceMtu;

/**
 * A central enabled notifications or indications on a characteristic — the signal to start
 * streaming to it.
 *
 * On Android this is driven by a write to the characteristic's Client Characteristic Configuration
 * descriptor (Bluetooth Core Specification, Vol 3, Part G, Section 3.3.3.3), which every client
 * has its own instance of. On iOS it comes from `peripheralManager(_:central:didSubscribeTo:)`.
 *
 * Whether the central asked for notifications or for indications is not reported: CoreBluetooth
 * does not expose the distinction, so it cannot be surfaced consistently. Switching between the
 * two does not produce a further event — the central stays subscribed throughout.
 */
export interface CharacteristicSubscribedEvent {
  deviceId: string;
  /** Empty when the platform could not identify the owning service. */
  serviceUuid: string;
  characteristicUuid: string;
}

/**
 * A central stopped receiving updates for a characteristic — the signal to stop streaming.
 *
 * Also emitted for every subscription a central still held when it disconnects, and when
 * Bluetooth is turned off and the published database is dropped.
 */
export interface CharacteristicUnsubscribedEvent {
  deviceId: string;
  /** Empty when the platform could not identify the owning service. */
  serviceUuid: string;
  characteristicUuid: string;
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
  onMtuChanged(event: MtuChangedEvent): void;
  onCharacteristicSubscribed(event: CharacteristicSubscribedEvent): void;
  onCharacteristicUnsubscribed(event: CharacteristicUnsubscribedEvent): void;
  onBluetoothStateChanged(event: BluetoothStateChangedEvent): void;
};

/**
 * Statuses accepted by `sendResponse`.
 *
 * An ATT error code is a single octet — Bluetooth Core Specification 5.4, Vol 3, Part F,
 * Section 3.4.1.1, Table 3.4 — so a wider value is not transmissible. Android narrows the status
 * to a `uint8_t` before it reaches the Bluetooth stack, so the constant this list replaces,
 * `GATT_FAILURE = 257` (`BluetoothGatt.GATT_FAILURE`, which is a GATT *status* rather than an ATT
 * error code), actually went out on the wire as its low byte, `0x01` "Invalid Handle".
 *
 * Only 0x01–0x11 are exposed, because they are exactly the codes both platforms can transmit:
 * iOS can only send what `CBATTError.Code` models, and that stops at 0x11. Android could also
 * send 0x12 (Database Out Of Sync), 0x13 (Value Not Allowed), the application range 0x80–0x9F and
 * the profile range 0xE0–0xFF, but iOS has no representation for those and would downgrade them
 * to Unlikely Error, so they are deliberately not offered.
 */
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
