import { Platform, type EventSubscription } from 'expo-modules-core';

import ExpoGattServerModule, { type ExpoGattServerModuleType } from './ExpoGattServerModule';
import {
  ATT_TRANSACTION_TIMEOUT_MS,
  CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
} from './ExpoGattServer.types';
import type {
  GattServiceConfig,
  GattServiceType,
  GattCharacteristicConfig,
  CharacteristicProperty,
  CharacteristicPermission,
  CreateServerOptions,
  AdvertiseConfig,
  AdvertisingMode,
  AdvertisingTxPower,
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
  ConnectedDevice,
  DeviceMtu,
  MtuChangedEvent,
  GattServerEvents,
} from './ExpoGattServer.types';

export type { EventSubscription };

export {
  type GattServiceConfig,
  type GattServiceType,
  type CreateServerOptions,
  type GattCharacteristicConfig,
  type GattDescriptorConfig,
  type CharacteristicDelegateConfig,
  type AdvertiseConfig,
  type AndroidAdvertiseOptions,
  type AdvertisingMode,
  type AdvertisingTxPower,
  type ManufacturerDataEntry,
  type ServiceDataEntry,
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
  type ConnectedDevice,
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
  ATT_TRANSACTION_TIMEOUT_MS,
  DEFAULT_REQUEST_TIMEOUT_MS,
  CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
} from './ExpoGattServer.types';

/**
 * Whether the native module is present, and therefore whether anything else here can work.
 *
 * `false` on web, and in any binary that does not contain the module — Expo Go being the common
 * case, since Expo Go ships a fixed set of native modules and cannot load this one. Synchronous and
 * safe to call anywhere, including at module scope, so a consuming app can branch on it before
 * touching the rest of the API.
 *
 * Importing this package never throws, whatever this returns.
 */
export function isSupported(): boolean {
  return ExpoGattServerModule !== null;
}

function unsupportedError(): Error {
  if (Platform.OS === 'web') {
    return new Error(
      '[expo-gatt-server] Not supported on web. This package publishes a BLE GATT server, which ' +
        'requires the peripheral role; Web Bluetooth implements only the central role, so there is ' +
        'no browser API to build on. Guard your calls with isSupported().',
    );
  }
  return new Error(
    `[expo-gatt-server] The native module is not present in this ${Platform.OS} binary. It ships ` +
      'native code, so it cannot run in Expo Go — create a development build with ' +
      '`npx expo run:ios` / `npx expo run:android` or EAS Build. If you already use a development ' +
      'build, rebuild it after adding this package. Guard your calls with isSupported().',
  );
}

/** Throws the explanation instead of letting a property access on `null` surface as the error. */
function nativeModule(): ExpoGattServerModuleType {
  if (ExpoGattServerModule === null) {
    throw unsupportedError();
  }
  return ExpoGattServerModule;
}

/**
 * Stands in for a real subscription when there is no native module to subscribe to. Returned rather
 * than thrown, because a listener registered in an effect is paired with a `remove()` in that
 * effect's teardown, and throwing would leave the teardown to crash on a value it never received.
 */
const NOOP_SUBSCRIPTION: EventSubscription = { remove() {} };

function addListener<EventName extends keyof GattServerEvents>(
  eventName: EventName,
  listener: GattServerEvents[EventName],
): EventSubscription {
  return ExpoGattServerModule?.addListener(eventName, listener) ?? NOOP_SUBSCRIPTION;
}

// Accepted by CBUUID(string:) on iOS: 16-bit (4 hex digits), 32-bit (8 hex digits) or the
// hyphenated 128-bit form. Anything else raises an uncatchable ObjC exception natively.
const SHORT_UUID_RE = /^(?:[0-9a-fA-F]{4}|[0-9a-fA-F]{8})$/;
const LONG_UUID_RE =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/**
 * The Bluetooth Base UUID, `00000000-0000-1000-8000-00805F9B34FB` — Bluetooth Core Specification,
 * Vol 3, Part B, Section 2.5.1.
 */
const BLUETOOTH_BASE_UUID = '00000000-0000-1000-8000-00805f9b34fb';

/**
 * Validates a UUID and returns it as the lowercase 128-bit form, expanding a 16-bit or 32-bit alias
 * onto the Bluetooth Base UUID.
 *
 * The specification defines the aliases arithmetically as
 * `128_bit_value = 16_bit_value * 2^96 + Bluetooth_Base_UUID` and
 * `128_bit_value = 32_bit_value * 2^96 + Bluetooth_Base_UUID` (Vol 3, Part B, Section 2.5.1). `2^96`
 * lands the value in the leading 32 bits either way — a 16-bit alias being first zero-extended to
 * 32 bits — so the expansion is exactly "left-pad to eight hex digits and append the base UUID's
 * remaining four groups", which is why the suffix is taken from the constant rather than repeated.
 *
 * Normalising here rather than per platform is what makes one configuration portable: `CBUUID`
 * accepts all three forms, but Java's `UUID.fromString` requires the 8-4-4-4-12 form, so `'180D'`
 * used to be accepted on iOS and throw on Android. The specification also requires this conversion
 * before comparing UUIDs of different sizes — "the shorter UUID must be converted to the longer UUID
 * format before comparison" — so the long form is the only representation in which the module's own
 * lookups and a consumer's `===` against an event payload agree.
 */
function normalizeUuid(uuid: unknown, field: string): string {
  if (typeof uuid !== 'string' || (!SHORT_UUID_RE.test(uuid) && !LONG_UUID_RE.test(uuid))) {
    throw new Error(
      `Invalid ${field} UUID ${JSON.stringify(uuid)}. Expected 4 hex digits (16-bit), ` +
        '8 hex digits (32-bit) or the hyphenated 8-4-4-4-12 form (128-bit).',
    );
  }
  const lower = uuid.toLowerCase();
  if (LONG_UUID_RE.test(lower)) {
    return lower;
  }
  return lower.padStart(8, '0') + BLUETOOTH_BASE_UUID.slice(8);
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

const SERVICE_TYPES: GattServiceType[] = ['primary', 'secondary'];

const CHARACTERISTIC_PROPERTIES: CharacteristicProperty[] = [
  'read',
  'write',
  'writeNoResponse',
  'notify',
  'indicate',
  'broadcast',
  'signedWrite',
  'extendedProperties',
];

const CHARACTERISTIC_PERMISSIONS: CharacteristicPermission[] = [
  'readable',
  'writeable',
  'readEncrypted',
  'readEncryptedMitm',
  'writeEncrypted',
  'writeEncryptedMitm',
  'writeSigned',
  'writeSignedMitm',
];

/**
 * Both platforms used to ignore a name they did not recognise, so a typo published an attribute with
 * one fewer property or — far worse — one fewer permission than the app asked for, silently.
 */
function assertEachOneOf<T extends string>(values: unknown, allowed: T[], field: string): void {
  if (!Array.isArray(values)) {
    throw new Error(`Invalid ${field} ${JSON.stringify(values)}. Expected an array.`);
  }
  for (const value of values) {
    assertOneOf(value, allowed, field);
  }
}

/** Validates a characteristic and returns it with every UUID in the 128-bit form. */
function normalizeCharacteristic(
  characteristic: GattCharacteristicConfig,
): GattCharacteristicConfig {
  const uuid = normalizeUuid(characteristic?.uuid, 'characteristic');
  assertEachOneOf(characteristic?.properties, CHARACTERISTIC_PROPERTIES, 'characteristic property');
  assertEachOneOf(
    characteristic?.permissions,
    CHARACTERISTIC_PERMISSIONS,
    'characteristic permission',
  );
  if (characteristic.value !== undefined) {
    assertValidBytes(characteristic.value, 'characteristic');
  }
  const descriptors = characteristic.descriptors?.map((descriptor) => {
    const descriptorUuid = normalizeUuid(descriptor?.uuid, 'descriptor');
    if (descriptorUuid === CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
      throw new Error(
        `Descriptor ${descriptor.uuid} is the Client Characteristic Configuration descriptor, ` +
          'which the module publishes itself for every characteristic declaring "notify" or ' +
          '"indicate", and whose per-client value it answers from its own subscription tracking. ' +
          'Declaring a second one would shadow that, so remove it — the automatic one is already ' +
          'readable and writeable.',
      );
    }
    assertValidBytes(descriptor?.value, 'descriptor');
    if (descriptor.permissions !== undefined) {
      assertEachOneOf(descriptor.permissions, CHARACTERISTIC_PERMISSIONS, 'descriptor permission');
    }
    return { ...descriptor, uuid: descriptorUuid };
  });
  return descriptors
    ? { ...characteristic, uuid, descriptors }
    : { ...characteristic, uuid };
}

export async function createServer(
  services: GattServiceConfig[],
  options: CreateServerOptions = {},
): Promise<void> {
  // Rebuilt rather than mutated, so the caller's own configuration object is left as they wrote it.
  const normalizedServices = (services ?? []).map((service) => {
    const uuid = normalizeUuid(service?.uuid, 'service');
    if (service.type !== undefined) {
      assertOneOf(service.type, SERVICE_TYPES, 'service type');
    }
    return {
      ...service,
      uuid,
      characteristics: (service?.characteristics ?? []).map(normalizeCharacteristic),
    };
  });
  if (options.requestTimeoutMs !== undefined) {
    if (
      !Number.isInteger(options.requestTimeoutMs) ||
      options.requestTimeoutMs < 0 ||
      options.requestTimeoutMs >= ATT_TRANSACTION_TIMEOUT_MS
    ) {
      throw new Error(
        `Invalid request timeout ${JSON.stringify(options.requestTimeoutMs)}. Expected an integer ` +
          `between 0 and ${ATT_TRANSACTION_TIMEOUT_MS - 1} milliseconds — below the ATT ` +
          `transaction timeout of ${ATT_TRANSACTION_TIMEOUT_MS} ms, past which the central has ` +
          'already given up — where 0 disables the timeout.',
      );
    }
  }
  return nativeModule().createServer(normalizedServices, options);
}

/**
 * The longest duration `AdvertiseSettings.Builder.setTimeout` accepts. Applied on both platforms, so
 * one configuration behaves the same either side.
 */
const MAX_ADVERTISING_TIMEOUT_MS = 180_000;

const ADVERTISING_MODES: AdvertisingMode[] = ['lowPower', 'balanced', 'lowLatency'];
const ADVERTISING_TX_POWERS: AdvertisingTxPower[] = ['ultraLow', 'low', 'medium', 'high'];

function assertOneOf<T extends string>(value: unknown, allowed: T[], field: string): void {
  if (!allowed.includes(value as T)) {
    throw new Error(
      `Invalid ${field} ${JSON.stringify(value)}. Expected one of ${allowed
        .map((option) => JSON.stringify(option))
        .join(', ')}.`,
    );
  }
}

export async function startAdvertising(config: AdvertiseConfig = {}): Promise<void> {
  // Expanding an advertised UUID costs nothing on the wire: Android encodes it with
  // `BluetoothUuid.uuidToBytes`, documented as returning "the shortest representation", and sizes the
  // 31-byte budget the same way — so a 16-bit alias still goes out as two octets.
  const serviceUuids = config.serviceUuids?.map((uuid) => normalizeUuid(uuid, 'service'));
  if (config.mode !== undefined) {
    assertOneOf(config.mode, ADVERTISING_MODES, 'advertising mode');
  }
  if (config.txPowerLevel !== undefined) {
    assertOneOf(config.txPowerLevel, ADVERTISING_TX_POWERS, 'advertising tx power level');
  }
  if (config.timeoutMs !== undefined) {
    if (
      !Number.isInteger(config.timeoutMs) ||
      config.timeoutMs < 0 ||
      config.timeoutMs > MAX_ADVERTISING_TIMEOUT_MS
    ) {
      throw new Error(
        `Invalid advertising timeout ${JSON.stringify(config.timeoutMs)}. Expected an integer ` +
          `between 0 and ${MAX_ADVERTISING_TIMEOUT_MS} milliseconds, where 0 means no time limit.`,
      );
    }
  }
  for (const entry of config.manufacturerData ?? []) {
    // 16-bit field, so a wider value cannot be transmitted; Android only rejects negative ids.
    if (!Number.isInteger(entry?.companyId) || entry.companyId < 0 || entry.companyId > 0xffff) {
      throw new Error(
        `Invalid manufacturer company id ${JSON.stringify(entry?.companyId)}. A Bluetooth SIG ` +
          'Company Identifier is a 16-bit value, so it must be an integer between 0 and 65535.',
      );
    }
    assertValidBytes(entry.data, 'manufacturer');
  }
  const serviceData = config.serviceData?.map((entry) => {
    const uuid = normalizeUuid(entry?.uuid, 'service data');
    assertValidBytes(entry.data, 'service data');
    return { ...entry, uuid };
  });
  if (Platform.OS === 'ios') {
    // Warned about rather than rejected: these only tune the radio, so failing the call would force
    // a platform branch on every caller. The options iOS cannot express at all reject natively.
    const ignored = (
      [
        ['mode', config.mode],
        ['txPowerLevel', config.txPowerLevel],
        ['includeTxPowerLevel', config.includeTxPowerLevel],
      ] as const
    )
      .filter(([, value]) => value !== undefined)
      .map(([key]) => key);
    if (ignored.length > 0) {
      console.warn(
        `[expo-gatt-server] startAdvertising: iOS ignores ${ignored.join(', ')}. ` +
          'CBPeripheralManager.startAdvertising supports only CBAdvertisementDataLocalNameKey and ' +
          'CBAdvertisementDataServiceUUIDsKey, so there is nowhere to put them.',
      );
    }
  }
  return nativeModule().startAdvertising({ ...config, serviceUuids, serviceData });
}

/**
 * Does nothing when the module is unsupported: nothing can be advertising, and a teardown path is
 * the wrong place to raise a configuration error the setup path already reported.
 */
export function stopAdvertising(): void {
  ExpoGattServerModule?.stopAdvertising();
}

/**
 * Sends a notification, or an indication when `confirm` is set, to a connected central.
 *
 * An indication is acknowledged by the central with an `ATT_HANDLE_VALUE_CFM` and only one may be
 * outstanding at a time; a notification is fire-and-forget. The characteristic must declare the
 * property that matches — `indicate` for `confirm: true`, `notify` for `confirm: false` — or the
 * call rejects with `ERR_CONFIRM_UNSUPPORTED`. The Bluetooth Core Specification permits each
 * transmission only when its property is set (Vol 3, Part G, Table 3.5), and lets a client enable
 * the corresponding descriptor bit only then (Table 3.11), so a mismatch could never have been
 * legitimately requested by any client.
 *
 * **iOS never receives the flag.** `CBPeripheralManager.updateValue(_:for:onSubscribedCentrals:)`
 * has no confirm parameter; CoreBluetooth derives notification versus indication from the declared
 * properties alone. Because the property check above is enforced on both platforms, a characteristic
 * that declares exactly one of `notify` and `indicate` behaves identically either side. A
 * characteristic that declares **both** is the one case iOS cannot honour: Android sends what
 * `confirm` asks for, while iOS sends whatever CoreBluetooth chooses. Declare only the one you
 * intend to use if that matters.
 *
 * The promise settles when the platform reports the notification as delivered, not when the call
 * reaches the Bluetooth stack. A device may only have one notification outstanding at a time, so
 * sends issued while an earlier one is still in flight are queued in order rather than dropped;
 * awaiting the promise is what paces a stream against the link.
 *
 * Rejects with `ERR_NO_SUBSCRIBER` when the device has not enabled the transmission on the
 * characteristic — a notification to nobody is a failure, not a success. See
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
  assertValidBytes(value, 'notification');
  return nativeModule().sendNotification(
    deviceId,
    normalizeUuid(serviceUuid, 'service'),
    normalizeUuid(characteristicUuid, 'characteristic'),
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
  return nativeModule().sendResponse(deviceId, requestId, status, offset, value);
}

/**
 * Replaces the value a read of this characteristic is answered from. Does not notify anybody; use
 * `sendNotification` to push the new value to subscribed centrals.
 *
 * Rejects with `ERR_CHARACTERISTIC_NOT_FOUND` when the pair of UUIDs names nothing in the published
 * database, and with `ERR_NO_SERVER` when no server exists — previously both were silent no-ops, so
 * a mistyped UUID was indistinguishable from a working update.
 */
export async function updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
): Promise<void> {
  assertValidBytes(value, 'characteristic');
  return nativeModule().updateCharacteristicValue(
    normalizeUuid(serviceUuid, 'service'),
    normalizeUuid(characteristicUuid, 'characteristic'),
    value,
  );
}

/** Does nothing when the module is unsupported, for the same reason as `stopAdvertising`. */
export function stopServer(): void {
  ExpoGattServerModule?.stopServer();
}

/**
 * Reads the current Bluetooth adapter state. Safe to call before `createServer`, though iOS
 * cannot report anything more specific than `unknown` or `unauthorized` until a server exists,
 * because `CBPeripheralManager.state` requires an instantiated manager.
 *
 * Resolves to `unsupported` where the native module is absent, which is what that state already
 * means — no BLE peripheral support on this device — so a consumer branching on the state needs no
 * separate check.
 */
export async function getBluetoothState(): Promise<BluetoothState> {
  return ExpoGattServerModule?.getBluetoothState() ?? 'unsupported';
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
  return nativeModule().getMtu(deviceId);
}

/**
 * Lists the centrals the module currently considers connected. Resolves to an empty array when no
 * server exists.
 *
 * See `ConnectedDevice` for what "connected" means on each platform — Android reports connections
 * directly, while iOS can only derive them from ATT activity, so the two are not equivalent.
 *
 * Also resolves to an empty array where the native module is absent — nothing can be connected.
 */
export async function getConnectedDevices(): Promise<ConnectedDevice[]> {
  return ExpoGattServerModule?.getConnectedDevices() ?? [];
}

/**
 * Drops a connected central. **Android only.**
 *
 * Android calls `BluetoothGattServer.cancelConnection`, which "disconnects an established
 * connection, or cancels a connection attempt currently in progress". That method returns nothing
 * and reports no outcome, so the promise resolves once the request has been handed to the Bluetooth
 * stack, not once the central is gone — wait for `onDeviceDisconnected` for that.
 *
 * **iOS rejects with `ERR_UNSUPPORTED`, because CoreBluetooth cannot do this at all.** The whole of
 * `CBPeripheralManager` is `startAdvertising`, `stopAdvertising`,
 * `setDesiredConnectionLatency(_:for:)`, `addService`, `removeService`, `removeAllServices`,
 * `respond(to:withResult:)`, `updateValue(_:for:onSubscribedCentrals:)`,
 * `publishL2CAPChannel(withEncryption:)` and `unpublishL2CAPChannel` — there is no disconnect among
 * them, and `CBCentral` exposes only `identifier` and `maximumUpdateValueLength`.
 * `cancelPeripheralConnection(_:)` is a `CBCentralManager` method that takes a `CBPeripheral`, so it
 * belongs to the central role and cannot be turned around. Nothing here is approximated: dropping
 * the GATT database with `stopServer` is not documented as disconnecting anybody, so claiming it as
 * an equivalent would be an invention.
 *
 * Rejects with `ERR_DEVICE_DISCONNECTED` when the device is not connected and `ERR_NO_SERVER` when
 * no server exists.
 */
export async function disconnectDevice(deviceId: string): Promise<void> {
  return nativeModule().disconnectDevice(deviceId);
}

/**
 * Whether a GATT database is currently published and usable.
 *
 * `false` before `createServer`, after `stopServer`, and while Bluetooth is not powered on — both
 * platforms destroy the published database when the adapter goes down. The module re-publishes it on
 * the next transition to `poweredOn`, at which point this becomes `true` again without any further
 * call, so it is the right thing to check before advertising rather than remembering whether
 * `createServer` was ever called. Also `false` where the native module is absent.
 */
export async function isServerRunning(): Promise<boolean> {
  return ExpoGattServerModule?.isServerRunning() ?? false;
}

/**
 * Whether the peripheral is currently advertising.
 *
 * iOS reads `CBPeripheralManager.isAdvertising`. Android has no equivalent query —
 * `BluetoothLeAdvertiser` exposes none — so the module tracks it from `AdvertiseCallback`, and
 * additionally clears it when an `AdvertiseConfig.timeoutMs` elapses, because the platform stops
 * advertising at that limit without reporting it. Also `false` where the native module is absent.
 */
export async function isAdvertising(): Promise<boolean> {
  return ExpoGattServerModule?.isAdvertising() ?? false;
}

/**
 * Fires when a connection's MTU changes. See `MtuChangedEvent` for the difference in timing between
 * Android, which reports the change as it happens, and iOS, which can only sample the value when
 * the central next produces activity.
 */
export function addMtuChangedListener(
  listener: (event: MtuChangedEvent) => void,
): EventSubscription {
  return addListener('onMtuChanged', listener);
}

export function addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): EventSubscription {
  return addListener('onDeviceConnected', listener);
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
  return addListener('onDeviceDisconnected', listener);
}

export function addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
): EventSubscription {
  return addListener('onCharacteristicReadRequest', listener);
}

export function addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
): EventSubscription {
  return addListener('onCharacteristicWriteRequest', listener);
}

export function addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
): EventSubscription {
  return addListener('onNotificationSent', listener);
}

/**
 * Fires when a central enables notifications or indications on a characteristic. This is the
 * signal to start streaming: before it arrives the central receives nothing, and on Android
 * `sendNotification` has no subscriber to send to.
 */
export function addCharacteristicSubscribedListener(
  listener: (event: CharacteristicSubscribedEvent) => void,
): EventSubscription {
  return addListener('onCharacteristicSubscribed', listener);
}

/**
 * Fires when a central stops receiving updates for a characteristic, including when it
 * disconnects while still subscribed. This is the signal to stop streaming.
 */
export function addCharacteristicUnsubscribedListener(
  listener: (event: CharacteristicUnsubscribedEvent) => void,
): EventSubscription {
  return addListener('onCharacteristicUnsubscribed', listener);
}

/**
 * Fires whenever the Bluetooth adapter state changes. Delivered only while a server exists,
 * since state monitoring is tied to the server lifecycle on both platforms.
 */
export function addBluetoothStateChangedListener(
  listener: (event: BluetoothStateChangedEvent) => void,
): EventSubscription {
  return addListener('onBluetoothStateChanged', listener);
}
