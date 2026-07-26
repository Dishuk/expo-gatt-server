import CoreBluetooth
import XCTest

@testable import GattServerCore

/// The rejection codes are a public API: consumers branch on them, the documentation enumerates them,
/// and Android reports the same strings for the same situations. Renaming one is a breaking change that
/// nothing else in the build would notice.
final class ErrorContractTests: XCTestCase {
  /// Every code this platform can produce, and the case that produces it. Deliberately spelled out
  /// rather than derived from the enum, so that adding a case forces a decision about which code it
  /// reports instead of inheriting one silently.
  private static let codes: [(String, GattServerError)] = [
    ("PAYLOAD_EXCEEDS_MTU", .payloadExceedsMtu(maxPayload: 20, payloadSize: 40)),
    ("REQUEST_NOT_FOUND", .requestNotFound(requestId: 1)),
    ("REQUEST_DEVICE_MISMATCH", .requestDeviceMismatch(requestId: 1, owner: "A", supplied: "B")),
    ("ERR_RESPONSE_OFFSET", .responseOffsetAfterRequest(requestId: 1, requested: 0, supplied: 2)),
    ("ERR_BLUETOOTH", .bluetoothUnavailable(state: .poweredOff)),
    ("ERR_CREATE_SERVER", .serviceRegistrationFailed(uuid: "180d", reason: "why")),
    ("ERR_NO_SERVER", .serverStopped),
    ("ERR_NO_SERVER", .databaseNotPublished),
    ("ERR_CHARACTERISTIC_NOT_FOUND", .characteristicNotFound(service: "180d", characteristic: "2a37")),
    ("ERR_NOTIFY_QUEUE_FULL", .notifyQueueFull(limit: 64)),
    ("ERR_DEVICE_DISCONNECTED", .deviceDisconnected(deviceId: "A")),
    ("ERR_NO_SUBSCRIBER", .noSubscriber(deviceId: "A", characteristic: "2a37")),
    ("ERR_CONFIRM_UNSUPPORTED", .confirmUnsupported(characteristic: "2a37", confirm: true)),
    ("ERR_UNSUPPORTED", .advertisingOptionUnsupported(option: "serviceData", reason: "no key.")),
    ("ERR_UNSUPPORTED", .configurationUnsupported(option: "permission", reason: "no member.")),
  ]

  func testEachCaseReportsItsDocumentedCode() {
    for (expected, error) in Self.codes {
      XCTAssertEqual(error.code, expected, "\(error)")
    }
  }

  func testEveryCodeIsScreamingSnakeCase() {
    for (code, _) in Self.codes {
      XCTAssertEqual(code, code.uppercased(), code)
      XCTAssertFalse(code.contains(" "), code)
    }
  }

  func testEveryCaseCarriesAMessage() {
    for (_, error) in Self.codes {
      XCTAssertFalse(error.message.isEmpty, "\(error)")
    }
  }

  // MARK: - The state-dependent code

  /// Bluetooth being unauthorised is a permission problem the user can fix in Settings, and is reported
  /// as one; every other unusable state is a Bluetooth problem.
  func testUnauthorisedIsAPermissionProblemAndTheRestAreBluetoothProblems() {
    XCTAssertEqual(GattServerError.bluetoothUnavailable(state: .unauthorized).code, "ERR_PERMISSION")

    for state: CBManagerState in [.poweredOff, .unsupported, .resetting, .unknown] {
      XCTAssertEqual(
        GattServerError.bluetoothUnavailable(state: state).code, "ERR_BLUETOOTH", "\(state)"
      )
    }
  }

  /// The wording is shared with Android, which reports the same two situations under `ERR_BLUETOOTH`.
  func testTheBluetoothMessagesMatchTheWordingAndroidUses() {
    XCTAssertEqual(
      GattServerError.bluetoothUnavailable(state: .poweredOff).message, "Bluetooth is turned off"
    )
    XCTAssertEqual(
      GattServerError.bluetoothUnavailable(state: .unsupported).message,
      "BLE not supported on this device"
    )
  }

  // MARK: - Messages that have to say something specific

  /// The default-MTU hint is the actionable part — it tells a caller the link simply has not negotiated
  /// yet — so it must appear when that is the situation and not when a larger MTU is already in effect,
  /// where it would be actively misleading.
  func testTheMtuHintAppearsOnlyWhileTheLinkIsStillAtTheDefault() {
    let unnegotiated = GattServerError.payloadExceedsMtu(maxPayload: 20, payloadSize: 40).message
    XCTAssertTrue(unnegotiated.contains("default ATT MTU"), unnegotiated)

    let negotiated = GattServerError.payloadExceedsMtu(maxPayload: 244, payloadSize: 300).message
    XCTAssertFalse(negotiated.contains("default ATT MTU"), negotiated)
  }

  func testTheMtuMessageNamesBothSizesAndSaysNothingWasSent() {
    let message = GattServerError.payloadExceedsMtu(maxPayload: 244, payloadSize: 300).message

    XCTAssertTrue(message.contains("300"), message)
    XCTAssertTrue(message.contains("244"), message)
    XCTAssertTrue(message.contains("Nothing was sent"), message)
  }

  /// `updateValue` has no confirm parameter and picks notification or indication from the declared
  /// properties, so this message is the only thing standing between the caller's intent and silently
  /// getting the other one. It has to name the property that is actually missing.
  func testTheConfirmMessageNamesTheMissingProperty() {
    let indicate = GattServerError.confirmUnsupported(characteristic: "2a37", confirm: true).message
    XCTAssertTrue(indicate.contains("\"indicate\""), indicate)
    XCTAssertFalse(indicate.contains("does not declare the \"notify\""), indicate)

    let notify = GattServerError.confirmUnsupported(characteristic: "2a37", confirm: false).message
    XCTAssertTrue(notify.contains("\"notify\""), notify)
    XCTAssertFalse(notify.contains("does not declare the \"indicate\""), notify)
  }

  func testTheDeviceMismatchMessageNamesBothDevices() {
    let message = GattServerError.requestDeviceMismatch(
      requestId: 3, owner: "OWNER", supplied: "OTHER"
    ).message

    XCTAssertTrue(message.contains("OWNER"), message)
    XCTAssertTrue(message.contains("OTHER"), message)
  }

  /// iOS cannot force a send to an unsubscribed central, and the message says so — `requireSubscription:
  /// false` works on Android and changes nothing here, which is exactly the kind of thing a caller
  /// otherwise discovers by guessing.
  func testTheSubscriberMessageSaysTheOverrideDoesNotApplyHere() {
    let message = GattServerError.noSubscriber(deviceId: "A", characteristic: "2a37").message

    XCTAssertTrue(message.contains("onCharacteristicSubscribed"), message)
    XCTAssertTrue(message.contains("cannot be overridden on iOS"), message)
  }

  // MARK: - Timing

  /// A module timeout at or above the ATT transaction timeout could never answer before the peer gives
  /// up and retires the bearer, so the default has to leave real margin below it.
  func testTheRequestTimeoutLeavesMarginBelowTheAttTransactionTimeout() {
    XCTAssertEqual(attTransactionTimeoutMs, 30_000)
    XCTAssertLessThan(defaultRequestTimeoutMs, attTransactionTimeoutMs)
    XCTAssertGreaterThan(defaultRequestTimeoutMs, 0)
  }
}
