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
import { useCallback, useEffect, useRef, useState } from 'react';
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

const SERVICE_UUID = '0000180d-0000-1000-8000-00805f9b34fb';
const CHARACTERISTIC_UUID = '00002a37-0000-1000-8000-00805f9b34fb';
const USER_DESCRIPTION_UUID = '00002901-0000-1000-8000-00805f9b34fb';

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
        descriptors: [{ uuid: USER_DESCRIPTION_UUID, value: utf8('Heart Rate Measurement') }],
      },
    ],
  },
];

export default function App() {
  const [log, setLog] = useState<string[]>([]);
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const counter = useRef(60);

  const append = useCallback((line: string) => {
    setLog((prev) => [`${new Date().toISOString().slice(11, 19)}  ${line}`, ...prev].slice(0, 200));
  }, []);

  // Exercises every add*Listener helper from the public API.
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
        sendResponse(event.deviceId, event.requestId, GATT_SUCCESS, event.offset, [
          0,
          counter.current,
        ]).catch((error: unknown) => append(`sendResponse failed: ${String(error)}`));
      }),
      addCharacteristicWriteRequestListener((event) => {
        append(
          `onCharacteristicWriteRequest req=${event.requestId} value=[${event.value.join(',')}]`
        );
        if (event.responseNeeded) {
          sendResponse(
            event.deviceId,
            event.requestId,
            GATT_SUCCESS,
            event.offset,
            event.value
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

  const run = useCallback(
    (label: string, action: () => void | Promise<void>) => async () => {
      try {
        await action();
        append(`${label}: ok`);
      } catch (error) {
        append(`${label}: ${String(error)}`);
      }
    },
    [append]
  );

  return (
    <View style={styles.container}>
      <StatusBar barStyle="dark-content" />
      <Text style={styles.header}>expo-gatt-server harness</Text>
      <Text style={styles.status}>connected: {deviceId ?? 'none'}</Text>

      <View style={styles.buttons}>
        <Button
          label="requestPermissions"
          onPress={run('requestPermissions', requestPermissions)}
        />
        <Button label="createServer" onPress={run('createServer', () => createServer(SERVICES))} />
        <Button
          label="startAdvertising"
          onPress={run('startAdvertising', () =>
            startAdvertising({
              localName: 'GattHarness',
              serviceUuids: [SERVICE_UUID],
              includeTxPowerLevel: false,
              connectable: true,
            })
          )}
        />
        <Button label="stopAdvertising" onPress={run('stopAdvertising', () => stopAdvertising())} />
        <Button
          label="updateCharacteristicValue"
          onPress={run('updateCharacteristicValue', () => {
            counter.current = (counter.current + 1) % 256;
            return updateCharacteristicValue(SERVICE_UUID, CHARACTERISTIC_UUID, [0, counter.current]);
          })}
        />
        <Button
          label="sendNotification"
          onPress={run('sendNotification', () => {
            if (!deviceId) {
              throw new Error('no connected device');
            }
            return sendNotification(
              deviceId,
              SERVICE_UUID,
              CHARACTERISTIC_UUID,
              [0, counter.current],
              false
            );
          })}
        />
        <Button
          label="getConnectedDevices"
          onPress={run('getConnectedDevices', async () => {
            append(JSON.stringify(await getConnectedDevices()));
          })}
        />
        <Button
          label="status"
          onPress={run('status', async () => {
            append(
              `supported=${isSupported()} running=${await isServerRunning()} ` +
                `advertising=${await isAdvertising()}`
            );
          })}
        />
        <Button
          label="disconnectDevice"
          onPress={run('disconnectDevice', () => {
            if (!deviceId) {
              throw new Error('no connected device');
            }
            return disconnectDevice(deviceId);
          })}
        />
        <Button label="stopServer" onPress={run('stopServer', () => stopServer())} />
        <Button label="clear log" onPress={() => setLog([])} />
      </View>

      <ScrollView style={styles.log}>
        {log.map((line, index) => (
          <Text key={`${index}-${line}`} style={styles.logLine}>
            {line}
          </Text>
        ))}
      </ScrollView>
    </View>
  );
}

function Button({ label, onPress }: { label: string; onPress: () => void }) {
  return (
    <Pressable style={styles.button} onPress={onPress}>
      <Text style={styles.buttonLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#eee', paddingTop: 60, paddingHorizontal: 12 },
  header: { fontSize: 20, fontWeight: '600', marginBottom: 4 },
  status: { fontSize: 12, color: '#555', marginBottom: 8 },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  button: { backgroundColor: '#2f6fed', borderRadius: 6, paddingHorizontal: 10, paddingVertical: 7 },
  buttonLabel: { color: '#fff', fontSize: 12 },
  log: { flex: 1, marginTop: 12, backgroundColor: '#fff', borderRadius: 6, padding: 8 },
  logLine: { fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', fontSize: 10 },
});
