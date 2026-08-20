import Foundation

/// What happened when an envelope was applied.
public enum DiffOutcome: Equatable {
    /// Folded in, or ignored as a duplicate. Either way the tracker is
    /// consistent and nothing further is needed.
    case ok
    /// An envelope was missed. State is **untouched** — recovering needs a
    /// snapshot, not more diffs.
    case gap
}

/// A list built from a stream of diff envelopes, with a dropped one detected
/// by its sequence number.
///
/// Applying a batch after a missed one is the corruption this type exists to
/// prevent: the ops address positions in a list the sender believes you have,
/// and after a gap you do not have it. So a gap returns without touching
/// anything and the caller fetches a snapshot instead.
///
/// Sequence numbers start at 1 — see `dto::SeqCounter`.
public struct DiffTracker<T> {
    public private(set) var items: [T] = []
    private var expectedSeq: UInt64 = 1

    public init() {}

    /// Fold `ops` in if `seq` is the next expected envelope.
    ///
    /// Ahead of expected is a gap. Behind is a duplicate, which is ignored:
    /// the core can re-send after a reconnect, and applying one twice would
    /// double an insert.
    public mutating func apply(_ ops: [DiffOp<T>], seq: UInt64) -> DiffOutcome {
        if seq > expectedSeq { return .gap }
        if seq < expectedSeq { return .ok }

        items = applyOps(items, ops)
        expectedSeq += 1
        return .ok
    }

    /// Hard-reset to a snapshot, after a gap or a new subscription.
    ///
    /// `seq` is the sequence the snapshot was taken at, so the next live
    /// envelope — guaranteed by the core to be `seq + 1` — resumes normally.
    public mutating func reset(items: [T], seq: UInt64) {
        self.items = items
        expectedSeq = seq + 1
    }
}
