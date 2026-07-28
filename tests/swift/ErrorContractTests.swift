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
    ("ERR_RESPONSE_OFFSET", .responseOffsetNegative(requestId: 1, requested: 0, supplied: -4)),
    ("ERR_BLUETOOTH", .bluetoothUnavailable(state: .poweredOff)),
    ("ERR_CREATE_SERVER", .serviceRegistrationFailed(uuid: "180d", reason: "why")),
    ("ERR_CREATE_SERVER", .publicationTimedOut(awaiting: ["180d"], timeoutMs: 30_000)),
    ("ERR_NO_SERVER", .serverStopped),
    ("ERR_NO_SERVER", .databaseNotPublished),
    ("ERR_CHARACTERISTIC_NOT_FOUND", .characteristicNotFound(service: "180d", characteristic: "2a37")),
    ("ERR_NOTIFY_QUEUE_FULL", .notifyQueueFull(limit: 64)),
    ("ERR_NOTIFY", .notificationTimedOut(timeoutMs: 35_000)),
    ("ERR_DEVICE_DISCONNECTED", .deviceDisconnected(deviceId: "A")),
    ("ERR_NO_SUBSCRIBER", .noSubscriber(deviceId: "A", characteristic: "2a37")),
    ("ERR_CONFIRM_UNSUPPORTED", .confirmUnsupported(characteristic: "2a37", confirm: true)),
    ("ERR_UNSUPPORTED", .advertisingOptionUnsupported(option: "serviceData", reason: "no key.")),
    ("ERR_UNSUPPORTED", .configurationUnsupported(option: "permission", reason: "no member.")),
  ]

  /// `GattServerError` carries associated values, so it cannot be `CaseIterable` and the table above
  /// cannot be derived from it. A `switch` with no `default` forces a new case to be named here.
  private static func declaredCode(for error: GattServerError) -> String {
    switch error {
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

  func testEachCaseReportsItsDocumentedCode() {
    for (expected, error) in Self.codes {
      XCTAssertEqual(error.code, expected, "\(error)")
      // Independently restated, so a code changed in one place and not the other fails here rather
      // than agreeing with itself.
      XCTAssertEqual(Self.declaredCode(for: error), expected, "\(error)")
    }
  }

  /// Asserted on the code the *error* reports, not on the table's own literal — reading it from the
  /// literal made this pass no matter what `GattServerError.code` returned.
  func testEveryCodeIsScreamingSnakeCase() {
    for (_, error) in Self.codes {
      let code = error.code
      XCTAssertEqual(code, code.uppercased(), code)
      XCTAssertFalse(code.contains(" "), code)
      XCTAssertFalse(code.isEmpty, "\(error)")
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

  // MARK: - What a failed publication round reports

  /// The two ways a round can end badly. `failPublicationRound` carries one error through to all three
  /// audiences (completion, event, and this table), so both must report the code the event promises.
  private static let publicationFailures: [GattServerError] = [
    .serviceRegistrationFailed(uuid: "180d", reason: "why"),
    .publicationTimedOut(awaiting: ["180d"], timeoutMs: 30_000),
  ]

  /// The message reaches `onServerPublicationFailed` unchanged, so it has to name the service and reason.
  func testARegistrationFailureNamesTheServiceAndTheReason() {
    let message = GattServerError.serviceRegistrationFailed(uuid: "180d", reason: "why").message

    XCTAssertTrue(message.contains("180d"), message)
    XCTAssertTrue(message.contains("why"), message)
  }

  /// Pins the code that must *not* come back: `ERR_NO_SERVER` means "there is no database, so there is
  /// nothing to do", which is the state a failed round leaves behind but not the fault it reports.
  func testTheAdvertisingGuidanceCodeIsNotAPublicationFailure() {
    XCTAssertEqual(GattServerError.databaseNotPublished.code, "ERR_NO_SERVER")
    for error in Self.publicationFailures {
      XCTAssertNotEqual(error.code, GattServerError.databaseNotPublished.code, "\(error)")
    }
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
