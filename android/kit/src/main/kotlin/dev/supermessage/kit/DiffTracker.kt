package dev.supermessage.kit

/** What happened when an envelope was applied. */
enum class DiffOutcome {
    /**
     * Folded in, or ignored as a duplicate. Either way the tracker is
     * consistent and nothing further is needed.
     */
    OK,

    /**
     * An envelope was missed. State is **untouched** — recovering needs a
     * snapshot, not more diffs.
     */
    GAP,
}

/**
 * A list built from a stream of diff envelopes, with a dropped one detected
 * by its sequence number.
 *
 * Applying a batch after a missed one is the corruption this type exists to
 * prevent: the ops address positions in a list the sender believes the
 * caller has, and after a gap the caller does not have it. So a gap returns
 * without touching anything and the caller fetches a snapshot instead.
 *
 * Sequence numbers start at 1 — see `dto::SeqCounter`. `ULong` rather than
 * Swift's `UInt64` for the same reason `TimelineDiffEnvelope.seq` and
 * `RoomDiffEnvelope.seq` already cross the boundary as `kotlin.ULong`: a
 * caller folding an envelope straight into [apply] should not have to
 * convert first.
 */
class DiffTracker<T> {
    /** What the tracker currently believes the list is. */
    var items: List<T> = emptyList()
        private set

    private var expectedSeq: ULong = 1uL

    /**
     * Fold [ops] in if [seq] is the next expected envelope.
     *
     * Ahead of expected is a gap. Behind is a duplicate, which is ignored:
     * the core can re-send after a reconnect, and applying one twice would
     * double an insert.
     */
    fun apply(ops: List<DiffOp<T>>, seq: ULong): DiffOutcome {
        if (seq > expectedSeq) return DiffOutcome.GAP
        if (seq < expectedSeq) return DiffOutcome.OK

        items = applyOps(items, ops)
        expectedSeq += 1uL
        return DiffOutcome.OK
    }

    /**
     * Hard-reset to a snapshot, after a gap or a new subscription.
     *
     * [seq] is the sequence the snapshot was taken at, so the next live
     * envelope — guaranteed by the core to be `seq + 1` — resumes normally.
     */
    fun reset(items: List<T>, seq: ULong) {
        this.items = items
        expectedSeq = seq + 1uL
    }
}
