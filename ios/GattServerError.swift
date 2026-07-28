import CoreBluetooth
import Foundation

enum GattServerError: Error {
  case payloadExceedsMtu(maxPayload: Int, payloadSize: Int)
  case requestNotFound(requestId: Int)
  case requestDeviceMismatch(requestId: Int, owner: String, supplied: String)
  case responseOffsetAfterRequest(requestId: Int, requested: Int, supplied: Int)
  case responseOffsetNegative(requestId: Int, requested: Int, supplied: Int)
  case bluetoothUnavailable(state: CBManagerState)
  case serviceRegistrationFailed(uuid: String, reason: String)
  case publicationTimedOut(awaiting: [String], timeoutMs: Int)
  case serverStopped
  case databaseNotPublished
  case characteristicNotFound(service: String, characteristic: String)
  case notifyQueueFull(limit: Int)
  case notificationTimedOut(timeoutMs: Int)
  case deviceDisconnected(deviceId: String)
  case noSubscriber(deviceId: String, characteristic: String)
  case confirmUnsupported(characteristic: String, confirm: Bool)
  case advertisingOptionUnsupported(option: String, reason: String)
  case configurationUnsupported(option: String, reason: String)

  var code: String {
    switch self {
    case .payloadExceedsMtu: return "PAYLOAD_EXCEEDS_MTU"
    case .requestNotFound: return "REQUEST_NOT_FOUND"
    case .requestDeviceMismatch: return "REQUEST_DEVICE_MISMATCH"
    case .responseOffsetAfterRequest, .responseOffsetNegative: return "ERR_RESPONSE_OFFSET"
    case .bluetoothUnavailable(let state):
      return state == .unauthorized ? "ERR_PERMISSION" : "ERR_BLUETOOTH"
    case .serviceRegistrationFailed, .publicationTimedOut: return "ERR_CREATE_SERVER"
    case .serverStopped, .databaseNotPublished: return "ERR_NO_SERVER"
    case .characteristicNotFound: return "ERR_CHARACTERISTIC_NOT_FOUND"
    case .notifyQueueFull: return "ERR_NOTIFY_QUEUE_FULL"
    case .notificationTimedOut: return "ERR_NOTIFY"
    case .deviceDisconnected: return "ERR_DEVICE_DISCONNECTED"
    case .noSubscriber: return "ERR_NO_SUBSCRIBER"
    case .confirmUnsupported: return "ERR_CONFIRM_UNSUPPORTED"
    case .advertisingOptionUnsupported, .configurationUnsupported: return "ERR_UNSUPPORTED"
    }
  }

  var message: String {
    switch self {
    case .payloadExceedsMtu(let maxPayload, let payloadSize):
      var message = "Payload size \(payloadSize) exceeds the \(maxPayload) bytes a single " +
        "notification or indication can carry on this link. Nothing was sent."
      if maxPayload <= defaultAttMtuPayload {
        message += " The link is still at the default ATT MTU of \(defaultAttMtu); a central that " +
          "negotiates a larger one is reported through onMtuChanged."
      }
      return message
    case .requestNotFound(let requestId):
      return "Request \(requestId) not found or already responded"
    case .requestDeviceMismatch(let requestId, let owner, let supplied):
      return "Request \(requestId) belongs to device \(owner), not \(supplied)"
    case .responseOffsetAfterRequest(let requestId, let requested, let supplied):
      return "Request \(requestId) asked for the attribute from offset \(requested), but the " +
        "response supplies it from offset \(supplied), which leaves the requested bytes missing. " +
        "Pass the value together with the offset it starts at — offset 0 with the whole value " +
        "always works."
    case .responseOffsetNegative(let requestId, let requested, let supplied):
      return "Request \(requestId) was answered with offset \(supplied) against a requested offset " +
        "of \(requested). An ATT offset is an unsigned 16-bit value."
    case .bluetoothUnavailable(let state):
      switch state {
      case .poweredOff: return "Bluetooth is turned off"
      case .unauthorized: return "Bluetooth permission not granted"
      case .unsupported: return "BLE not supported on this device"
      default: return "Bluetooth not ready"
      }
    case .serviceRegistrationFailed(let uuid, let reason):
      return "Failed to publish service \(uuid): \(reason)"
    case .publicationTimedOut(let awaiting, let timeoutMs):
      return "CoreBluetooth did not acknowledge \(awaiting.joined(separator: ", ")) within " +
        "\(timeoutMs) ms, so the database was not published. Call createServer again to retry."
    case .serverStopped:
      return "Server was stopped before it finished opening"
    case .databaseNotPublished:
      return "No GATT database is published, so there is nothing to advertise. Wait for createServer " +
        "to resolve; isServerRunning reports whether the database is still there, which a failed " +
        "registration or Bluetooth going down undoes."
    case .characteristicNotFound(let service, let characteristic):
      return "Characteristic \(characteristic) was not found in service \(service)"
    case .notifyQueueFull(let limit):
      return "\(limit) notifications are already waiting for the transmit queue to drain for this " +
        "central. Wait for earlier sends to resolve before queueing more."
    case .notificationTimedOut(let timeoutMs):
      return "CoreBluetooth did not report the transmit queue ready within \(timeoutMs) ms, so the " +
        "notification was abandoned and the ones behind it were retried. The central may have gone " +
        "away without CoreBluetooth reporting it."
    case .deviceDisconnected(let deviceId):
      return "Device \(deviceId) disconnected"
    case .noSubscriber(let deviceId, let characteristic):
      return "Device \(deviceId) has not subscribed to characteristic \(characteristic). " +
        "Wait for onCharacteristicSubscribed. CoreBluetooth only transmits to subscribed " +
        "centrals, so this cannot be overridden on iOS."
    case .confirmUnsupported(let characteristic, let confirm):
      // updateValue has no confirm parameter; this check prevents silent mismatch.
      if confirm {
        return "Characteristic \(characteristic) does not declare the \"indicate\" property, so it " +
          "cannot send the acknowledged indication confirm: true asks for. Declare \"indicate\" on " +
          "the characteristic, or send a notification with confirm: false."
      }
      return "Characteristic \(characteristic) does not declare the \"notify\" property, so it " +
        "cannot send an unacknowledged notification. Declare \"notify\" on the characteristic, or " +
        "send an indication with confirm: true."
    case .advertisingOptionUnsupported(let option, let reason):
      return "iOS cannot honour the advertising option \"\(option)\": \(reason) " +
        "CBPeripheralManager.startAdvertising supports only CBAdvertisementDataLocalNameKey and " +
        "CBAdvertisementDataServiceUUIDsKey."
    case .configurationUnsupported(let option, let reason):
      return "iOS cannot honour \"\(option)\": \(reason)"
    }
  }
}

/// Surfaces as `ERR_ADVERTISE`, matching Android.
func advertisingError(_ message: String) -> NSError {
  NSError(domain: "ExpoGattServer", code: 0, userInfo: [NSLocalizedDescriptionKey: message])
}
