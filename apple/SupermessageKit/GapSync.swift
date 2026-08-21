import Foundation

/// A full snapshot for recovering from a gap.
///
/// The subject travels with it for the same reason it travels on every
/// envelope — see `GapSync`'s note on subject filtering.
public struct Snapshot<T>: Sendable where T: Sendable {
    public let subject: String
    public let seq: UInt64
    public let items: [T]

    public init(subject: String, seq: UInt64, items: [T]) {
        self.subject = subject
        self.seq = seq
        self.items = items
    }
}

/// The gap → resync → reset sequencing every diff-backed store needs.
///
/// Ported from `src/lib/stores/gapSync.ts`, comments and all. It is factored
/// out for the reason it was there: the ordering hazards below are subtle
/// enough that they must be written, and tested, exactly once.
///
/// ## Hazard 1 — a resync in flight
///
/// `DiffTracker` returning `.gap` means a snapshot is needed. But while that
/// round trip is in flight the core keeps emitting on the same channel, and
/// applying those against the pre-reset tracker just rediscovers the same gap
/// and asks again, forever. So once a resync is in flight, further envelopes
/// are ignored until it lands; the tracker is then hard-reset, and the next
/// live envelope — guaranteed by the core to be `seq + 1` — resumes normally.
///
/// ## Hazard 2 — somebody else's subject
///
/// A channel's sequence is monotonic per channel **and subject**, not per
/// channel alone. The timeline channel's subject is the focused room id, and
/// it changes under the store while a subscribe round trip is in flight. An
/// envelope — or a resync snapshot — belonging to a subject the store is no
/// longer showing is not a gap and not a duplicate: it is somebody else's
/// data, and the only correct thing to do is drop it.
///
/// This one cost a real incident. Treating it as a gap resyncs off the
/// *previous* room's still-installed handle and installs that room's messages
/// under the new room's header, where they stay until the next room switch.
///
/// The in-flight check appears twice — once in `handle`, once in
/// `performResync` — and they are **individually redundant**: falsification
/// showed either one alone prevents the observable failure, and only removing
/// both fails a test. That is not an oversight to tidy up. The one in
/// `performResync` makes it safe to call from anywhere without relying on the
/// caller, and the one in `handle` avoids folding a batch that is about to be
/// discarded. The TypeScript called this belt and suspenders; keeping the pair
/// is deliberate, and knowing no single test can isolate them is the point of
/// saying so here.
///
/// ## Hazard 3 — a resync that lands too late
///
/// A resync issued under one subscription context can land after the context
/// has changed. Without the generation counter, a slow one rolls the new
/// room's state back to the old room's data.
@MainActor
public final class GapSync<T: Sendable> {
    private var tracker = DiffTracker<T>()
    private var resyncing = false
    private var generation = 0
    private var stopped = false

    private let resync: @Sendable () async throws -> Snapshot<T>
    private let accepts: (String) -> Bool
    private let onUpdate: ([T]) -> Void

    /// - Parameters:
    ///   - resync: fetches a full snapshot to recover from a gap.
    ///   - accepts: whether an envelope carrying this subject is ours.
    ///     Anything it rejects is dropped outright — not a gap, not a
    ///     duplicate. Omit it on a single-subject channel like the room list,
    ///     where every envelope is by definition ours.
    ///   - onUpdate: called with the new list whenever it changes.
    public init(
        resync: @escaping @Sendable () async throws -> Snapshot<T>,
        accepts: @escaping (String) -> Bool = { _ in true },
        onUpdate: @escaping ([T]) -> Void
    ) {
        self.resync = resync
        self.accepts = accepts
        self.onUpdate = onUpdate
    }

    /// Fold one envelope in, or recover.
    public func handle(subject: String, seq: UInt64, ops: [DiffOp<T>]) {
        guard !stopped else { return }
        // Somebody else's subject — the previous room's stream, still emitting
        // while this store's subscribe round trip is in flight.
        guard accepts(subject) else { return }
        // A resync is already in flight; ignore until it lands and resets.
        guard !resyncing else { return }

        if tracker.apply(ops, seq: seq) == .gap {
            Task { await self.performResync(generation: generation) }
            return
        }
        onUpdate(tracker.items)
    }

    /// Fetch a snapshot now, without waiting for a gap to reveal one is needed.
    ///
    /// The channel only speaks when something *changes*. A store built after
    /// the core has already emitted its opening state therefore starts empty
    /// and stays empty until the next change, which in a quiet account is
    /// minutes. It is not a gap — no envelope ever arrived to be out of
    /// sequence with — so nothing would ever ask.
    ///
    /// On iOS this is not an edge case. It is what happens on **every return
    /// from background**: the app was suspended, its sockets died, and the
    /// channel has nothing to say until something changes.
    public func seed() async {
        await performResync(generation: generation)
    }

    /// Hard-reset for a new subscription context — a room switch, where the
    /// core restarts the sequence at 1. Publishes an empty list immediately.
    ///
    /// Bumps the generation so a resync already in flight has its result
    /// discarded when it lands: without that, a slow one could resolve after
    /// the reset and roll the new context back to stale data.
    public func resetForNewSubscription() {
        generation += 1
        tracker.reset(items: [], seq: 0)
        onUpdate(tracker.items)
    }

    /// Stop, on logout or teardown.
    public func stop() {
        stopped = true
    }

    /// Start again after a `stop`, on a sign-in that follows a sign-out.
    ///
    /// `stop` used to be a one-way latch: nothing anywhere cleared `stopped`,
    /// so a sync stopped on sign-out stayed stopped for the life of the
    /// process and the next session received nothing (#28).
    ///
    /// The generation bump is the load-bearing half. A resync in flight when
    /// the stop happened is still going to land, and without a bump it lands
    /// against a session that has nothing to do with it and repopulates the
    /// new context with the old one's rows — the same hazard
    /// `resetForNewSubscription` exists to prevent, on a path that had no
    /// protection at all.
    public func resume() {
        generation += 1
        stopped = false
    }

    private func performResync(generation: Int) async {
        // Belt and braces over `handle`'s own check, so this is safe to call
        // from anywhere without relying on it.
        guard !resyncing else { return }
        resyncing = true
        defer { resyncing = false }

        guard let snapshot = try? await resync() else { return }
        // A newer subscription context started while this was in flight; its
        // result belongs to a context that no longer exists.
        guard !stopped, generation == self.generation else { return }
        // And belt-and-braces over that: the core serves a resync out of
        // whichever subscription is *currently* installed, which during a room
        // switch is still the previous room's. Its generation may well match
        // ours, so the subject is the only thing that can say this is not our
        // data.
        guard accepts(snapshot.subject) else { return }

        tracker.reset(items: snapshot.items, seq: snapshot.seq)
        onUpdate(tracker.items)
    }
}
