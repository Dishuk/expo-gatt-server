/**
 * Per-characteristic opt-in delegation of ATT request handling to JavaScript. Every flag defaults to
 * `false`, which keeps the module answering the request itself.
 */
export interface CharacteristicDelegateConfig {
  /**
   * Always emit `onCharacteristicReadRequest` and wait for `sendResponse`, even when the
   * characteristic already has a value to serve. Computed or dynamic reads need this: without it the
   * module answers from the last known value as soon as one exists, so the event stops firing.
   */
  read?: boolean;
  /**
   * Do not acknowledge writes automatically. `onCharacteristicWriteRequest` carries a live
   * `requestId` and the write stays unanswered until `sendResponse` is called with `GATT_SUCCESS` or
   * an `ATT_ERROR_*` code — the only way to reject a write.
   *
   * Apple requires exactly one response per write callback, taken from the first request of the
   * batch, and documents the batch as all-or-nothing, so on iOS every event produced by one batch
   * shares a single `requestId` and the first `sendResponse` for it answers the whole batch.
   */
  write?: boolean;
}

export interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  /**
   * The value reads are answered from until something replaces it. Omit it to delegate every read to
   * JavaScript instead. `[]` is a configured value, not an absent one: it declares a present but
   * zero-length attribute, which both platforms answer reads from with an empty value.
   */
  value?: number[];
  /**
   * Descriptors to publish beyond the Client Characteristic Configuration descriptor the module adds
   * itself. See `GattDescriptorConfig` for the types each platform can express.
   */
  descriptors?: GattDescriptorConfig[];
  /** Opt out of the module's automatic responses for this characteristic. */
  delegate?: CharacteristicDelegateConfig;
}

/**
 * Client Characteristic Configuration descriptor (Core Spec Vol 3, Part G, §3.3.3.3), in the 128-bit
 * form both platforms compare against.
 *
 * The module publishes it for every characteristic declaring `notify` or `indicate`, and owns its
 * value: the specification gives each client its own instantiation, so the configuration bits are
 * tracked per device rather than in the single descriptor object the platform hands out. Declaring it
 * in `descriptors` is therefore rejected.
 */
export const CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = '00002902-0000-1000-8000-00805f9b34fb';

/**
 * A descriptor to publish on a characteristic.
 *
 * **iOS accepts only two descriptor types**: `CBMutableDescriptor` is documented as supporting "only
 * the `Characteristic User Description` and `Characteristic Presentation Format` descriptors" —
 * 0x2901 and 0x2904 — so any other UUID is rejected there with `ERR_UNSUPPORTED`. Android publishes
 * whatever it is given. `CLIENT_CHARACTERISTIC_CONFIGURATION_UUID` is rejected on both platforms.
 */
export interface GattDescriptorConfig {
  uuid: string;
  /**
   * Required on both platforms, to keep one configuration portable, because iOS documents a
   * descriptor's value as "required and cannot be updated dynamically once the parent service has
   * been published".
   *
   * For 0x2901 the bytes must be valid UTF-8, since iOS models that descriptor's value as an
   * `NSString`. Every other supported descriptor takes them verbatim.
   */
  value: number[];
  /**
   * Android only, and defaults to `['readable']`. `CBMutableDescriptor` has no permissions
   * parameter — CoreBluetooth decides them from the descriptor type — so this is ignored on iOS.
   */
  permissions?: CharacteristicPermission[];
}

/**
 * Characteristic properties, as declared in the characteristic's declaration (Core Spec Vol 3,
 * Part G, Table 3.5).
 *
 * `broadcast` and `extendedProperties` are **rejected on iOS**, which annotates both
 * `CBCharacteristicPropertyBroadcast` and `CBCharacteristicPropertyExtendedProperties` as "Not
 * allowed for local characteristics". They are offered because Android's `PROPERTY_BROADCAST` and
 * `PROPERTY_EXTENDED_PROPS` do set the bits.
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
 * Access requirements for an attribute value. `readable` and `writeable` map 1:1 onto both
 * platforms; `readEncrypted` and `writeEncrypted` map onto CoreBluetooth's
 * `read`/`writeEncryptionRequired`.
 *
 * **The MITM and signed variants are rejected on iOS** with `ERR_UNSUPPORTED`.
 * `CBAttributePermissions` has exactly four members, and every near equivalent *weakens* what was
 * asked for — an MITM variant demands authenticated pairing rather than any encrypted link, a signed
 * variant a signature over an unencrypted one — so the call fails rather than publishing an attribute
 * less protected than the app declared.
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
 * A secondary service "is a service that is included from another service" (Core Spec Vol 3, Part G,
 * §3.1) and is not intended to be discovered on its own. This module publishes every configured
 * service at the top level and cannot include one service from another, so a `secondary` service is
 * published but will not be found by a central doing primary service discovery. It is offered
 * because both platforms can express the type and a peer that already knows the handle can use it.
 */
export type GattServiceType = 'primary' | 'secondary';

export interface GattServiceConfig {
  uuid: string;
  /** Defaults to `primary`. */
  type?: GattServiceType;
  characteristics: GattCharacteristicConfig[];
}

/**
 * The ATT transaction timeout, in milliseconds. A transaction not completed within 30 s times out,
 * after which no further request, command, indication or notification may be sent on that ATT
 * bearer — recovering costs a whole new bearer (Core Spec Vol 3, Part F, §3.3.3). A server timeout at
 * or above this could never answer in time, so it is the exclusive upper bound on
 * `CreateServerOptions.requestTimeoutMs`.
 */
export const ATT_TRANSACTION_TIMEOUT_MS = 30_000;

/** Default `CreateServerOptions.requestTimeoutMs`. */
export const DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

export interface CreateServerOptions {
  /**
   * How long a request delegated to JavaScript may go unanswered before the module answers it itself
   * with `ATT_ERROR_UNLIKELY_ERROR`, in milliseconds. Defaults to `DEFAULT_REQUEST_TIMEOUT_MS`
   * (10000).
   *
   * Without it a handler that never calls `sendResponse` leaves the central stalled until its own
   * 30 s ATT transaction timeout expires, which then bars every further request, notification and
   * indication on that bearer. Answering early keeps the bearer usable.
   *
   * Must be an integer from 0 to `ATT_TRANSACTION_TIMEOUT_MS - 1`; `0` disables the timeout.
   */
  requestTimeoutMs?: number;
}

export interface SendNotificationOptions {
  /**
   * Refuse the send with `ERR_NO_SUBSCRIBER` when the target device has not enabled the exact
   * transmission `confirm` selects — indications for `confirm: true`, notifications for
   * `confirm: false`. Defaults to `true`.
   *
   * On Android this is checked against the device's own Client Characteristic Configuration bits,
   * because "when a bit is set, that action shall be enabled, otherwise it will not be used" (Core
   * Spec Vol 3, Part G, §3.3.3.3). `false` sends anyway there, since the platform transmits without
   * consulting the descriptor — useful for a peer whose descriptor state the app knows better.
   *
   * iOS cannot make the distinction, because CoreBluetooth reports a subscription without saying
   * which bit the central set, so it is checked as "subscribed at all". `false` changes nothing
   * there: `updateValue(_:for:onSubscribedCentrals:)` ignores unsubscribed centrals, so there is no
   * send to force.
   *
   * This never relaxes the `confirm` property check, which applies on both platforms regardless.
   */
  requireSubscription?: boolean;
}

/**
 * Discovery latency against battery. Android implements these as advertising intervals of 1 s, 250 ms
 * and 100 ms, and documents `lowLatency` as having "the highest power consumption" and as something
 * that "should not be used for continuous background advertising". Ignored on iOS, which chooses
 * advertising intervals itself.
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
   * is the only control Android offers over the advertised name, which is why it is exposed at all,
   * but it defaults to `false`.
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
   * The local name to advertise. Honoured verbatim on iOS, as `CBAdvertisementDataLocalNameKey`.
   *
   * **Android has no per-advertisement local name**: `AdvertiseData.Builder` offers only
   * `setIncludeDeviceName(boolean)`, and the name that includes is the *adapter's*. No public API
   * writes an arbitrary Local Name into an advertisement, so Android advertises the device's own name
   * instead (`android.includeDeviceName`) unless the app opts in to renaming the adapter with
   * `android.setAdapterName`.
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
   * applied on both platforms. iOS has no equivalent, so the module emulates it with a timer — same
   * observable outcome, but only while the process is alive.
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
 * connections directly, through `onConnectionStateChange`. iOS has no connection-level callback at
 * all, so membership is derived from ATT activity: a central appears on its first subscribe, read or
 * write, and is dropped when it unsubscribes from everything or Bluetooth leaves `poweredOn`. So on
 * iOS a central that never touches an attribute is absent from this list, and one that unsubscribes
 * but stays connected is dropped from it early.
 *
 * The list is the module's own tracking on both platforms, not a platform query:
 * `BluetoothManager.getConnectedDevices(GATT_SERVER)` would report centrals connected to *any* GATT
 * server on the device, including other apps'.
 */
export interface ConnectedDevice {
  deviceId: string;
  /** From `BluetoothDevice.getName()` on Android. Always empty on iOS. */
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
 * A long or reliable write is reported on Android as a single event per attribute, carrying the
 * reassembled value at `offset: 0`, once the execute has committed it; a cancelled queue emits
 * nothing. iOS cannot report the distinction, because CoreBluetooth exposes no prepared-write
 * callback and the fragmentation happens below the app layer.
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
 * The public unit is the **ATT MTU**, in octets, because that is what the Core Specification and the
 * Android platform both call "MTU". `maxNotificationPayload` is provided rather than left to the
 * caller to derive.
 */
export interface DeviceMtu {
  deviceId: string;
  /**
   * ATT_MTU in octets, including the ATT header. Before any negotiation this is the specification
   * default of 23 (Core Spec Vol 3, Part G, §5.2.1).
   *
   * Exact on Android, which reports the ATT MTU directly through `onMtuChanged`. Derived on iOS,
   * where CoreBluetooth exposes only `CBCentral.maximumUpdateValueLength` and three octets of
   * notification header are added back — Apple does not document that identity, so prefer
   * `maxNotificationPayload` there.
   */
  mtu: number;
  /**
   * Octets that fit in a single notification or indication — `mtu - 3`, the maximum Attribute Value
   * length of an `ATT_HANDLE_VALUE_NTF` PDU (Core Spec Vol 3, Part F, §3.4.7.1). Size a
   * `sendNotification` payload against this; anything larger is rejected with `PAYLOAD_EXCEEDS_MTU`
   * rather than truncated.
   */
  maxNotificationPayload: number;
}

/**
 * The MTU for a connection changed, or was observed for the first time.
 *
 * Android delivers this from `onMtuChanged`, when a client requests a different MTU. iOS has no
 * equivalent callback, so the value is sampled whenever the central produces ATT activity and
 * emitted when it differs from the value last seen — a change therefore surfaces at the next
 * activity rather than the moment it happens, and the first event arrives with `onDeviceConnected`.
 */
export type MtuChangedEvent = DeviceMtu;

/**
 * A central enabled notifications or indications on a characteristic — the signal to start
 * streaming to it.
 *
 * Whether it asked for notifications or for indications is not reported: CoreBluetooth does not
 * expose the distinction, so it cannot be surfaced consistently. Switching between the two produces
 * no further event, since the central stays subscribed throughout.
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
 * - `poweredOn` — the only state in which a server can advertise.
 * - `poweredOff` — iOS `CBManagerState.poweredOff`, Android `STATE_OFF`.
 * - `resetting` — transient, so do not act yet. iOS `CBManagerState.resetting`, Android
 *   `STATE_TURNING_ON` / `STATE_TURNING_OFF`, both of which the platform documents as not yet usable.
 * - `unsupported` — no BLE peripheral support. iOS `CBManagerState.unsupported`, Android: no
 *   `BluetoothAdapter`.
 * - `unauthorized` — the app may not use Bluetooth. iOS `CBManagerState.unauthorized`.
 * - `unknown` — not determined yet. iOS reports this until the first state callback arrives.
 *
 * `poweredOff` destroys the published GATT database on both platforms. The module re-publishes
 * services on the next transition to `poweredOn`, but advertising must be restarted by the consumer.
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

/**
 * Every `serviceUuid` and `characteristicUuid` in an event payload is the **lowercase 128-bit form**,
 * on both platforms, whatever spelling the configuration used. A 16-bit or 32-bit UUID is expanded
 * onto the Bluetooth Base UUID before it reaches either platform, because `CBUUID` accepts all three
 * forms while Java's `UUID.fromString` requires only the 8-4-4-4-12 one.
 *
 * The spelling the consumer passed is deliberately **not** echoed back. The Core Specification
 * requires the conversion before comparison anyway (Vol 3, Part B, §2.5.1), and one canonical
 * spelling is what makes a consumer's `event.characteristicUuid === MY_UUID` work at all. Restoring
 * it would also mean a reverse map on both platforms, for events that arrive for attributes never
 * configured — a descriptor write, a read on a re-published database — which have no spelling to
 * restore.
 *
 * `deviceId` is unaffected: it is an opaque handle (a MAC address on Android, a `CBCentral.identifier`
 * on iOS), not a Bluetooth UUID.
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
};

/**
 * Statuses accepted by `sendResponse`.
 *
 * An ATT error code is a single octet (Core Spec 5.4, Vol 3, Part F, §3.4.1.1, Table 3.4), and
 * Android narrows the status to a `uint8_t` on its way into the Bluetooth stack — so
 * `BluetoothGatt.GATT_FAILURE` (257), a GATT *status* rather than an ATT error code, actually went out
 * on the wire as its low byte, `0x01` "Invalid Handle".
 *
 * Only 0x01–0x11 are exposed, because they are exactly the codes both platforms can transmit:
 * `CBATTError.Code` stops at 0x11. Android could also send 0x12, 0x13 and the application and profile
 * ranges, but iOS would downgrade those to Unlikely Error, so they are deliberately not offered.
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
