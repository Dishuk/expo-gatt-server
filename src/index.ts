import { Platform, type EventSubscription } from 'expo-modules-core';

import {
  ATT_TRANSACTION_TIMEOUT_MS,
  CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
  MAX_ATTRIBUTE_VALUE_LENGTH,
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
  ServerPublicationFailedEvent,
  ConnectedDevice,
  DeviceMtu,
  MtuChangedEvent,
  GattServerEvents,
} from './ExpoGattServer.types';
import ExpoGattServerModule, { type ExpoGattServerModuleType } from './ExpoGattServerModule';

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
  type ServerPublicationFailedEvent,
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
  MAX_ATTRIBUTE_VALUE_LENGTH,
  CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
} from './ExpoGattServer.types';

/**
 * Whether the native module is present, and therefore whether anything else here can work.
 *
 * `false` on web, and in any binary that does not contain the module — Expo Go being the common case,
 * since it ships a fixed set of native modules. Synchronous and safe to call at module scope.
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

// Returned instead of thrown: listener teardown calls remove() even when module is absent.
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

/** The Bluetooth Base UUID — Core Spec Vol 3, Part B, §2.5.1. */
const BLUETOOTH_BASE_UUID = '00000000-0000-1000-8000-00805f9b34fb';

/**
 * Validates and normalizes UUID to lowercase 128-bit form. Core Spec Vol 3, Part B, §2.5.1.
 * Expansion: `short_value * 2^96 + Bluetooth_Base_UUID`. CBUUID accepts all three forms;
 * Java UUID.fromString requires 8-4-4-4-12 only. Both code and events use normalized form.
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

/**
 * Enforces MAX_ATTRIBUTE_VALUE_LENGTH. Core Spec Vol 3, Part F, §3.2.9.
 * Platforms cap notifications but do not bound app-supplied values; every path through here.
 */
function assertValidAttributeValue(value: unknown, field: string): void {
  assertValidBytes(value, field);
  const bytes = value as number[];
  if (bytes.length > MAX_ATTRIBUTE_VALUE_LENGTH) {
    throw new Error(
      `Invalid ${field} value of ${bytes.length} bytes. An attribute value may hold at most ` +
        `${MAX_ATTRIBUTE_VALUE_LENGTH} octets (Core Spec Vol 3, Part F, §3.2.9), and a longer one ` +
        'could never be notified or read in a single response.',
    );
  }
}

/**
 * Rejects a value written where a list belongs, which reaches the caller as `.map is not a function`
 * otherwise. Absent stays legal; each caller has its own default.
 */
function assertArrayOrAbsent(value: unknown, field: string): void {
  if (value !== undefined && !Array.isArray(value)) {
    throw new Error(`Invalid ${field} ${JSON.stringify(value)}. Expected an array.`);
  }
}

/**
 * Rejects wrong type for optional fields. Natives read as `as? Boolean ?: default` / `as? String`,
 * silently absent if wrong type; prevents `'false'` string advertising as connectable=true.
 */
function assertTypeOrAbsent(value: unknown, field: string, expected: 'boolean' | 'string'): void {
  if (value !== undefined && typeof value !== expected) {
    throw new Error(`Invalid ${field} ${JSON.stringify(value)}. Expected a ${expected}.`);
  }
}

/**
 * Validates all integer arguments before native pass. iOS: Int(double.rounded()) traps on NaN/∞.
 * Android: asDouble().toInt() maps NaN→0, truncates fractions. Must validate before native side.
 */
function assertValidInteger(
  value: unknown,
  field: string,
  min: number,
  max: number,
  explanation: string,
): void {
  if (!Number.isInteger(value) || (value as number) < min || (value as number) > max) {
    throw new Error(
      `Invalid ${field} ${JSON.stringify(value)}. ${explanation} It must be an integer between ` +
        `${min} and ${max}.`,
    );
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

function assertEachOneOf<T extends string>(values: unknown, allowed: T[], field: string): void {
  if (!Array.isArray(values)) {
    throw new Error(`Invalid ${field} ${JSON.stringify(values)}. Expected an array.`);
  }
  for (const value of values) {
    assertOneOf(value, allowed, field);
  }
}

/**
 * Rejects delegate with typo or non-boolean flags. Natives read as `as? Boolean ?: false`,
 * silently absent if wrong type. Must validate to catch silent failures.
 */
function assertValidDelegate(delegate: unknown, characteristicUuid: string): void {
  if (delegate === undefined) return;
  if (typeof delegate !== 'object' || delegate === null || Array.isArray(delegate)) {
    throw new Error(
      `Invalid delegate ${JSON.stringify(delegate)} on characteristic ${characteristicUuid}. ` +
        'Expected an object with optional boolean "read" and "write" properties.',
    );
  }
  for (const [key, value] of Object.entries(delegate)) {
    if (key !== 'read' && key !== 'write') {
      throw new Error(
        `Unknown delegate option ${JSON.stringify(key)} on characteristic ${characteristicUuid}. ` +
          'Only "read" and "write" are recognised, and an unrecognised one would publish the ' +
          'characteristic as though nothing had been delegated.',
      );
    }
    if (value !== undefined && typeof value !== 'boolean') {
      throw new Error(
        `Invalid delegate.${key} ${JSON.stringify(value)} on characteristic ` +
          `${characteristicUuid}. Expected a boolean.`,
      );
    }
  }
}

/**
 * Rejects unrecognized keys. Natives ignore unknown keys silently; misspelling causes silent failure.
 */
function assertNoUnknownKeys(value: unknown, allowed: readonly string[], what: string): void {
  // Check before Object.keys: null reports "Cannot convert" with no field name.
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(
      `Invalid ${what} options ${JSON.stringify(value)}. Expected an object with any of ` +
        `${allowed.map((option) => JSON.stringify(option)).join(', ')}.`,
    );
  }
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) {
      throw new Error(
        `Unknown ${what} option ${JSON.stringify(key)}. Recognised options are ` +
          `${allowed.map((option) => JSON.stringify(option)).join(', ')}. An unrecognised one is ` +
          'read by nothing, so it would be silently ignored rather than applied.',
      );
    }
  }
}

const SERVICE_KEYS = ['uuid', 'type', 'characteristics'] as const;
const CHARACTERISTIC_KEYS = [
  'uuid',
  'properties',
  'permissions',
  'value',
  'descriptors',
  'delegate',
] as const;
const DESCRIPTOR_KEYS = ['uuid', 'value', 'permissions'] as const;
const CREATE_SERVER_KEYS = ['requestTimeoutMs'] as const;
const ADVERTISE_KEYS = [
  'localName',
  'serviceUuids',
  'includeTxPowerLevel',
  'connectable',
  'mode',
  'txPowerLevel',
  'timeoutMs',
  'manufacturerData',
  'serviceData',
  'android',
] as const;
const ANDROID_ADVERTISE_KEYS = ['includeDeviceName', 'setAdapterName'] as const;
const MANUFACTURER_DATA_KEYS = ['companyId', 'data'] as const;
const SERVICE_DATA_KEYS = ['uuid', 'data'] as const;
const SEND_NOTIFICATION_KEYS = ['requireSubscription'] as const;

function normalizeCharacteristic(
  characteristic: GattCharacteristicConfig,
): GattCharacteristicConfig {
  const uuid = normalizeUuid(characteristic?.uuid, 'characteristic');
  assertNoUnknownKeys(characteristic, CHARACTERISTIC_KEYS, 'characteristic');
  assertEachOneOf(characteristic?.properties, CHARACTERISTIC_PROPERTIES, 'characteristic property');
  assertEachOneOf(
    characteristic?.permissions,
    CHARACTERISTIC_PERMISSIONS,
    'characteristic permission',
  );
  if (characteristic.value !== undefined) {
    assertValidAttributeValue(characteristic.value, 'characteristic');
  }
  assertValidDelegate(characteristic?.delegate, uuid);
  assertArrayOrAbsent(characteristic.descriptors, 'characteristic descriptors');
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
    assertNoUnknownKeys(descriptor, DESCRIPTOR_KEYS, 'descriptor');
    assertValidAttributeValue(descriptor?.value, 'descriptor');
    if (descriptor.permissions !== undefined) {
      assertEachOneOf(descriptor.permissions, CHARACTERISTIC_PERMISSIONS, 'descriptor permission');
    }
    return { ...descriptor, uuid: descriptorUuid };
  });
  return descriptors ? { ...characteristic, uuid, descriptors } : { ...characteristic, uuid };
}

/**
 * Rejects duplicate UUIDs: service UUIDs globally, characteristic UUIDs per service, descriptor UUIDs
 * per characteristic. Shadows resolve to first (Android) or last (iOS) match; CBMutableCharacteristic
 * raises NSInternalInconsistencyException for duplicate User Description/Presentation Format descriptors.
 * Same UUID in different services or different characteristics is legal.
 */
function assertUniqueUuids(
  services: {
    uuid: string;
    characteristics: { uuid: string; descriptors?: { uuid: string }[] }[];
  }[],
): void {
  const serviceUuids = new Set<string>();
  for (const service of services) {
    if (serviceUuids.has(service.uuid)) {
      throw new Error(
        `Duplicate service UUID ${service.uuid}. Two services declaring the same UUID cannot be ` +
          'told apart by sendNotification or updateCharacteristicValue, which address an attribute ' +
          'by service and characteristic UUID. Give each service its own UUID, or merge their ' +
          'characteristics into one service.',
      );
    }
    serviceUuids.add(service.uuid);

    const characteristicUuids = new Set<string>();
    for (const characteristic of service.characteristics) {
      if (characteristicUuids.has(characteristic.uuid)) {
        throw new Error(
          `Duplicate characteristic UUID ${characteristic.uuid} in service ${service.uuid}. Two ` +
            'characteristics declaring the same UUID within one service cannot be told apart by ' +
            'sendNotification or updateCharacteristicValue. The same characteristic UUID in a ' +
            'different service is fine.',
        );
      }
      characteristicUuids.add(characteristic.uuid);

      const descriptorUuids = new Set<string>();
      for (const descriptor of characteristic.descriptors ?? []) {
        if (descriptorUuids.has(descriptor.uuid)) {
          throw new Error(
            `Duplicate descriptor UUID ${descriptor.uuid} on characteristic ` +
              `${characteristic.uuid} in service ${service.uuid}. A characteristic may declare each ` +
              'descriptor once: iOS refuses a second User Description or Presentation Format ' +
              'descriptor outright, and on Android the repeat would shadow the first. The same ' +
              'descriptor UUID on a different characteristic is fine.',
          );
        }
        descriptorUuids.add(descriptor.uuid);
      }
    }
  }
}

/**
 * Race condition guard: stopServer is sync, createServer is async. Stop issued second reaches
 * native first; this epoch lets createServer detect and honor out-of-order calls.
 */
let serverStopEpoch = 0;

// Only most recent createServer may issue compensating stop for cancellation.
let serverStartEpoch = 0;

/** Carries `ERR_NO_SERVER`, the code both platforms already reject a stopped-before-published create with. */
function serverStoppedError(): Error {
  const error: Error & { code?: string } = new Error(
    '[expo-gatt-server] createServer was cancelled by a stopServer issued while it was still in flight. ' +
      'The server has been stopped again, so no database is published.',
  );
  error.code = 'ERR_NO_SERVER';
  return error;
}

export async function createServer(
  services: GattServiceConfig[],
  options: CreateServerOptions = {},
): Promise<void> {
  // Read before anything else; stops after this call are detected as out-of-order.
  const epoch = serverStopEpoch;
  // Rebuilt rather than mutated, so the caller's own configuration object is left as they wrote it.
  assertNoUnknownKeys(options, CREATE_SERVER_KEYS, 'createServer');
  // Empty array is legal for advertise-only peripheral; undefined must be rejected.
  if (!Array.isArray(services)) {
    throw new Error(
      `Invalid services ${JSON.stringify(services)}. Expected an array of service configurations. ` +
        'Pass [] to publish a database with no services of its own, which is what an advertise-only ' +
        'peripheral wants.',
    );
  }
  const normalizedServices = services.map((service) => {
    const uuid = normalizeUuid(service?.uuid, 'service');
    assertNoUnknownKeys(service, SERVICE_KEYS, 'service');
    if (service.type !== undefined) {
      assertOneOf(service.type, SERVICE_TYPES, 'service type');
    }
    // Empty characteristics array is legal; undefined must be rejected.
    if (!Array.isArray(service?.characteristics)) {
      throw new Error(
        `Invalid service characteristics ${JSON.stringify(service?.characteristics)} for service ` +
          `${uuid}. Expected an array of characteristic configurations. Pass [] for a service that ` +
          'declares none.',
      );
    }
    return {
      ...service,
      uuid,
      characteristics: service.characteristics.map(normalizeCharacteristic),
    };
  });
  // Checked on normalized UUIDs so `180d` and its 128-bit form are recognized as the same.
  assertUniqueUuids(normalizedServices);
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
  // Claim here, not on entry, so only validated calls reach native and take ownership.
  const generation = ++serverStartEpoch;
  try {
    await nativeModule().createServer(normalizedServices, options);
    // Bump once native accepts; platforms stop advertising as part of accepting new server.
    advertisingStopEpoch += 1;
  } finally {
    // If stop came in while create was in flight, stop again now (out-of-order detection).
    // Only most recent create should issue stop. Also bumps advertisingStopEpoch.
    if (serverStopEpoch !== epoch && serverStartEpoch === generation) {
      advertisingStopEpoch += 1;
      ExpoGattServerModule?.stopServer();
    }
  }
  if (serverStopEpoch !== epoch) {
    throw serverStoppedError();
  }
}

// AdvertiseSettings.Builder.setTimeout max, applied to both platforms for consistent behavior.
const MAX_ADVERTISING_TIMEOUT_MS = 180_000;

/**
 * Race condition guard: stopAdvertising is sync, startAdvertising is async. Stop issued second
 * reaches native first; this epoch lets startAdvertising detect and honor out-of-order calls.
 */
let advertisingStopEpoch = 0;

// Only most recent startAdvertising may issue compensating stop for cancellation.
let advertisingStartEpoch = 0;

// Error code ERR_ADVERTISE for cancelled startAdvertising.
function advertisingCancelledError(): Error {
  const error: Error & { code?: string } = new Error(
    '[expo-gatt-server] startAdvertising was cancelled by a stopAdvertising issued while it was still ' +
      'in flight. The advertisement has been stopped again, so nothing is on the air.',
  );
  error.code = 'ERR_ADVERTISE';
  return error;
}

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

/**
 * Begins advertising the published GATT database. Rejects with ERR_NO_SERVER if no database
 * published. Safe before createServer resolves; waits for in-flight publication. Replaces
 * current advertisement rather than adding a second one.
 */
export async function startAdvertising(config: AdvertiseConfig = {}): Promise<void> {
  // Read before anything else; stops after this call are detected as out-of-order.
  const epoch = advertisingStopEpoch;
  // Expand UUIDs here so both platforms use one spelling. Android encodes shortest form in 31-byte
  // budget; iOS contracts CBUUID in beginAdvertising. Both platforms' advertising form are aligned.
  assertNoUnknownKeys(config, ADVERTISE_KEYS, 'advertising');
  // != null check treats Platform.OS === 'android' ? {...} : null as absent, same as natives.
  if (config.android != null) {
    assertNoUnknownKeys(config.android, ANDROID_ADVERTISE_KEYS, 'advertising android');
  }
  assertArrayOrAbsent(config.serviceUuids, 'advertising serviceUuids');
  assertTypeOrAbsent(config.localName, 'advertising localName', 'string');
  assertTypeOrAbsent(config.connectable, 'advertising connectable', 'boolean');
  assertTypeOrAbsent(config.includeTxPowerLevel, 'advertising includeTxPowerLevel', 'boolean');
  assertTypeOrAbsent(
    config.android?.includeDeviceName,
    'advertising android.includeDeviceName',
    'boolean',
  );
  assertTypeOrAbsent(
    config.android?.setAdapterName,
    'advertising android.setAdapterName',
    'boolean',
  );
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
  assertArrayOrAbsent(config.manufacturerData, 'advertising manufacturerData');
  for (const entry of config.manufacturerData ?? []) {
    // Bluetooth SIG Company Identifier is 16-bit; Android rejects only negatives natively.
    if (!Number.isInteger(entry?.companyId) || entry.companyId < 0 || entry.companyId > 0xffff) {
      throw new Error(
        `Invalid manufacturer company id ${JSON.stringify(entry?.companyId)}. A Bluetooth SIG ` +
          'Company Identifier is a 16-bit value, so it must be an integer between 0 and 65535.',
      );
    }
    assertNoUnknownKeys(entry, MANUFACTURER_DATA_KEYS, 'manufacturer data');
    assertValidBytes(entry.data, 'manufacturer');
  }
  assertArrayOrAbsent(config.serviceData, 'advertising serviceData');
  const serviceData = config.serviceData?.map((entry) => {
    const uuid = normalizeUuid(entry?.uuid, 'service data');
    assertNoUnknownKeys(entry, SERVICE_DATA_KEYS, 'service data');
    assertValidBytes(entry.data, 'service data');
    return { ...entry, uuid };
  });
  if (Platform.OS === 'ios') {
    // Warn rather than reject: iOS cannot express mode/txPowerLevel/includeTxPowerLevel.
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
  // Claim here, not on entry, so only validated calls reach native and take ownership.
  const generation = ++advertisingStartEpoch;
  try {
    await nativeModule().startAdvertising({ ...config, serviceUuids, serviceData });
  } finally {
    if (advertisingStopEpoch !== epoch && advertisingStartEpoch === generation) {
      // If stop came in while start was in flight, stop again now (out-of-order detection).
      // Only most recent start should issue stop.
      ExpoGattServerModule?.stopAdvertising();
    }
  }
  if (advertisingStopEpoch !== epoch) {
    throw advertisingCancelledError();
  }
}

/**
 * Does nothing when module is unsupported. Cancels in-flight startAdvertising via advertisingStopEpoch.
 */
export function stopAdvertising(): void {
  advertisingStopEpoch += 1;
  ExpoGattServerModule?.stopAdvertising();
}

/**
 * Sends notification (confirm=false) or indication (confirm=true). Characteristic must declare
 * matching property (Core Spec Vol 3, Part G, Table 3.5). iOS ignores confirm flag; CoreBluetooth
 * chooses based on properties alone. Promise settles at stack acceptance (Android: delivery +
 * central confirm for indications; iOS: queued not delivered). Rejects ERR_NO_SUBSCRIBER if
 * device not subscribed; see requireSubscription option. Queueing is per-central on Android,
 * shared on iOS. Does not change ATT Read value; call updateCharacteristicValue separately.
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
  assertNoUnknownKeys(options, SEND_NOTIFICATION_KEYS, 'sendNotification');
  if (
    options.requireSubscription !== undefined &&
    typeof options.requireSubscription !== 'boolean'
  ) {
    throw new Error(
      `Invalid sendNotification option requireSubscription ` +
        `${JSON.stringify(options.requireSubscription)}. Expected a boolean.`,
    );
  }
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
 * Answers pending read or write request. Offset rebases response onto the request's offset;
 * offset=0 with whole value answers Read Blob correctly. Rejects REQUEST_NOT_FOUND,
 * REQUEST_DEVICE_MISMATCH, ERR_RESPONSE_OFFSET. Value not size-checked against MTU; central
 * continues via Read Blob if longer than PDU.
 */
export async function sendResponse(
  deviceId: string,
  requestId: number,
  status: number,
  offset: number,
  value: number[],
): Promise<void> {
  // Validate all three: requestId NaN traps on iOS; status wider than byte truncates on Android.
  assertValidInteger(
    requestId,
    'response request id',
    0,
    Number.MAX_SAFE_INTEGER,
    'A request id is the whole number the matching request event carried.',
  );
  assertValidInteger(status, 'response status', 0, 255, 'An ATT error code is a single byte.');
  assertValidInteger(
    offset,
    'response offset',
    0,
    0xffff,
    'An ATT offset is an unsigned 16-bit value.',
  );
  assertValidBytes(value, 'response');
  return nativeModule().sendResponse(deviceId, requestId, status, offset, value);
}

/**
 * Replaces value returned by ATT Read; does not notify. Call both this and sendNotification to
 * push and make readable. Rejects ERR_CHARACTERISTIC_NOT_FOUND, ERR_NO_SERVER; never silent.
 */
export async function updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
): Promise<void> {
  assertValidAttributeValue(value, 'characteristic');
  return nativeModule().updateCharacteristicValue(
    normalizeUuid(serviceUuid, 'service'),
    normalizeUuid(characteristicUuid, 'characteristic'),
    value,
  );
}

/**
 * Does nothing when module is unsupported. Stops advertising as well (both platforms).
 */
export function stopServer(): void {
  advertisingStopEpoch += 1;
  serverStopEpoch += 1;
  ExpoGattServerModule?.stopServer();
}

/**
 * Reads Bluetooth adapter state. Safe before createServer. iOS reports unknown/unauthorized only
 * until server exists (CBPeripheralManager.state requires instantiated manager). Returns unsupported
 * where module is absent.
 */
export async function getBluetoothState(): Promise<BluetoothState> {
  return ExpoGattServerModule?.getBluetoothState() ?? 'unsupported';
}

/**
 * Reads ATT MTU for connected device. Rejects ERR_DEVICE_DISCONNECTED, ERR_NO_SERVER.
 * Non-negotiated devices report spec default 23.
 */
export async function getMtu(deviceId: string): Promise<DeviceMtu> {
  return nativeModule().getMtu(deviceId);
}

/**
 * Lists currently connected centrals. Returns empty array when no server or module absent.
 * See ConnectedDevice: Android reports connections directly; iOS derives from ATT activity.
 */
export async function getConnectedDevices(): Promise<ConnectedDevice[]> {
  return ExpoGattServerModule?.getConnectedDevices() ?? [];
}

/**
 * Drops connected central. Android only: calls BluetoothGattServer.cancelConnection; promise
 * resolves when request reaches stack, not when disconnected (wait for onDeviceDisconnected).
 * iOS rejects ERR_UNSUPPORTED (no CBPeripheralManager method exists; cancelPeripheralConnection
 * is CBCentralManager only). Rejects ERR_DEVICE_DISCONNECTED, ERR_NO_SERVER.
 */
export async function disconnectDevice(deviceId: string): Promise<void> {
  return nativeModule().disconnectDevice(deviceId);
}

/**
 * Whether GATT database is currently published. False before createServer, after stopServer,
 * while Bluetooth is off (platforms destroy on adapter down), and where module is absent.
 * Module re-publishes on poweredOn transition automatically; check this before advertising.
 */
export async function isServerRunning(): Promise<boolean> {
  return ExpoGattServerModule?.isServerRunning() ?? false;
}

/**
 * Whether peripheral is advertising. iOS reads CBPeripheralManager.isAdvertising. Android
 * tracks via AdvertiseCallback and clears on AdvertiseConfig.timeoutMs (platform doesn't report).
 * False where module is absent.
 */
export async function isAdvertising(): Promise<boolean> {
  return ExpoGattServerModule?.isAdvertising() ?? false;
}

/**
 * Fires when a connection's MTU changes. See `MtuChangedEvent` for the difference in timing between
 * Android, which reports the change as it happens, and iOS, which can only sample the value when the
 * central next produces activity.
 */
export function addMtuChangedListener(
  listener: (event: MtuChangedEvent) => void,
): EventSubscription {
  return addListener('onMtuChanged', listener);
}

/**
 * Fires once per connected central, independently of any subscription.
 *
 * Android reports the connection itself, via `onConnectionStateChange`. iOS has no connection-level
 * callback, so a central is reported on its first ATT activity instead — a subscribe, read or write.
 * One that connects and never touches an attribute is not observable from the peripheral role at all.
 */
export function addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): EventSubscription {
  return addListener('onDeviceConnected', listener);
}

/**
 * Fires once when a central goes away.
 *
 * Android reports the disconnection itself. On iOS it is inferred, because CoreBluetooth never
 * reports one: losing the last subscription is treated as a disconnection, and every known central is
 * reported as disconnected when Bluetooth leaves `poweredOn`. So a central that only ever read or
 * wrote may not produce this event until Bluetooth is turned off or the server stops, and one that
 * deliberately unsubscribes but stays connected produces it early — CoreBluetooth delivers the same
 * callback for both and offers nothing to tell them apart.
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
 * Fires when a central enables notifications or indications on a characteristic — the signal to start
 * streaming. Before it arrives the central receives nothing, and `sendNotification` has no subscriber
 * to send to.
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
 * Fires when Bluetooth adapter state changes. Delivered only while server exists; state reported
 * once on server creation (Android: ACTION_STATE_CHANGED announces changes only).
 */
export function addBluetoothStateChangedListener(
  listener: (event: BluetoothStateChangedEvent) => void,
): EventSubscription {
  return addListener('onBluetoothStateChanged', listener);
}

/**
 * Fires when published database disappears unexpectedly (re-publication failed after createServer
 * resolved). Signal to call createServer again; isServerRunning stays false until then.
 */
export function addServerPublicationFailedListener(
  listener: (event: ServerPublicationFailedEvent) => void,
): EventSubscription {
  return addListener('onServerPublicationFailed', listener);
}
