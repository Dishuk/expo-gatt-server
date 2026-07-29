import CoreBluetooth
import Foundation

/// The ATT-level arithmetic of assembling written values and aligning read responses.
///
/// Free functions with no state of their own: the counterpart of Android's `AttOperations.kt`, and the
/// part of the peripheral that can be exercised without a `CBPeripheralManager`.

/// One write request's payload, minimal form for testability (CBATTRequest has no public init).
struct WriteFragment {
  let address: CharacteristicAddress
  let offset: Int
  let value: Data
}

/// What a batch of write fragments resolves to, and the ATT error to answer it with when it resolves
/// to nothing.
enum WriteBatchOutcome: Equatable {
  case invalidOffset
  case exceedsAttributeLength
  case assembled([CharacteristicAddress: Data])
}

/// Assembles fragment at offset into current value. Returns nil if offset past end (InvalidOffset).
/// For unqueued writes, replaces all (Core Spec Vol 3, Part F, §3.4.5.1).
/// For queued writes, preserves octets beyond fragment (Core Spec Vol 3, Part F, §3.4.6.1).
func spliced(_ current: Data, offset: Int, part: Data, queued: Bool) -> Data? {
  guard offset <= current.count else { return nil }
  guard queued else { return part }
  var result = Data(current.prefix(offset))
  result.append(part)
  result.append(contentsOf: current.dropFirst(offset + part.count))
  return result
}

/// True if any offset is non-zero (queued write signature). ATT_WRITE_REQ has no offset field (Core Spec Vol 3, Part F, §3.4.5.1).
/// Lone offset-0 part is ambiguous; treated as unqueued write.
func isQueuedWriteBatch(offsets: [Int]) -> Bool {
  offsets.contains { $0 > 0 }
}

/// Addresses whose fragments are queued writes (grouped per attribute; decision reused for assembly and reporting).
func queuedWriteAddresses(_ fragments: [WriteFragment]) -> Set<CharacteristicAddress> {
  var offsetsByAddress: [CharacteristicAddress: [Int]] = [:]
  for fragment in fragments {
    offsetsByAddress[fragment.address, default: []].append(fragment.offset)
  }
  return Set(offsetsByAddress.filter { isQueuedWriteBatch(offsets: $0.value) }.keys)
}

/// Assembles fragments onto current values; returns nil if any part is past end (atomic failure). Per-attribute queued-write decision.
func assembleWriteBatch(
  _ fragments: [WriteFragment],
  current: [CharacteristicAddress: Data]
) -> [CharacteristicAddress: Data]? {
  let queued = queuedWriteAddresses(fragments)

  var assembled: [CharacteristicAddress: Data] = [:]
  for fragment in fragments {
    let base = assembled[fragment.address] ?? current[fragment.address] ?? Data()
    guard let merged = spliced(
      base,
      offset: fragment.offset,
      part: fragment.value,
      queued: queued.contains(fragment.address)
    ) else {
      return nil
    }
    assembled[fragment.address] = merged
  }
  return assembled
}

/// Validates batch assembly; checked on result (fragments alone can't exceed limit due to placement).
func resolveWriteBatch(
  _ fragments: [WriteFragment],
  current: [CharacteristicAddress: Data]
) -> WriteBatchOutcome {
  guard let assembled = assembleWriteBatch(fragments, current: current) else {
    return .invalidOffset
  }
  if assembled.contains(where: { exceedsAttributeLength($0.value.count) }) {
    return .exceedsAttributeLength
  }
  return .assembled(assembled)
}

/// Rebases response from suppliedOffset to requestedOffset. CBATTRequest.offset is read-only; CoreBluetooth derives offset from request.
func rebasedResponseValue(
  _ value: Data, isRead: Bool, suppliedOffset: Int, requestedOffset: Int, requestId: Int
) throws -> Data {
  guard isRead else { return value }

  // Re-check: native module is directly callable. Prevent negative offsets from trimming response undetected.
  guard suppliedOffset >= 0, requestedOffset >= 0 else {
    throw GattServerError.responseOffsetNegative(
      requestId: requestId, requested: requestedOffset, supplied: suppliedOffset
    )
  }

  guard suppliedOffset <= requestedOffset else {
    throw GattServerError.responseOffsetAfterRequest(
      requestId: requestId, requested: requestedOffset, supplied: suppliedOffset
    )
  }
  let skip = requestedOffset - suppliedOffset
  guard skip > 0 else { return value }
  guard skip < value.count else { return Data() }
  return value.subdata(in: skip..<value.count)
}
