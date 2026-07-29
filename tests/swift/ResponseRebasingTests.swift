import CoreBluetooth
import XCTest

@testable import GattServerCore

/// Aligning a `sendResponse` value onto the offset the central actually asked for.
///
/// `respond(to:withResult:)` takes no offset — CoreBluetooth copies `CBATTRequest.value` into the
/// response PDU verbatim. Both spellings are documented to work: the whole value with `offset: 0`, or
/// an already-sliced value with the request's own offset. Android implements the same contract.
final class ResponseRebasingTests: XCTestCase {
  private func rebase(
    _ bytes: [UInt8], supplied: Int, requested: Int, isRead: Bool = true
  ) throws -> Data {
    try rebasedResponseValue(
      Data(bytes), isRead: isRead, suppliedOffset: supplied,
      requestedOffset: requested, requestId: 7
    )
  }

  // MARK: - Reads

  func testWholeValueAtOffsetZeroAnswersAPlainRead() throws {
    XCTAssertEqual(try rebase([1, 2, 3], supplied: 0, requested: 0), Data([1, 2, 3]))
  }

  /// The documented "offset 0 with the whole value always works" spelling, answering a Read Blob.
  func testWholeValueAtOffsetZeroAnswersAReadBlobContinuation() throws {
    XCTAssertEqual(try rebase([1, 2, 3, 4, 5], supplied: 0, requested: 2), Data([3, 4, 5]))
  }

  /// The other accepted spelling: the caller sliced the value itself and says where it starts.
  func testPreSlicedValueAtTheRequestedOffsetIsPassedThrough() throws {
    XCTAssertEqual(try rebase([3, 4, 5], supplied: 2, requested: 2), Data([3, 4, 5]))
  }

  func testPartiallySlicedValueIsRebasedTheRemainingDistance() throws {
    XCTAssertEqual(try rebase([2, 3, 4, 5], supplied: 1, requested: 3), Data([4, 5]))
  }

  /// Both spellings have to produce the same PDU, or the contract is a coin flip.
  func testBothDocumentedSpellingsAgree() throws {
    let whole: [UInt8] = [10, 20, 30, 40, 50]
    let requested = 3

    let fromZero = try rebase(whole, supplied: 0, requested: requested)
    let preSliced = try rebase(Array(whole[requested...]), supplied: requested, requested: requested)

    XCTAssertEqual(fromZero, preSliced)
    XCTAssertEqual(fromZero, Data([40, 50]))
  }

  /// The specification's signal that the attribute ends there, answered with an empty value rather
  /// than an error.
  func testNothingLeftAtTheRequestedOffsetAnswersEmpty() throws {
    XCTAssertEqual(try rebase([1, 2], supplied: 0, requested: 2), Data())
  }

  func testEmptyValueStaysEmpty() throws {
    XCTAssertEqual(try rebase([], supplied: 0, requested: 0), Data())
  }

  // MARK: - Rejections

  /// Supplying the value from *after* the requested offset leaves the octets the central asked for
  /// missing, and nothing downstream could detect that — so it is refused rather than sent short.
  func testSupplyingFromPastTheRequestedOffsetIsRejected() {
    XCTAssertThrowsError(try rebase([1, 2, 3], supplied: 4, requested: 2)) { error in
      guard let error = error as? GattServerError else {
        return XCTFail("expected a GattServerError, got \(error)")
      }
      XCTAssertEqual(error.code, "ERR_RESPONSE_OFFSET")
      XCTAssertTrue(
        error.message.contains("offset 2"),
        "the message should name the offset the request asked for: \(error.message)"
      )
    }
  }

  func testSupplyingFromExactlyTheRequestedOffsetIsNotRejected() {
    XCTAssertNoThrow(try rebase([1, 2, 3], supplied: 2, requested: 2))
  }

  /// A negative supplied offset passes the "not after the request" relation (`-4 <= 0`) and would
  /// produce a positive skip, silently trimming the response. Android rejects the same input.
  func testANegativeSuppliedOffsetIsRejectedRatherThanTrimmingTheResponse() {
    XCTAssertThrowsError(try rebase([1, 2, 3, 4, 5], supplied: -4, requested: 0)) { error in
      guard let error = error as? GattServerError else {
        return XCTFail("expected a GattServerError, got \(error)")
      }
      XCTAssertEqual(error.code, "ERR_RESPONSE_OFFSET")
      XCTAssertTrue(
        error.message.contains("unsigned 16-bit"),
        "the message should say what an ATT offset is: \(error.message)"
      )
    }
  }

  /// The message has to say the offset is out of range, not that the value starts too late.
  func testANegativeRequestedOffsetIsRejectedAsOutOfRange() {
    XCTAssertThrowsError(try rebase([1, 2, 3], supplied: 0, requested: -1)) { error in
      guard let error = error as? GattServerError else {
        return XCTFail("expected a GattServerError, got \(error)")
      }
      XCTAssertEqual(error.code, "ERR_RESPONSE_OFFSET")
      XCTAssertTrue(
        error.message.contains("unsigned 16-bit"),
        "a negative offset is out of range, not a value supplied too late: \(error.message)"
      )
    }
  }

  // MARK: - Writes

  /// A Write Response carries no value, so nothing is rebased and nothing is rejected — including the
  /// offsets that would fail a read.
  func testWriteResponsesArePassedThroughUnchanged() throws {
    XCTAssertEqual(
      try rebase([1, 2, 3], supplied: 9, requested: 0, isRead: false), Data([1, 2, 3])
    )
  }
}
