# API Reference

Complete reference for all exported functions, types, events, and constants.

- [Functions](#functions)
  - [createServer](#createserver)
  - [startAdvertising](#startadvertising)
  - [stopAdvertising](#stopadvertising)
  - [sendNotification](#sendnotification)
  - [sendResponse](#sendresponse)
  - [updateCharacteristicValue](#updatecharacteristicvalue)
  - [stopServer](#stopserver)
- [Event Listeners](#event-listeners)
  - [addDeviceConnectedListener](#adddeviceconnectedlistener)
  - [addDeviceDisconnectedListener](#adddevicedisconnectedlistener)
  - [addCharacteristicReadRequestListener](#addcharacteristicreadrequestlistener)
  - [addCharacteristicWriteRequestListener](#addcharacteristicwriterequestlistener)
  - [addNotificationSentListener](#addnotificationsentlistener)
- [Types](#types)
- [Constants](#constants)
- [Error Codes](#error-codes)

## Functions

### createServer

```typescript
createServer(services: GattServiceConfig[]): Promise<void>
```

Initialize the native BLE GATT server with the given services and characteristics.

| Parameter | Type | Description |
|-----------|------|-------------|
| `services` | `GattServiceConfig[]` | Array of service definitions |

**Throws** if Bluetooth permission is not granted (iOS: authorization check, Android: `BLUETOOTH_CONNECT` runtime permission).

Must be called before `startAdvertising`. Call `stopServer` before calling `createServer` again.

---

### startAdvertising

```typescript
startAdvertising(config?: AdvertiseConfig): Promise<void>
```

Begin BLE advertisement. The device becomes visible to nearby scanners.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `config.localName` | `string` | -- | Device name in advertisement data |
| `config.serviceUuids` | `string[]` | -- | Service UUIDs to advertise |
| `config.includeTxPowerLevel` | `boolean` | `false` | Include TX power level |
| `config.connectable` | `boolean` | `true` | Accept incoming connections |

**Throws** if Bluetooth is not powered on (iOS) or `BLUETOOTH_ADVERTISE` permission is missing (Android).

---

### stopAdvertising

```typescript
stopAdvertising(): void
```

Stop BLE advertisement. Does not disconnect existing connections or remove services.

---

### sendNotification

```typescript
sendNotification(
  deviceId: string,
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
  confirm?: boolean,
): Promise<void>
```

Send a notification or indication to a connected central.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `deviceId` | `string` | -- | Target device identifier |
| `serviceUuid` | `string` | -- | Service containing the characteristic |
| `characteristicUuid` | `string` | -- | Characteristic to update |
| `value` | `number[]` | -- | Byte array payload |
| `confirm` | `boolean` | `false` | `true` for indication (acknowledged), `false` for notification |

**Throws** `MTU_SMALL` if the default MTU is too small for the payload, or `PAYLOAD_EXCEEDS_MTU` if the payload exceeds the negotiated MTU. The data is sent before the error is thrown -- the error serves as a warning that the central may have received truncated data.

---

### sendResponse

```typescript
sendResponse(
  deviceId: string,
  requestId: number,
  status: number,
  offset: number,
  value: number[],
): Promise<void>
```

Respond to a characteristic read request forwarded from the native layer.

| Parameter | Type | Description |
|-----------|------|-------------|
| `deviceId` | `string` | Requesting device identifier |
| `requestId` | `number` | Request ID from the read event |
| `status` | `number` | GATT status code (`GATT_SUCCESS` or `GATT_FAILURE`) |
| `offset` | `number` | Read offset from the request event |
| `value` | `number[]` | Response byte array |

**Throws** `REQUEST_NOT_FOUND` if the request ID is invalid or already responded to. May also throw MTU-related errors.

---

### updateCharacteristicValue

```typescript
updateCharacteristicValue(
  serviceUuid: string,
  characteristicUuid: string,
  value: number[],
): void
```

Update the cached value of a characteristic. Subsequent read requests from centrals are auto-responded by the native layer using this value.

Does **not** send a notification. Use `sendNotification` to push updates to subscribed centrals.

---

### stopServer

```typescript
stopServer(): void
```

Shut down the GATT server. Removes all services, disconnects peripherals, and releases native resources. Call this in cleanup or when done with BLE.

## Event Listeners

All listeners return a `Subscription` object with a `.remove()` method. Call `.remove()` to unsubscribe.

### addDeviceConnectedListener

```typescript
addDeviceConnectedListener(
  listener: (event: DeviceConnectedEvent) => void,
): Subscription
```

Fired when a central connects to the server.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier (UUID on iOS, MAC address on Android) |
| `event.name` | `string?` | Device name, if available |

> **iOS behavior:** The connected event fires on first characteristic subscription (not on raw connection), because CoreBluetooth's peripheral manager API does not expose a connection-level callback.

---

### addDeviceDisconnectedListener

```typescript
addDeviceDisconnectedListener(
  listener: (event: DeviceDisconnectedEvent) => void,
): Subscription
```

Fired when a central disconnects.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Device identifier |

---

### addCharacteristicReadRequestListener

```typescript
addCharacteristicReadRequestListener(
  listener: (event: CharacteristicReadRequestEvent) => void,
): Subscription
```

Fired when a central reads a characteristic that has no cached value. Respond with `sendResponse`.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Requesting device |
| `event.requestId` | `number` | Use in `sendResponse` |
| `event.serviceUuid` | `string` | Service UUID |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Read offset |

> **Note:** If the characteristic has a cached value (from `value` in config or `updateCharacteristicValue`), the native layer auto-responds and this listener is not called.

---

### addCharacteristicWriteRequestListener

```typescript
addCharacteristicWriteRequestListener(
  listener: (event: CharacteristicWriteRequestEvent) => void,
): Subscription
```

Fired when a central writes to a characteristic.

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Writing device |
| `event.requestId` | `number` | Request identifier |
| `event.serviceUuid` | `string` | Service UUID |
| `event.characteristicUuid` | `string` | Characteristic UUID |
| `event.offset` | `number` | Write offset |
| `event.value` | `number[]` | Written byte array |
| `event.responseNeeded` | `boolean` | Whether the central expects an acknowledgment |

> **Android:** Write responses are auto-sent by the native layer when `responseNeeded` is true.

---

### addNotificationSentListener

```typescript
addNotificationSentListener(
  listener: (event: NotificationSentEvent) => void,
): Subscription
```

Fired after a notification or indication is delivered (or fails).

| Field | Type | Description |
|-------|------|-------------|
| `event.deviceId` | `string` | Target device |
| `event.characteristicUuid` | `string` | Notified characteristic |
| `event.status` | `number` | `0` for success |

## Types

### GattServiceConfig

```typescript
interface GattServiceConfig {
  uuid: string;
  characteristics: GattCharacteristicConfig[];
}
```

### GattCharacteristicConfig

```typescript
interface GattCharacteristicConfig {
  uuid: string;
  properties: CharacteristicProperty[];
  permissions: CharacteristicPermission[];
  value?: number[];
}
```

### CharacteristicProperty

```typescript
type CharacteristicProperty = 'read' | 'write' | 'writeNoResponse' | 'notify' | 'indicate';
```

### CharacteristicPermission

```typescript
type CharacteristicPermission = 'readable' | 'writeable';
```

### AdvertiseConfig

```typescript
interface AdvertiseConfig {
  localName?: string;
  serviceUuids?: string[];
  includeTxPowerLevel?: boolean;
  connectable?: boolean;
}
```

### DeviceConnectedEvent

```typescript
interface DeviceConnectedEvent {
  deviceId: string;
  name?: string;
}
```

### DeviceDisconnectedEvent

```typescript
interface DeviceDisconnectedEvent {
  deviceId: string;
}
```

### CharacteristicReadRequestEvent

```typescript
interface CharacteristicReadRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
}
```

### CharacteristicWriteRequestEvent

```typescript
interface CharacteristicWriteRequestEvent {
  deviceId: string;
  requestId: number;
  serviceUuid: string;
  characteristicUuid: string;
  offset: number;
  value: number[];
  responseNeeded: boolean;
}
```

### NotificationSentEvent

```typescript
interface NotificationSentEvent {
  deviceId: string;
  characteristicUuid: string;
  status: number;
}
```

## Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `GATT_SUCCESS` | `0` | Operation completed successfully |
| `GATT_FAILURE` | `257` | Generic failure status |

## Error Codes

Errors thrown by `sendNotification` and `sendResponse` include a `code` property:

| Code | Description |
|------|-------------|
| `MTU_SMALL` | Default ATT MTU (23 bytes / 20 payload) is too small for the value. The central has not negotiated a larger MTU. |
| `PAYLOAD_EXCEEDS_MTU` | Value exceeds the negotiated MTU. Data was sent but may be truncated by the central. |
| `REQUEST_NOT_FOUND` | The `requestId` does not match any pending read request. |
