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

/**
 * Stands in for a real subscription when there is no native module to subscribe to. Returned rather
 * than thrown: a listener registered in an effect is paired with a `remove()` in that effect's
 * teardown, which would otherwise crash on a value it never received.
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

/** The Bluetooth Base UUID — Core Spec Vol 3, Part B, §2.5.1. */
const BLUETOOTH_BASE_UUID = '00000000-0000-1000-8000-00805f9b34fb';

/**
 * Validates a UUID and returns it as the lowercase 128-bit form, expanding a 16-bit or 32-bit alias
 * onto the Bluetooth Base UUID. The specification defines the aliases as
 * `short_value * 2^96 + Bluetooth_Base_UUID` (Vol 3, Part B, §2.5.1), which lands the value in the
 * leading 32 bits — so the expansion is exactly a left-pad to eight hex digits plus the base UUID's
 * remaining groups.
 *
 * Normalising here rather than per platform is what makes one configuration portable: `CBUUID`
 * accepts all three forms, but Java's `UUID.fromString` requires the 8-4-4-4-12 form, so `'180D'`
 * used to be accepted on iOS and throw on Android. The specification also requires the conversion
 * before comparing UUIDs of different sizes, so the long form is the only spelling in which the
 * module's own lookups and a consumer's `===` against an event payload agree.
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
 * The bytes of a value the module will hold as an attribute, which the specification bounds at
 * `MAX_ATTRIBUTE_VALUE_LENGTH` however it came to be set.
 *
 * Both platforms already refuse a *client* write that assembles past that bound, and both cap a
 * notification at `min(mtu - 3, 512)` — but neither bounded a value the application supplied itself, so
 * a configured `value` or an `updateCharacteristicValue` could publish an attribute longer than any
 * attribute may be: readable only through a conformant Read Blob, and impossible to notify. Every path
 * that stores an attribute value goes through here so the one rule is applied once.
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
 * Rejects a value of the wrong primitive type where an optional one belongs.
 *
 * Both natives read these as `as? Boolean ?: default` / `as? String`, so a wrong type is not an error
 * there — it is simply absent, and the call resolves having quietly done something else. `connectable`
 * is the one that matters most: a truthy `'false'` advertises a connectable peripheral for an app that
 * asked for a beacon, and iOS never reaches the `ERR_UNSUPPORTED` it documents for it.
 */
function assertTypeOrAbsent(value: unknown, field: string, expected: 'boolean' | 'string'): void {
  if (value !== undefined && typeof value !== expected) {
    throw new Error(`Invalid ${field} ${JSON.stringify(value)}. Expected a ${expected}.`);
  }
}

/**
 * A whole number the native side will receive in a parameter declared as an integer.
 *
 * Every such argument is validated here rather than only where it happens to be used, because the
 * conversion is not forgiving and the two platforms fail differently: expo-modules-core turns a JS
 * number into a native `Int` with `Int(double.rounded())` on iOS — which *traps* on `NaN` or an
 * infinity, killing the process rather than rejecting the promise — and with `asDouble().toInt()` on
 * Android, which maps `NaN` to 0 and truncates a fraction. So an unvalidated argument is a crash on one
 * platform and a silently different value on the other. The natives re-check the same bounds, since the
 * module is reachable directly.
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

/**
 * Rejects a `delegate` that would silently do nothing.
 *
 * Both native layers read the two flags as `delegate["read"] as? Boolean ?: false`, so a typo or a
 * non-boolean is not an error there — it is simply absent. The characteristic then publishes as fully
 * automatic: the listener never fires, reads are answered from whatever value is cached, and nothing
 * anywhere reports a problem. `delegate` was the only sub-object `normalizeCharacteristic` passed
 * through unchecked, which is exactly the silent-drop failure `assertEachOneOf` exists to prevent for
 * properties and permissions.
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
 * Rejects a key no layer below will read.
 *
 * The same silent-drop failure `assertValidDelegate` exists for, generalised: every native parser reads
 * the keys it knows and ignores the rest, so a misspelling is not an error anywhere — it is simply
 * absent. `delegat` publishes a characteristic as fully automatic, `descriptor` publishes none,
 * `serviceUUIDs` advertises no service UUIDs and leaves a central filtering on one unable to find the
 * peripheral, and `requestTimeoutMS` silently keeps the default. None of them reports a problem, and
 * `delegate` was the only object guarded against it.
 */
function assertNoUnknownKeys(value: unknown, allowed: readonly string[], what: string): void {
  // Checked here rather than left to `Object.keys`, which reports a null as `Cannot convert undefined or
  // null to object` — naming neither the option nor the fix, which is the whole point of this function.
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
 * Rejects a configuration in which one name would identify more than one attribute, at every level of
 * the database.
 *
 * `sendNotification` and `updateCharacteristicValue` address an attribute by service and
 * characteristic UUID, and each platform resolves that pair to exactly one attribute — Android's
 * `getService` and `getCharacteristic` to the first match, iOS to the last service added — so a repeat
 * leaves the two platforms answering the same call about different attributes. This is the same
 * shadowing that already rejects a manually declared Client Characteristic Configuration descriptor.
 *
 * A repeated *descriptor* UUID within one characteristic belongs to the same rule and is far less
 * forgiving: `CBMutableCharacteristic.descriptors` raises `NSInternalInconsistencyException` for a
 * second User Description or Presentation Format descriptor, and an Objective-C exception cannot be
 * caught from Swift — so the configuration that merely shadowed an attribute on Android terminated the
 * application on iOS. Checking all three levels here is what makes that unreachable, rather than
 * checking the two levels that happened to be reported.
 *
 * The same characteristic UUID in *different* services stays legal: the specification permits it, and
 * the pair of UUIDs still names one attribute. The same descriptor UUID on *different* characteristics
 * is legal for the same reason.
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
 * Bumped by everything that asks the server to stop, and read by `createServer` before it hands over — the
 * same hazard `advertisingStopEpoch` covers, for the same reason.
 *
 * `stopServer` is a synchronous Expo `Function`, so its body runs on the JavaScript thread the moment it is
 * called, while `createServer` is an `AsyncFunction` whose body runs later on Expo's worker queue. A stop
 * issued *second* therefore reaches the native side first, finds no manager to stop, and the create behind
 * it then publishes the whole database anyway — leaving a server the application explicitly asked not to
 * have. Neither platform can tell the two apart, because neither is told which call the application made
 * first; JavaScript is single-threaded, so this is the only place that knows.
 *
 * iOS carries a `serverStopEpoch` of its own, but it is read inside the `AsyncFunction` body — after the
 * JavaScript call has already returned — so it only ever catches a stop that lands while the configuration
 * is still being parsed, not the ordinary mount/unmount case. Android has no equivalent at all.
 */
let serverStopEpoch = 0;

/**
 * Counts `createServer` calls, so only the most recent one may issue the compensating stop that a cancelled
 * create uses to undo itself — the same rule, and for the same reason, as `advertisingStartEpoch`.
 */
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
  // Read before anything else, so every stop issued from here on counts as having come after this call.
  const epoch = serverStopEpoch;
  // Rebuilt rather than mutated, so the caller's own configuration object is left as they wrote it.
  assertNoUnknownKeys(options, CREATE_SERVER_KEYS, 'createServer');
  // `services ?? []` silently turned a missing list into an empty database, so a `loadServices()` that
  // returned `undefined` on a failure path published a server with nothing in it and resolved. An
  // *explicitly* empty list stays legal — it is how an advertise-only peripheral is built, since
  // `startAdvertising` requires a published database — but it now has to be written.
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
    // Required for the reason `services` is, one level down: `?? []` turned a loader that returned
    // `undefined` on its failure path into a service published with nothing in it, which a central
    // discovers and finds empty. `[]` stays legal and has to be written.
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
  // Checked on the normalised UUIDs, so a service written as `180d` and another as its 128-bit
  // expansion are recognised as the one UUID they are.
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
  // Claimed here rather than on entry, so only a call that actually reaches the native side takes
  // ownership. Claiming it first meant a create rejected by the validation above — which never reached
  // any server — still counted as the most recent one, and the check below then refused the compensating
  // stop to the create genuinely in flight. That create rejected saying the database was not published
  // while it was, which is the exact outcome this whole mechanism exists to prevent.
  const generation = ++serverStartEpoch;
  // Both natives stop advertising as part of accepting a new server, so a `startAdvertising` still in
  // flight has had the radio taken from under it and must not go on to resolve as though it were on the
  // air. `stopServer` bumps this for the same reason.
  advertisingStopEpoch += 1;
  try {
    await nativeModule().createServer(normalizedServices, options);
  } finally {
    // In a `finally`, so a create whose native call rejects still releases its claim. Left to the
    // success path alone, a rejected create stayed the most recent one for good and every later
    // cancellation silently stopped compensating.
    if (serverStopEpoch !== epoch && serverStartEpoch === generation) {
      // The application asked to stop while this create was in flight, and the native side may or may
      // not have seen the two in that order — so the server is stopped again here rather than left to an
      // ordering nothing guarantees.
      //
      // Only the most recent create may do that: `stopServer` is not addressed to a particular server,
      // so a later create that already replaced this one — and resolved — would be torn down by a stop
      // meant to undo a call the application had abandoned. That create owns the server now, and cancels
      // itself the same way if it needs to.
      //
      // Counted as a stop of the advertisement as well, because it is one: both platforms stop
      // advertising as part of stopping the server, which is why the public `stopServer` bumps both
      // epochs. This path calls the native module directly and so bypassed that, leaving a
      // `startAdvertising` issued after the application's stop — and still in flight when this one lands
      // — to resolve as though it were on the air while this stop had just taken the radio from it.
      advertisingStopEpoch += 1;
      ExpoGattServerModule?.stopServer();
    }
  }
  if (serverStopEpoch !== epoch) {
    throw serverStoppedError();
  }
}

/**
 * The longest duration `AdvertiseSettings.Builder.setTimeout` accepts, applied on both platforms so
 * one configuration behaves the same either side.
 */
const MAX_ADVERTISING_TIMEOUT_MS = 180_000;

/**
 * Bumped by everything that asks for advertising to stop. `startAdvertising` reads it before calling in
 * and again once the native call has resolved, so a stop the application issued while the start was in
 * flight is honoured whichever order the two reach the native side in.
 *
 * **That order is not guaranteed.** `stopAdvertising` is a synchronous Expo `Function`, so its body runs
 * on the JavaScript thread the moment it is called, while `startAdvertising` is an `AsyncFunction`, whose
 * body runs later on Expo's own worker queue. A stop issued *second* can therefore reach the manager
 * first — and be read as the baseline by the start that follows it, which then passes its own
 * generation check and puts the radio on the air after the application explicitly asked for the
 * opposite. Neither platform can tell the two apart, because neither is told which call the application
 * made first; JavaScript is single-threaded, so this is the only place that knows.
 */
let advertisingStopEpoch = 0;

/**
 * Counts `startAdvertising` calls, so only the most recent one may issue the compensating stop that a
 * cancelled start uses to undo itself.
 *
 * Without it that stop is unconditional, and stops whatever is on the air rather than "the
 * advertisement this call put there". In `start(A); stop(); await start(B);` the stop cancels A, B
 * reads the bumped epoch and resolves normally — and then A's native call finally returns, sees the
 * epoch moved, and issues a stop that takes B off the air. The caller awaited B, B resolved, nothing
 * is advertising, and no promise ever reported a failure.
 */
let advertisingStartEpoch = 0;

/**
 * Carries `ERR_ADVERTISE`, the code a stop already gives a start it cancelled natively, so a consumer
 * branching on the code cannot tell the two apart — the outcome is the same either way.
 */
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
 * Begins advertising the published GATT database.
 *
 * Rejects with `ERR_NO_SERVER` unless a database is actually published — before `createServer`, after a
 * service failed to publish, and while Bluetooth is down. Advertising a half-built or empty database
 * would expose it to scanners, which is worse than not advertising. `isServerRunning` reports the same
 * condition.
 *
 * A publication still in flight is waited for on both platforms, so the call is safe before
 * `createServer` resolves and from a `poweredOn` event handler. A wait is settled rather than left
 * pending if the publication fails, the server is stopped, or Bluetooth goes off.
 *
 * Calling it again replaces the current advertisement rather than adding a second one.
 */
export async function startAdvertising(config: AdvertiseConfig = {}): Promise<void> {
  // Read before anything else, so every stop issued from here on counts as having come after this call.
  const epoch = advertisingStopEpoch;
  // Expanded here so both platforms are addressed with one spelling. It costs nothing on the wire:
  // Android encodes an advertised UUID as "the shortest representation" and sizes the 31-byte budget
  // the same way, and iOS — where `CBUUID` would otherwise advertise the full sixteen octets it was
  // built from — contracts it back in `beginAdvertising`. See `CBUUID.advertisedForm`.
  assertNoUnknownKeys(config, ADVERTISE_KEYS, 'advertising');
  // `!= null`, so `Platform.OS === 'android' ? { … } : null` reads as absent, which is how both
  // natives already read a missing block.
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
    // 16-bit field, so a wider value cannot be transmitted; Android only rejects negative ids.
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
  // Claimed here rather than on entry, and released in a `finally`, for the same reasons as
  // `createServer`'s: a start rejected by the validation above never reached the radio and must not
  // take ownership of it from the start that did, and a start whose native call rejects must not keep
  // that ownership for good.
  const generation = ++advertisingStartEpoch;
  try {
    await nativeModule().startAdvertising({ ...config, serviceUuids, serviceData });
  } finally {
    if (advertisingStopEpoch !== epoch && advertisingStartEpoch === generation) {
      // The application asked to stop while this start was in flight, and the native side may or may
      // not have seen the two in that order — so the advertisement is stopped again here rather than
      // left to an ordering nothing guarantees. One stop means one thing.
      //
      // Only the most recent start may do that, though: `stopAdvertising` is not addressed to a
      // particular advertisement, so a later start that already replaced this one — and resolved —
      // would be taken off the air by a stop meant to undo a call the application had abandoned. That
      // start owns the radio now, and cancels itself the same way if it needs to.
      ExpoGattServerModule?.stopAdvertising();
    }
  }
  if (advertisingStopEpoch !== epoch) {
    throw advertisingCancelledError();
  }
}

/**
 * Does nothing when the module is unsupported: nothing can be advertising, and a teardown path is the
 * wrong place to raise a configuration error the setup path already reported.
 *
 * Cancels a `startAdvertising` still in flight, whichever order the two reach the native side in — see
 * `advertisingStopEpoch`.
 */
export function stopAdvertising(): void {
  advertisingStopEpoch += 1;
  ExpoGattServerModule?.stopAdvertising();
}

/**
 * Sends a notification, or an indication when `confirm` is set, to a connected central.
 *
 * The characteristic must declare the matching property — `indicate` for `confirm: true`, `notify`
 * for `confirm: false` — or the call rejects with `ERR_CONFIRM_UNSUPPORTED`. The Core Specification
 * permits each transmission only when its property is set (Vol 3, Part G, Table 3.5) and lets a
 * client enable the corresponding descriptor bit only then (Table 3.11), so a mismatch could never
 * have been legitimately requested by any client.
 *
 * **iOS never receives the flag**: `updateValue(_:for:onSubscribedCentrals:)` has no confirm
 * parameter, and CoreBluetooth derives notification versus indication from the declared properties
 * alone. A characteristic declaring exactly one of the two therefore behaves identically either side;
 * one declaring **both** is the case iOS cannot honour, since Android sends what `confirm` asks for
 * while iOS sends whatever CoreBluetooth chooses.
 *
 * The promise settles later than the call reaching the Bluetooth stack, but **what it reports differs
 * by platform, and the difference cannot be removed**:
 *
 * - **Android** resolves it from `onNotificationSent`, which the platform delivers once the stack has
 *   finished transmitting — and, for an indication, once the central has confirmed. A device may have
 *   only one notification outstanding at a time, so sends issued while an earlier one is in flight are
 *   queued in order rather than dropped, and awaiting the promise paces a stream against the link.
 * - **iOS** resolves it once CoreBluetooth accepts the payload for transmission. The peripheral role
 *   has no delivery callback at all — `peripheralManagerIsReady(toUpdateSubscribers:)` reports only
 *   that the transmit queue has space — so a resolved promise there means *queued*, not *delivered*,
 *   and an indication's confirmation is never surfaced. Awaiting still paces a stream, because a
 *   payload the queue cannot take is held until it can.
 *
 * So treat a resolution as "the platform took it" rather than "the central has it", and do not build
 * an application-level acknowledgement out of it — have the central write back instead.
 *
 * Rejects with `ERR_NO_SUBSCRIBER` when the device has not enabled the transmission on the
 * characteristic. See `options.requireSubscription` to send anyway where the platform allows it.
 *
 * **Queueing is per central on Android and shared on iOS**, because the platforms are. Android allows
 * one notification in flight per device and the module keeps a queue for each, so a link that has gone
 * quiet holds up only its own sends. CoreBluetooth has a single transmit queue for the peripheral and
 * one `peripheralManagerIsReady(toUpdateSubscribers:)` to say it has drained, so a payload it will not
 * take parks every later send behind it whichever central it was addressed to — for at most 35 s, after
 * which the parked entry is abandoned and the queue moves on. The 64-entry bound is counted per central
 * on both.
 *
 * **Does not change the value a read returns.** Pushing a value to subscribers and setting the value
 * an ATT Read is answered from are separate operations; `updateCharacteristicValue` does the latter,
 * so call both when a value should be pushed *and* readable.
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
  // Every one of the three numbers is checked, not only the two whose misuse produces a wrong
  // *answer*. `requestId` was the argument nothing validated, and it is the one that fails worst: on
  // iOS `NaN` reaches `Int(double.rounded())` inside expo-modules-core and traps, killing the process
  // before this module sees the call, while on Android the same value silently becomes request 0.
  assertValidInteger(
    requestId,
    'response request id',
    0,
    Number.MAX_SAFE_INTEGER,
    'A request id is the whole number the matching request event carried.',
  );
  // Android narrows the status to a byte on its way into the Bluetooth stack, so a wider value would
  // be truncated into an unrelated ATT error rather than rejected.
  assertValidInteger(status, 'response status', 0, 255, 'An ATT error code is a single byte.');
  // A negative offset would be rebased into a slice beyond the value's end on both platforms rather
  // than reported.
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
 * Replaces the value a read of this characteristic is answered from. Does not notify anybody, and
 * `sendNotification` does not do this — use both to push a value and make it readable.
 *
 * A value stored here is never overwritten by a delegated write batch that was already outstanding: the
 * value that batch held for this characteristic is dropped instead.
 *
 * Rejects with `ERR_CHARACTERISTIC_NOT_FOUND` when the pair of UUIDs names nothing in the published
 * database, and with `ERR_NO_SERVER` when no server exists, rather than resolving silently and
 * leaving a mistyped UUID indistinguishable from a working update.
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
 * Does nothing when the module is unsupported, for the same reason as `stopAdvertising`.
 *
 * Stopping the server stops advertising with it on both platforms, so this cancels a `startAdvertising`
 * still in flight too.
 */
export function stopServer(): void {
  advertisingStopEpoch += 1;
  serverStopEpoch += 1;
  ExpoGattServerModule?.stopServer();
}

/**
 * Reads the current Bluetooth adapter state. Safe to call before `createServer`, though iOS cannot
 * report anything more specific than `unknown` or `unauthorized` until a server exists, because
 * `CBPeripheralManager.state` requires an instantiated manager.
 *
 * Resolves to `unsupported` where the native module is absent, which is what that state already means,
 * so a consumer branching on the state needs no separate check.
 */
export async function getBluetoothState(): Promise<BluetoothState> {
  return ExpoGattServerModule?.getBluetoothState() ?? 'unsupported';
}

/**
 * Reads the current ATT MTU for a connected device, so payloads can be sized before they are sent.
 *
 * Rejects with `ERR_DEVICE_DISCONNECTED` when the device is not connected, and with `ERR_NO_SERVER`
 * when no server exists. A device that has not negotiated an MTU reports the specification default of
 * 23 rather than failing — that default is what the link carries until a negotiation happens.
 */
export async function getMtu(deviceId: string): Promise<DeviceMtu> {
  return nativeModule().getMtu(deviceId);
}

/**
 * Lists the centrals the module currently considers connected. Resolves to an empty array when no
 * server exists, and where the native module is absent.
 *
 * See `ConnectedDevice` for what "connected" means on each platform — Android reports connections
 * directly, while iOS can only derive them from ATT activity, so the two are not equivalent.
 */
export async function getConnectedDevices(): Promise<ConnectedDevice[]> {
  return ExpoGattServerModule?.getConnectedDevices() ?? [];
}

/**
 * Drops a connected central. **Android only.**
 *
 * Android calls `BluetoothGattServer.cancelConnection`, which reports no outcome, so the promise
 * resolves once the request has been handed to the Bluetooth stack, not once the central is gone —
 * wait for `onDeviceDisconnected` for that.
 *
 * **iOS rejects with `ERR_UNSUPPORTED`, because CoreBluetooth cannot do this at all.** No
 * `CBPeripheralManager` method drops a central, and `cancelPeripheralConnection(_:)` is a
 * `CBCentralManager` method taking a `CBPeripheral`, so it belongs to the central role. Nothing is
 * approximated: `stopServer` is not documented as disconnecting anybody.
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
 * `false` before `createServer`, after `stopServer`, while Bluetooth is not powered on — both
 * platforms destroy the published database when the adapter goes down — and where the native module
 * is absent. The module re-publishes on the next transition to `poweredOn`, at which point this
 * becomes `true` again without any further call, so it is the right thing to check before advertising
 * rather than remembering whether `createServer` was called.
 */
export async function isServerRunning(): Promise<boolean> {
  return ExpoGattServerModule?.isServerRunning() ?? false;
}

/**
 * Whether the peripheral is currently advertising.
 *
 * iOS reads `CBPeripheralManager.isAdvertising`. Android exposes no equivalent query, so the module
 * tracks it from `AdvertiseCallback` and additionally clears it when an `AdvertiseConfig.timeoutMs`
 * elapses, because the platform stops advertising at that limit without reporting it. Also `false`
 * where the native module is absent.
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
 * Fires whenever the Bluetooth adapter state changes. Delivered only while a server exists,
 * since state monitoring is tied to the server lifecycle on both platforms.
 *
 * The state as it stands is reported once when the server is created, so a consumer that renders from
 * this event alone starts from the truth rather than from a placeholder. Android otherwise said nothing
 * until the user happened to toggle Bluetooth, because `ACTION_STATE_CHANGED` announces only changes.
 */
export function addBluetoothStateChangedListener(
  listener: (event: BluetoothStateChangedEvent) => void,
): EventSubscription {
  return addListener('onBluetoothStateChanged', listener);
}

/**
 * Fires when the published database goes away for a reason no promise reported — a re-publication that
 * failed after `createServer` had already resolved. See `ServerPublicationFailedEvent`.
 *
 * This is the signal to call `createServer` again: nothing retries a failed registration, and
 * `isServerRunning` stays `false` until something does.
 */
export function addServerPublicationFailedListener(
  listener: (event: ServerPublicationFailedEvent) => void,
): EventSubscription {
  return addListener('onServerPublicationFailed', listener);
}
