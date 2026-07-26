import CoreBluetooth
import XCTest

@testable import GattServerCore

/// How a written fragment lands in the attribute it targets.
///
/// This is the part of the peripheral that decides what the *next* read returns, so a mistake here is
/// visible on the wire and silent everywhere else. It is also where iOS and Android diverged: iOS had
/// only `CBATTRequest.offset` to go on and treated every fragment at offset 0 as a whole-value
/// replacement, truncating an attribute that a long write did not cover to its end, while Android —
/// which is told the procedure directly — kept the remainder.
final class WriteAssemblyTests: XCTestCase {
  private var manager: GattServerManager!

  override func setUp() {
    super.setUp()
    // Safe to build off-device: the initialiser only stores the timeout. `CBPeripheralManager` is not
    // created until `open`, which these tests never call.
    manager = GattServerManager()
  }

  override func tearDown() {
    manager = nil
    super.tearDown()
  }

  private func data(_ bytes: [UInt8]) -> Data { Data(bytes) }

  // MARK: - Unqueued ATT_WRITE_REQ

  /// "The attribute value shall be truncated or lengthened to match the length of the Attribute Value
  /// parameter" — Core Spec Vol 3, Part F, §3.4.5.1.
  func testUnqueuedWriteTruncatesALongerAttribute() {
    let current = data([1, 2, 3, 4, 5])

    let result = manager.spliced(current, offset: 0, part: data([9, 9]), queued: false)

    XCTAssertEqual(result, data([9, 9]))
  }

  func testUnqueuedWriteLengthensAShorterAttribute() {
    let result = manager.spliced(data([1]), offset: 0, part: data([1, 2, 3]), queued: false)

    XCTAssertEqual(result, data([1, 2, 3]))
  }

  /// A zero-length write is a present but empty attribute, not an absent one.
  func testUnqueuedEmptyWriteEmptiesTheAttribute() {
    let result = manager.spliced(data([1, 2, 3]), offset: 0, part: Data(), queued: false)

    XCTAssertEqual(result, Data())
  }

  func testUnqueuedWriteOntoAnEmptyAttribute() {
    let result = manager.spliced(Data(), offset: 0, part: data([7]), queued: false)

    XCTAssertEqual(result, data([7]))
  }

  // MARK: - Queued (long / reliable) writes

  /// The regression this file exists for. A queued part at offset 0 covers only what it carries; the
  /// octets past it belong to the attribute and stay. Assembling it as a replacement dropped them.
  func testQueuedPartAtOffsetZeroKeepsTheTail() {
    let current = data([1, 2, 3, 4, 5])

    let result = manager.spliced(current, offset: 0, part: data([9, 9]), queued: true)

    XCTAssertEqual(result, data([9, 9, 3, 4, 5]))
  }

  func testQueuedPartKeepsBothSidesOfTheFragment() {
    let current = data([1, 2, 3, 4, 5])

    let result = manager.spliced(current, offset: 1, part: data([8]), queued: true)

    XCTAssertEqual(result, data([1, 8, 3, 4, 5]))
  }

  /// An offset exactly at the end appends, which is what every long write after the first part does.
  func testQueuedPartAtTheEndAppends() {
    let result = manager.spliced(data([1, 2]), offset: 2, part: data([3, 4]), queued: true)

    XCTAssertEqual(result, data([1, 2, 3, 4]))
  }

  func testQueuedPartMayExtendPastTheCurrentEnd() {
    let result = manager.spliced(data([1, 2]), offset: 1, part: data([8, 9, 10]), queued: true)

    XCTAssertEqual(result, data([1, 8, 9, 10]))
  }

  /// "Invalid Offset" — Core Spec Vol 3, Part F, §3.4.6.3. `nil` is how this reports it.
  func testOffsetPastTheEndIsRejected() {
    XCTAssertNil(manager.spliced(data([1, 2]), offset: 3, part: data([9]), queued: true))
    XCTAssertNil(manager.spliced(Data(), offset: 1, part: data([9]), queued: true))
  }

  func testOffsetExactlyAtTheEndIsInRange() {
    XCTAssertNotNil(manager.spliced(data([1, 2]), offset: 2, part: data([9]), queued: true))
  }

  // MARK: - Recognising the procedure

  func testALoneFragmentAtOffsetZeroReadsAsAnUnqueuedWrite() {
    XCTAssertFalse(manager.isQueuedWriteBatch(offsets: [0]))
  }

  func testMoreThanOneFragmentCanOnlyBeAQueuedWrite() {
    XCTAssertTrue(manager.isQueuedWriteBatch(offsets: [0, 4]))
  }

  /// A single `ATT_WRITE_REQ` carries no offset field at all, so a non-zero one names a queued write
  /// even on its own.
  func testANonZeroOffsetCanOnlyBeAQueuedWrite() {
    XCTAssertTrue(manager.isQueuedWriteBatch(offsets: [4]))
  }

  func testAnEmptyBatchIsNotAQueuedWrite() {
    XCTAssertFalse(manager.isQueuedWriteBatch(offsets: []))
  }

  // MARK: - The assembly loop

  private let address = CharacteristicAddress(
    service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
  )
  /// Drives the real assembly `didReceiveWrite` uses, rather than a copy of it.
  ///
  /// This used to re-implement the fold, with a comment saying so — and the copy had drifted from the
  /// original in ways that mattered: it decided the queued-write heuristic across the whole batch
  /// instead of per attribute, and knew nothing of the delegated/automatic split. So the cases below
  /// asserted against the test's own accumulator. `assembleWriteBatch` exists to be called from both
  /// places, taking plain fragments because `CBATTRequest` has no public initialiser.
  private func assemble(current: Data, fragments: [(offset: Int, bytes: [UInt8])]) -> Data? {
    manager.assembleWriteBatch(
      fragments.map {
        GattServerManager.WriteFragment(
          address: address, offset: $0.offset, value: data($0.bytes)
        )
      },
      current: [address: current]
    )?[address]
  }

  func testMultiFragmentLongWriteStoppingShortKeepsTheRemainder() {
    let current = data([1, 2, 3, 4, 5, 6, 7, 8])

    let result = assemble(
      current: current,
      fragments: [(offset: 0, bytes: [10, 11]), (offset: 2, bytes: [12, 13])]
    )

    XCTAssertEqual(result, data([10, 11, 12, 13, 5, 6, 7, 8]))
  }

  func testMultiFragmentLongWriteCoveringEverythingReplacesEverything() {
    let current = data([1, 2, 3, 4])

    let result = assemble(
      current: current,
      fragments: [(offset: 0, bytes: [9, 9]), (offset: 2, bytes: [9, 9])]
    )

    XCTAssertEqual(result, data([9, 9, 9, 9]))
  }

  func testMultiFragmentLongWriteMayGrowTheAttribute() {
    let result = assemble(
      current: data([1, 2]),
      fragments: [(offset: 0, bytes: [1, 2]), (offset: 2, bytes: [3, 4]), (offset: 4, bytes: [5])]
    )

    XCTAssertEqual(result, data([1, 2, 3, 4, 5]))
  }

  /// A gap between fragments would leave octets nobody wrote, so the offset has to be rejected rather
  /// than silently zero-filled.
  func testAFragmentPastTheAssembledEndFailsTheWholeBatch() {
    let result = assemble(
      current: Data(),
      fragments: [(offset: 0, bytes: [1, 2]), (offset: 5, bytes: [9])]
    )

    XCTAssertNil(result)
  }

  /// The single shape iOS cannot tell apart, pinned so the limitation is a decision rather than a
  /// surprise: one part at offset 0 is assembled as an unqueued write and does truncate.
  func testTheAmbiguousLoneFragmentAtOffsetZeroTruncates() {
    let result = assemble(current: data([1, 2, 3, 4]), fragments: [(offset: 0, bytes: [9])])

    XCTAssertEqual(result, data([9]))
  }
}

/// Two attributes written in one callback, which the batch-wide heuristic used to conflate.
extension WriteAssemblyTests {
  func testEachAttributeGetsItsOwnQueuedWriteDecision() {
    let manager = GattServerManager(requestTimeoutMs: 1000)
    // Two Write Without Response commands the stack coalesced: each is a lone part at offset 0, so
    // neither is a queued write and both must truncate. Pooling the offsets made the pair look like a
    // two-fragment run, and each attribute then kept a tail it should have dropped.
    let fragments = [
      GattServerManager.WriteFragment(
        address: CharacteristicAddress(
          service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
        ),
        offset: 0,
        value: Data([9, 9])
      ),
      GattServerManager.WriteFragment(
        address: CharacteristicAddress(
          service: CBUUID(string: "180F"), characteristic: CBUUID(string: "2A19")
        ),
        offset: 0,
        value: Data([8, 8])
      ),
    ]
    let current = [
      fragments[0].address: Data([1, 2, 3, 4, 5]),
      fragments[1].address: Data([1, 2, 3, 4, 5]),
    ]

    let assembled = manager.assembleWriteBatch(fragments, current: current)

    XCTAssertEqual(assembled?[fragments[0].address], Data([9, 9]))
    XCTAssertEqual(assembled?[fragments[1].address], Data([8, 8]))
  }

  /// A genuine long write to one attribute is still read as queued when another attribute shares the
  /// callback, so the fix does not cost the case it was protecting.
  func testAGenuineLongWriteIsStillQueuedAlongsideAnotherAttribute() {
    let manager = GattServerManager(requestTimeoutMs: 1000)
    let long = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let other = CharacteristicAddress(
      service: CBUUID(string: "180F"), characteristic: CBUUID(string: "2A19")
    )
    let fragments = [
      GattServerManager.WriteFragment(address: long, offset: 0, value: Data([10, 11])),
      GattServerManager.WriteFragment(address: long, offset: 2, value: Data([12, 13])),
      GattServerManager.WriteFragment(address: other, offset: 0, value: Data([7])),
    ]

    let assembled = manager.assembleWriteBatch(
      fragments,
      current: [long: Data([1, 2, 3, 4, 5, 6]), other: Data([1, 2, 3])]
    )

    XCTAssertEqual(assembled?[long], Data([10, 11, 12, 13, 5, 6]))
    XCTAssertEqual(assembled?[other], Data([7]))
  }
}
