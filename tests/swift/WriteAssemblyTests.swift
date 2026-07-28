import CoreBluetooth
import XCTest

@testable import GattServerCore

/// How a written fragment lands in the attribute it targets — decides what the *next* read returns.
///
/// iOS has only `CBATTRequest.offset` to go on; Android is told the write procedure directly. iOS used
/// to treat every fragment at offset 0 as a whole-value replacement, truncating an attribute that a
/// long write did not fully cover.
final class WriteAssemblyTests: XCTestCase {
  private func data(_ bytes: [UInt8]) -> Data { Data(bytes) }

  // MARK: - Unqueued ATT_WRITE_REQ

  /// "The attribute value shall be truncated or lengthened to match the length of the Attribute Value
  /// parameter" — Core Spec Vol 3, Part F, §3.4.5.1.
  func testUnqueuedWriteTruncatesALongerAttribute() {
    let current = data([1, 2, 3, 4, 5])

    let result = spliced(current, offset: 0, part: data([9, 9]), queued: false)

    XCTAssertEqual(result, data([9, 9]))
  }

  func testUnqueuedWriteLengthensAShorterAttribute() {
    let result = spliced(data([1]), offset: 0, part: data([1, 2, 3]), queued: false)

    XCTAssertEqual(result, data([1, 2, 3]))
  }

  /// A zero-length write is a present but empty attribute, not an absent one.
  func testUnqueuedEmptyWriteEmptiesTheAttribute() {
    let result = spliced(data([1, 2, 3]), offset: 0, part: Data(), queued: false)

    XCTAssertEqual(result, Data())
  }

  func testUnqueuedWriteOntoAnEmptyAttribute() {
    let result = spliced(Data(), offset: 0, part: data([7]), queued: false)

    XCTAssertEqual(result, data([7]))
  }

  // MARK: - Queued (long / reliable) writes

  /// The regression this file exists for. A queued part at offset 0 covers only what it carries; the
  /// octets past it belong to the attribute and stay. Assembling it as a replacement dropped them.
  func testQueuedPartAtOffsetZeroKeepsTheTail() {
    let current = data([1, 2, 3, 4, 5])

    let result = spliced(current, offset: 0, part: data([9, 9]), queued: true)

    XCTAssertEqual(result, data([9, 9, 3, 4, 5]))
  }

  func testQueuedPartKeepsBothSidesOfTheFragment() {
    let current = data([1, 2, 3, 4, 5])

    let result = spliced(current, offset: 1, part: data([8]), queued: true)

    XCTAssertEqual(result, data([1, 8, 3, 4, 5]))
  }

  /// An offset exactly at the end appends, which is what every long write after the first part does.
  func testQueuedPartAtTheEndAppends() {
    let result = spliced(data([1, 2]), offset: 2, part: data([3, 4]), queued: true)

    XCTAssertEqual(result, data([1, 2, 3, 4]))
  }

  func testQueuedPartMayExtendPastTheCurrentEnd() {
    let result = spliced(data([1, 2]), offset: 1, part: data([8, 9, 10]), queued: true)

    XCTAssertEqual(result, data([1, 8, 9, 10]))
  }

  /// "Invalid Offset" — Core Spec Vol 3, Part F, §3.4.6.3. `nil` is how this reports it.
  func testOffsetPastTheEndIsRejected() {
    XCTAssertNil(spliced(data([1, 2]), offset: 3, part: data([9]), queued: true))
    XCTAssertNil(spliced(Data(), offset: 1, part: data([9]), queued: true))
  }

  func testOffsetExactlyAtTheEndIsInRange() {
    XCTAssertNotNil(spliced(data([1, 2]), offset: 2, part: data([9]), queued: true))
  }

  // MARK: - Recognising the procedure

  func testALoneFragmentAtOffsetZeroReadsAsAnUnqueuedWrite() {
    XCTAssertFalse(isQueuedWriteBatch(offsets: [0]))
  }

  /// A long write's parts carry advancing offsets, so more than one part always brings a non-zero one
  /// with it. The decision rests on that offset, never on the count — see the case below.
  func testAdvancingOffsetsNameAQueuedWrite() {
    XCTAssertTrue(isQueuedWriteBatch(offsets: [0, 4]))
  }

  /// The regression this rule was rewritten for. Two Write Without Response commands to one
  /// characteristic are coalesced into a single callback and both carry offset 0 — an `ATT_WRITE_CMD`
  /// has no offset field to carry anything else. Counting the parts read that as a queued write, and
  /// the queued splice then preserved the octets past each one, leaving a stale tail where Android
  /// replaced the value outright.
  func testTwoPartsAtOffsetZeroAreNotAQueuedWrite() {
    XCTAssertFalse(isQueuedWriteBatch(offsets: [0, 0]))
  }

  /// A single `ATT_WRITE_REQ` carries no offset field at all, so a non-zero one names a queued write
  /// even on its own.
  func testANonZeroOffsetCanOnlyBeAQueuedWrite() {
    XCTAssertTrue(isQueuedWriteBatch(offsets: [4]))
  }

  func testAnEmptyBatchIsNotAQueuedWrite() {
    XCTAssertFalse(isQueuedWriteBatch(offsets: []))
  }

  /// The decision that drives both the assembly and the reporting, so the two can never disagree about
  /// which procedure a batch was.
  func testTheQueuedDecisionIsMadePerAttribute() {
    let long = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let streamed = CharacteristicAddress(
      service: CBUUID(string: "180F"), characteristic: CBUUID(string: "2A19")
    )
    let queued = queuedWriteAddresses([
      WriteFragment(address: long, offset: 0, value: Data([1])),
      WriteFragment(address: long, offset: 1, value: Data([2])),
      WriteFragment(address: streamed, offset: 0, value: Data([3])),
      WriteFragment(address: streamed, offset: 0, value: Data([4])),
    ])

    XCTAssertEqual(queued, [long])
  }

  // MARK: - The assembly loop

  private let address = CharacteristicAddress(
    service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
  )
  /// Drives the real assembly `didReceiveWrite` uses, rather than a copy of it. `assembleWriteBatch`
  /// takes plain fragments because `CBATTRequest` has no public initialiser.
  private func assemble(current: Data, fragments: [(offset: Int, bytes: [UInt8])]) -> Data? {
    assembleWriteBatch(
      fragments.map {
        WriteFragment(
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

  /// Two Write Without Response commands to *one* characteristic, coalesced into a single callback.
  /// Each replaces, so the attribute is left holding exactly the last one — the value Android leaves.
  func testCoalescedCommandsToOneAttributeReplaceRatherThanKeepingATail() {
    let result = assemble(
      current: data([1, 2, 3, 4, 5]),
      fragments: [(offset: 0, bytes: [10, 11, 12]), (offset: 0, bytes: [20, 21])]
    )

    XCTAssertEqual(result, data([20, 21]))
  }

  /// The same shape where the later command is the longer one, so the bug would have been invisible:
  /// a tail is only left behind when the value shrinks.
  func testCoalescedCommandsAreOrderedLastWriteWins() {
    let result = assemble(
      current: data([1, 2]),
      fragments: [(offset: 0, bytes: [10]), (offset: 0, bytes: [20, 21, 22])]
    )

    XCTAssertEqual(result, data([20, 21, 22]))
  }
}

/// Two attributes written in one callback, which the batch-wide heuristic used to conflate.
extension WriteAssemblyTests {
  func testEachAttributeGetsItsOwnQueuedWriteDecision() {
    // Two coalesced commands, each a lone part at offset 0: neither is a queued write, both truncate.
    let fragments = [
      WriteFragment(
        address: CharacteristicAddress(
          service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
        ),
        offset: 0,
        value: Data([9, 9])
      ),
      WriteFragment(
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

    let assembled = assembleWriteBatch(fragments, current: current)

    XCTAssertEqual(assembled?[fragments[0].address], Data([9, 9]))
    XCTAssertEqual(assembled?[fragments[1].address], Data([8, 8]))
  }

  /// A genuine long write to one attribute is still read as queued when another attribute shares the
  /// callback, so the fix does not cost the case it was protecting.
  func testAGenuineLongWriteIsStillQueuedAlongsideAnotherAttribute() {
    let long = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let other = CharacteristicAddress(
      service: CBUUID(string: "180F"), characteristic: CBUUID(string: "2A19")
    )
    let fragments = [
      WriteFragment(address: long, offset: 0, value: Data([10, 11])),
      WriteFragment(address: long, offset: 2, value: Data([12, 13])),
      WriteFragment(address: other, offset: 0, value: Data([7])),
    ]

    let assembled = assembleWriteBatch(
      fragments,
      current: [long: Data([1, 2, 3, 4, 5, 6]), other: Data([1, 2, 3])]
    )

    XCTAssertEqual(assembled?[long], Data([10, 11, 12, 13, 5, 6]))
    XCTAssertEqual(assembled?[other], Data([7]))
  }

  /// "The maximum length of an attribute value shall be 512 octets" — Core Spec Vol 3, Part F, §3.2.9.
  ///
  /// Mirrors `AttOperationsTest.an assembled value at the limit is accepted and one past it is not`.
  func testTheAttributeLengthLimitIsTheSpecifiedOne() {
    XCTAssertFalse(exceedsAttributeLength(512))
    XCTAssertTrue(exceedsAttributeLength(513))
  }

  /// Assembly bounds the offset and not the result, so fragments that are each within what a PDU carries
  /// can still build a value longer than an attribute may hold.
  func testFragmentsWithinRangeCanStillAssemblePastTheLimit() {
    let address = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let fragments = [
      WriteFragment(address: address, offset: 0, value: Data(repeating: 1, count: 500)),
      WriteFragment(address: address, offset: 500, value: Data(repeating: 2, count: 500)),
    ]

    let assembled = assembleWriteBatch(fragments, current: [:])

    XCTAssertEqual(assembled?[address]?.count, 1000)
    XCTAssertTrue(exceedsAttributeLength(assembled?[address]?.count ?? 0))
  }

  /// And the batch is refused whole for it, with the ATT error Android answers the same input with.
  func testABatchThatAssemblesPastTheLimitIsRefusedWhole() {
    let address = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let fragments = [
      WriteFragment(address: address, offset: 0, value: Data(repeating: 1, count: 500)),
      WriteFragment(address: address, offset: 500, value: Data(repeating: 2, count: 500)),
    ]

    XCTAssertEqual(
      resolveWriteBatch(fragments, current: [:]), .exceedsAttributeLength
    )
  }

  func testABatchAtTheLimitIsAccepted() {
    let address = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let fragments = [
      WriteFragment(address: address, offset: 0, value: Data(repeating: 1, count: 256)),
      WriteFragment(address: address, offset: 256, value: Data(repeating: 2, count: 256)),
    ]

    XCTAssertEqual(
      resolveWriteBatch(fragments, current: [:]),
      .assembled([address: Data(repeating: 1, count: 256) + Data(repeating: 2, count: 256)])
    )
  }

  /// A fragment addressing a gap past the end of its attribute is the other way a batch resolves to
  /// nothing, and it answers a different ATT error.
  func testAFragmentPastTheEndIsRefusedAsAnInvalidOffset() {
    let address = CharacteristicAddress(
      service: CBUUID(string: "180D"), characteristic: CBUUID(string: "2A37")
    )
    let fragments = [
      WriteFragment(address: address, offset: 4, value: Data([1, 2]))
    ]

    XCTAssertEqual(resolveWriteBatch(fragments, current: [:]), .invalidOffset)
  }
}
