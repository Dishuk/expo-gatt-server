import CoreBluetooth
import XCTest

@testable import GattServerCore

/// The maps that decide what a published attribute allows.
///
/// Until these moved out of the binding they ran in no suite on any platform: `Package.swift` cannot
/// compile a file importing ExpoModulesCore, and the TypeScript and Kotlin suites exercise their own
/// re-implementations. A transposed line here publishes an attribute an unpaired central can read,
/// through a green `swift test` and a green pod compile.
final class AttributeConfigurationTests: XCTestCase {

  // MARK: - Permissions

  func testEachSupportedPermissionMapsToItsOwnMember() throws {
    XCTAssertEqual(try parsePermissions(["readable"]), .readable)
    XCTAssertEqual(try parsePermissions(["writeable"]), .writeable)
    XCTAssertEqual(try parsePermissions(["readEncrypted"]), .readEncryptionRequired)
    XCTAssertEqual(try parsePermissions(["writeEncrypted"]), .writeEncryptionRequired)
  }

  /// The pairing that matters: an encrypted permission must never come back as its plain counterpart.
  func testAnEncryptedPermissionIsNotThePlainOne() throws {
    XCTAssertNotEqual(try parsePermissions(["readEncrypted"]), .readable)
    XCTAssertNotEqual(try parsePermissions(["writeEncrypted"]), .writeable)
  }

  func testPermissionsCombineRatherThanReplace() throws {
    XCTAssertEqual(
      try parsePermissions(["readable", "writeEncrypted"]),
      [.readable, .writeEncryptionRequired]
    )
  }

  func testNoPermissionsAtAllIsEmptyRatherThanADefault() throws {
    XCTAssertEqual(try parsePermissions(nil), [])
    XCTAssertEqual(try parsePermissions([]), [])
  }

  /// Refused rather than approximated: every near equivalent is weaker than what was asked for.
  func testTheUnmappableAndroidPermissionsAreRefused() {
    for name in ["readEncryptedMitm", "writeEncryptedMitm", "writeSigned", "writeSignedMitm"] {
      XCTAssertThrowsError(try parsePermissions([name]), name) { error in
        XCTAssertEqual((error as? GattServerError)?.code, "ERR_UNSUPPORTED")
      }
    }
  }

  func testAnUnknownPermissionThrowsRatherThanBeingDropped() {
    XCTAssertThrowsError(try parsePermissions(["reedable"]))
  }

  // MARK: - Properties

  func testEachSupportedPropertyMapsToItsOwnMember() throws {
    XCTAssertEqual(try parseProperties(["read"]), .read)
    XCTAssertEqual(try parseProperties(["write"]), .write)
    XCTAssertEqual(try parseProperties(["writeNoResponse"]), .writeWithoutResponse)
    XCTAssertEqual(try parseProperties(["notify"]), .notify)
    XCTAssertEqual(try parseProperties(["indicate"]), .indicate)
    XCTAssertEqual(try parseProperties(["signedWrite"]), .authenticatedSignedWrites)
  }

  /// `notify` and `indicate` are what `sendNotification` branches on, so they must stay distinct.
  func testNotifyAndIndicateAreDistinct() throws {
    XCTAssertNotEqual(try parseProperties(["notify"]), try parseProperties(["indicate"]))
  }

  func testPropertiesCombineRatherThanReplace() throws {
    XCTAssertEqual(try parseProperties(["read", "notify"]), [.read, .notify])
  }

  /// Apple annotates both as not allowed for local characteristics, so they are refused up front
  /// rather than set and rejected at publication time.
  func testThePropertiesAppleForbidsLocallyAreRefused() {
    for name in ["broadcast", "extendedProperties"] {
      XCTAssertThrowsError(try parseProperties([name]), name) { error in
        XCTAssertEqual((error as? GattServerError)?.code, "ERR_UNSUPPORTED")
      }
    }
  }

  func testAnUnknownPropertyThrowsRatherThanBeingDropped() {
    XCTAssertThrowsError(try parseProperties(["raed"]))
  }

  // MARK: - UUIDs

  func testTheThreeAcceptedWidthsAreAccepted() throws {
    XCTAssertEqual(try parseUuid("180d", field: "service"), CBUUID(string: "180d"))
    XCTAssertEqual(try parseUuid("0000180d", field: "service"), CBUUID(string: "0000180d"))
    XCTAssertEqual(
      try parseUuid("0000180d-0000-1000-8000-00805f9b34fb", field: "service"),
      CBUUID(string: "0000180d-0000-1000-8000-00805f9b34fb")
    )
  }

  /// `CBUUID(string:)` raises an uncatchable Objective-C exception for anything else, so the check has
  /// to happen before the value reaches CoreBluetooth — a throw here is the only survivable outcome.
  func testEverythingElseThrowsBeforeReachingCoreBluetooth() {
    for spelling in [
      "180", "180dd", "zzzz", "", "0000180d-0000-1000-8000-00805f9b34f",
      "0000180d-0000-1000-8000-00805f9b34fg", "0000180d00001000800000805f9b34fb",
      "0000180d_0000_1000_8000_00805f9b34fb",
    ] {
      XCTAssertThrowsError(try parseUuid(spelling, field: "service"), spelling)
    }
  }

  func testANonStringUuidThrows() {
    XCTAssertThrowsError(try parseUuid(42, field: "service"))
    XCTAssertThrowsError(try parseUuid(nil, field: "service"))
  }
}

/// The derivation that decides whether an unpaired central may subscribe.
///
/// `CBAttributePermissions` guards only reads and writes of the value; the separate
/// `notifyEncryptionRequired` / `indicateEncryptionRequired` pair is the only gate on subscribing. A
/// transposed line here hands every later value to an unbonded peer in cleartext, which no other check
/// in this package would notice.
final class SubscriptionSecurityTests: XCTestCase {

  func testAnUnsecuredAttributeKeepsItsPropertiesUnchanged() {
    XCTAssertEqual(securedSubscription([.read, .notify], [.readable]), [.read, .notify])
    XCTAssertEqual(securedSubscription([.write, .indicate], [.writeable]), [.write, .indicate])
  }

  func testAnEncryptedReadSecuresTheSubscription() {
    XCTAssertEqual(
      securedSubscription([.read, .notify], [.readEncryptionRequired]),
      [.read, .notify, .notifyEncryptionRequired]
    )
    XCTAssertEqual(
      securedSubscription([.read, .indicate], [.readEncryptionRequired]),
      [.read, .indicate, .indicateEncryptionRequired]
    )
  }

  /// An encrypted *write* secures the subscription too: the value is confidential either direction.
  func testAnEncryptedWriteSecuresTheSubscription() {
    XCTAssertEqual(
      securedSubscription([.write, .notify], [.writeEncryptionRequired]),
      [.write, .notify, .notifyEncryptionRequired]
    )
  }

  func testBothSubscriptionKindsAreSecuredTogether() {
    XCTAssertEqual(
      securedSubscription([.notify, .indicate], [.readEncryptionRequired]),
      [.notify, .indicate, .notifyEncryptionRequired, .indicateEncryptionRequired]
    )
  }

  /// The plain member has to survive: it sets the bit of the characteristic declaration a central reads
  /// before it will subscribe at all (Core Spec Vol 3, Part G, Table 3.5).
  func testThePlainSubscriptionPropertyIsKeptAlongsideTheSecuredOne() {
    let secured = securedSubscription([.notify], [.readEncryptionRequired])
    XCTAssertTrue(secured.contains(.notify))
    XCTAssertTrue(secured.contains(.notifyEncryptionRequired))
  }

  /// Nothing to secure: an encrypted attribute that declares neither subscription property must not
  /// acquire one.
  func testAnAttributeWithNoSubscriptionPropertyGainsNone() {
    let secured = securedSubscription([.read, .write], [.readEncryptionRequired])
    XCTAssertEqual(secured, [.read, .write])
    XCTAssertFalse(secured.contains(.notifyEncryptionRequired))
    XCTAssertFalse(secured.contains(.indicateEncryptionRequired))
  }
}
