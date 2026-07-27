import {
  GATT_SUCCESS,
  addCharacteristicReadRequestListener,
  addCharacteristicSubscribedListener,
  addCharacteristicUnsubscribedListener,
  addCharacteristicWriteRequestListener,
  addDeviceConnectedListener,
  addDeviceDisconnectedListener,
  addNotificationSentListener,
  createServer,
  disconnectDevice,
  getConnectedDevices,
  isAdvertising,
  isServerRunning,
  isSupported,
  sendNotification,
  sendResponse,
  startAdvertising,
  stopAdvertising,
  stopServer,
  updateCharacteristicValue,
  type EventSubscription,
  type GattServiceConfig,
} from 'expo-gatt-server';
import { useCallback, useEffect, useState } from 'react';
import {
  PermissionsAndroid,
  Platform,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

// Short forms on purpose: the shared layer expands them onto the Bluetooth Base UUID, so both
// platforms accept them. Event payloads report the 128-bit form regardless.
const SERVICE_UUID = '180d';
const CHARACTERISTIC_UUID = '2a37';
const USER_DESCRIPTION_UUID = '2901';

// iOS exposes no API for the local Bluetooth address, and advertises behind a random address that
// rotates roughly every fifteen minutes, so a scanner cannot be pointed at this device by address.
// A name it broadcasts itself is the only handle it can offer, and four hex digits are enough to
// tell two phones running the harness apart. The width is not arbitrary: the advertisement holds 31
// bytes, of which the flags take 3 and `180d` takes 4, leaving 24 for a name whose own header costs
// 2 -- and the 17-character prefix plus four digits comes to 21, just inside it. A longer suffix
// would push the name into the scan response, where it is answered only if the scanner asks.
//
// Regenerated per launch rather than persisted: it only has to be unique among the devices running
// the harness at the same moment, and keeping it would mean a storage dependency the example has no
// other use for.
const DEVICE_SUFFIX = Math.floor(Math.random() * 0x10000)
  .toString(16)
  .padStart(4, '0');
const ADVERTISED_NAME = `expo-gatt-server-${DEVICE_SUFFIX}`;

const utf8 = (text: string): number[] => Array.from(new TextEncoder().encode(text));

const SERVICES: GattServiceConfig[] = [
  {
    uuid: SERVICE_UUID,
    type: 'primary',
    characteristics: [
      {
        uuid: CHARACTERISTIC_UUID,
        properties: ['read', 'write', 'notify'],
        permissions: ['readable', 'writeable'],
        value: [0, 60],
        // Without this the harness demonstrates neither listener: a characteristic with a cached value
        // has its reads answered natively, and `responseNeeded` is only ever true for a delegated
        // write — so `onCharacteristicReadRequest` never fired and the `sendResponse` branch below was
        // unreachable, in the one example the guides point readers at.
        delegate: { read: true, write: true },
        descriptors: [{ uuid: USER_DESCRIPTION_UUID, value: utf8('Heart Rate Measurement') }],
      },
    ],
  },
];

// The value the harness reports and notifies, outside React because nothing renders it: it is read and
// written only from event handlers and from the read-request listener. Holding it in a `useRef` made
// every action that touched it a ref-reading closure, which is what the button list is built out of.
let counter = 60;

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const [deviceId, setDeviceId] = useState<string | null>(null);

  const append = useCallback((line: string) => {
    setLog((prev) => [`${new Date().toISOString().slice(11, 19)}  ${line}`, ...prev].slice(0, 200));
  }, []);

  useEffect(() => {
    const subscriptions: EventSubscription[] = [
      addDeviceConnectedListener((event) => {
        setDeviceId(event.deviceId);
        append(`onDeviceConnected ${event.deviceId} ${event.name ?? ''}`);
      }),
      addDeviceDisconnectedListener((event) => {
        setDeviceId((current) => (current === event.deviceId ? null : current));
        append(`onDeviceDisconnected ${event.deviceId}`);
      }),
      addCharacteristicReadRequestListener((event) => {
        append(`onCharacteristicReadRequest req=${event.requestId} offset=${event.offset}`);
        // `offset: 0` because the value passed is the whole attribute; the module rebases the response
        // onto the offset the request asked for. Passing `event.offset` here with an unsliced value
        // would resend the prefix on a Read Blob continuation.
        sendResponse(event.deviceId, event.requestId, GATT_SUCCESS, 0, [0, counter]).catch(
          (error: unknown) => append(`sendResponse failed: ${String(error)}`),
        );
      }),
      addCharacteristicWriteRequestListener((event) => {
        append(
          `onCharacteristicWriteRequest req=${event.requestId} value=[${event.value.join(',')}]`,
        );
        if (event.responseNeeded) {
          sendResponse(
            event.deviceId,
            event.requestId,
            GATT_SUCCESS,
            event.offset,
            event.value,
          ).catch((error: unknown) => append(`sendResponse failed: ${String(error)}`));
        }
      }),
      addNotificationSentListener((event) => {
        append(`onNotificationSent ${event.characteristicUuid} status=${event.status}`);
      }),
      addCharacteristicSubscribedListener((event) => {
        setDeviceId(event.deviceId);
        append(`onCharacteristicSubscribed ${event.deviceId} ${event.characteristicUuid}`);
      }),
      addCharacteristicUnsubscribedListener((event) => {
        append(`onCharacteristicUnsubscribed ${event.deviceId} ${event.characteristicUuid}`);
      }),
    ];

    return () => {
      subscriptions.forEach((subscription) => subscription.remove());
    };
  }, [append]);

  const requestPermissions = useCallback(async () => {
    if (Platform.OS !== 'android') {
      append('iOS prompts for Bluetooth on first use');
      return;
    }
    const result = await PermissionsAndroid.requestMultiple([
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
      PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
    ]);
    append(JSON.stringify(result));
  }, [append]);

  // Invoked when a button is pressed rather than while the action list is built, so the list holds the
  // actions themselves rather than closures already wrapped around them.
  const run = useCallback(
    async (label: string, action: () => void | Promise<void>) => {
      try {
        await action();
        append(`${label}: ok`);
      } catch (error) {
        append(`${label}: ${String(error)}`);
      }
    },
    [append],
  );

  // Held in callbacks rather than written inline in the action list below, so the list stays a plain
  // description of the buttons.
  const nextValue = useCallback(() => {
    counter = (counter + 1) % 256;
    return updateCharacteristicValue(SERVICE_UUID, CHARACTERISTIC_UUID, [0, counter]);
  }, []);

  const notifyValue = useCallback(() => {
    if (!deviceId) {
      throw new Error('no connected device');
    }
    return sendNotification(deviceId, SERVICE_UUID, CHARACTERISTIC_UUID, [0, counter], false);
  }, [deviceId]);

  // Kept as data rather than inline JSX so every cell of the grid is laid out identically, and so the
  // count stays even — an odd one out would stretch across its whole row.
  const actions: Action[] = [
    { label: 'requestPermissions', action: requestPermissions },
    { label: 'createServer', action: () => createServer(SERVICES) },
    {
      label: 'startAdvertising',
      action: () =>
        // `includeTxPowerLevel` is deliberately omitted: iOS cannot express it, so passing it at all —
        // even as `false` — makes the shared layer warn on every call in the harness the guides point at.
        startAdvertising({
          localName: ADVERTISED_NAME,
          serviceUuids: [SERVICE_UUID],
          connectable: true,
        }),
    },
    { label: 'stopAdvertising', tone: 'stop', action: () => stopAdvertising() },
    {
      label: 'updateCharacteristicValue',
      action: nextValue,
    },
    { label: 'sendNotification', action: notifyValue },
    {
      label: 'getConnectedDevices',
      tone: 'query',
      action: async () => {
        append(JSON.stringify(await getConnectedDevices()));
      },
    },
    {
      label: 'status',
      tone: 'query',
      action: async () => {
        append(
          `supported=${isSupported()} running=${await isServerRunning()} ` +
            `advertising=${await isAdvertising()} name=${ADVERTISED_NAME}`,
        );
      },
    },
    {
      label: 'disconnectDevice',
      tone: 'stop',
      action: () => {
        if (!deviceId) {
          throw new Error('no connected device');
        }
        return disconnectDevice(deviceId);
      },
    },
    { label: 'stopServer', tone: 'stop', action: () => stopServer() },
  ];

  return (
    <View style={styles.container}>
      <StatusBar barStyle="dark-content" />

      <View style={styles.header}>
        <Text style={styles.title}>expo-gatt-server</Text>
        <View style={[styles.badge, deviceId ? styles.badgeOn : styles.badgeOff]}>
          <Text style={styles.badgeLabel}>{deviceId ? 'connected' : 'no device'}</Text>
        </View>
      </View>
      {/* Shown whether or not advertising has been started: the point of the name is to be read off
          the screen and typed into a scanner's filter, which is something to do before the radio is
          on rather than after. */}
      <Text style={styles.advertisedName} numberOfLines={1}>
        {ADVERTISED_NAME}
      </Text>
      <Text style={styles.deviceId} numberOfLines={1}>
        {deviceId ?? '—'}
      </Text>

      <View style={styles.grid}>
        {actions.map((entry) => (
          <Button
            key={entry.label}
            label={entry.label}
            tone={entry.tone}
            onPress={() => run(entry.label, entry.action)}
          />
        ))}
      </View>

      <View style={styles.logHeader}>
        <Text style={styles.logTitle}>log</Text>
        <Text style={styles.logCount}>{log.length}</Text>
        <View style={styles.spacer} />
        <Pressable
          style={({ pressed }) => [styles.clear, pressed && styles.pressed]}
          onPress={() => setLog([])}>
          <Text style={styles.clearLabel}>clear</Text>
        </Pressable>
      </View>
      <ScrollView style={styles.log} contentContainerStyle={styles.logContent}>
        {log.length === 0 ? (
          <Text style={styles.logEmpty}>no events yet</Text>
        ) : (
          log.map((line, index) => (
            <Text key={`${index}-${line}`} style={styles.logLine}>
              {line}
            </Text>
          ))
        )}
      </ScrollView>
    </View>
  );
}

/** `stop` and `query` only tint the cell; every tone is the same size, so the grid stays regular. */
type Action = { label: string; tone?: 'stop' | 'query'; action: () => void | Promise<void> };

function Button({ label, tone, onPress }: Omit<Action, 'action'> & { onPress: () => void }) {
  return (
    <Pressable
      style={({ pressed }) => [
        styles.button,
        tone === 'stop' && styles.buttonStop,
        tone === 'query' && styles.buttonQuery,
        pressed && styles.pressed,
      ]}
      onPress={onPress}>
      {/* Two lines rather than one: `updateCharacteristicValue` does not fit a half-width cell on a
          narrow phone, and wrapping keeps it readable where ellipsis would not. The cell height is
          fixed either way, so a wrapped label does not make its row taller than the others. */}
      <Text style={styles.buttonLabel} numberOfLines={2}>
        {label}
      </Text>
    </Pressable>
  );
}

// Android reports no safe area of its own, so the status bar is measured; iOS keeps a notch-safe inset.
const TOP_INSET = Platform.OS === 'android' ? (StatusBar.currentHeight ?? 0) + 12 : 60;

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#eee',
    paddingTop: TOP_INSET,
    paddingBottom: 12,
    paddingHorizontal: 12,
  },

  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  title: { fontSize: 20, fontWeight: '600', color: '#111' },
  badge: { borderRadius: 10, paddingHorizontal: 8, paddingVertical: 3 },
  badgeOn: { backgroundColor: '#d6f0dd' },
  badgeOff: { backgroundColor: '#e2e2e2' },
  badgeLabel: { fontSize: 11, fontWeight: '600', color: '#333' },
  advertisedName: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 11,
    color: '#111',
    marginTop: 4,
  },
  deviceId: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 11,
    color: '#666',
    marginTop: 2,
    marginBottom: 12,
  },

  // Two equal columns: `flexBasis` under half sets the count, `flexGrow` squares the edges off.
  grid: { flexDirection: 'row', flexWrap: 'wrap', columnGap: 8, rowGap: 6 },
  button: {
    flexBasis: '47%',
    flexGrow: 1,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#2f6fed',
    borderRadius: 8,
    paddingHorizontal: 8,
  },
  buttonStop: { backgroundColor: '#8a94a6' },
  buttonQuery: { backgroundColor: '#4a5568' },
  buttonLabel: {
    color: '#fff',
    fontSize: 12,
    lineHeight: 14,
    fontWeight: '500',
    textAlign: 'center',
  },
  pressed: { opacity: 0.7 },

  logHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 12, marginBottom: 6 },
  logTitle: { fontSize: 12, fontWeight: '600', color: '#444', textTransform: 'uppercase' },
  logCount: { fontSize: 11, color: '#888' },
  spacer: { flex: 1 },
  clear: { borderRadius: 6, paddingHorizontal: 10, paddingVertical: 4, backgroundColor: '#ddd' },
  clearLabel: { fontSize: 11, fontWeight: '600', color: '#444' },

  log: { flex: 1, backgroundColor: '#fff', borderRadius: 8 },
  logContent: { padding: 10, gap: 2 },
  logEmpty: { fontSize: 11, color: '#aaa', fontStyle: 'italic' },
  logLine: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 11,
    lineHeight: 15,
    color: '#222',
  },
});
