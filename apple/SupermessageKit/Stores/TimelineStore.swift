import Foundation
import Observation
import SupermessageFFI

/// The focused room's timeline.
///
/// Only one room is ever subscribed. Switching rooms restarts the core's
/// sequence counter at 1, so `subscribeTo` resets local tracking to a fresh
/// generation **before** issuing the subscribe — and the `accepts` filter
/// rejects anything belonging to a room this store is no longer showing.
///
/// Resetting alone was not enough on the desktop, and the sequence that broke
/// it is worth keeping in view:
///
///   1. `subscribeTo("!b")` resets tracking, then awaits the subscribe, which
///      has to build `room.timeline()` — slow.
///   2. Room A's subscription is still installed and still emitting. Its next
///      envelope arrives at, say, seq 12 against a tracker expecting 1: a gap.
///   3. The resync that gap triggers is a fast mutex read, so it beats the
///      subscribe — and it is served out of room A's still-installed handle.
///      The tracker now holds A's items at A's high seq.
///   4. Room B's stream finally starts at seq 1, 2, 3 — all below what the
///      tracker expects, so all discarded as duplicates.
///
/// Room A's messages then sit under room B's header until the next switch.
/// Rejecting anything whose subject is not the focused room turns steps 2 and
/// 3 into no-ops.
@MainActor
@Observable
public final class TimelineStore {
    public private(set) var items: [TimelineRow] = []
    public private(set) var roomId: String?
    /// Set while a back-pagination round trip is in flight, so the view can
    /// show it and so two do not overlap.
    public private(set) var isPaginating = false
    /// False once the core reports there is no more history to fetch.
    public private(set) var canPaginate = true

    private let client: CoreClient
    private let sink: any CoreEventSink
    private var sync: GapSync<TimelineRow>?

    public init(client: CoreClient, sink: any CoreEventSink) {
        self.client = client
        self.sink = sink
        sync = GapSync(
            resync: { [client] in
                let snapshot = try await client.timelineResync()
                return Snapshot(
                    subject: snapshot.roomId, seq: snapshot.seq, items: snapshot.items)
            },
            // The subject is the focused room id, and it changes under this
            // store while a subscribe round trip is in flight.
            accepts: { [weak self] subject in subject == self?.roomId },
            onUpdate: { [weak self] items in self?.items = items })
    }

    public func handle(_ envelope: TimelineDiffEnvelope) {
        sync?.handle(
            subject: envelope.subject, seq: envelope.seq, ops: envelope.ops.map(\.generic))
    }

    /// Focus a room. Safe to call for the room already open — it does nothing.
    public func subscribeTo(_ roomId: String) async {
        guard roomId != self.roomId else { return }
        // Order matters: reset and re-point *before* the round trip, so
        // anything the previous room emits while it is in flight is rejected
        // by `accepts` rather than mistaken for a gap.
        sync?.resetForNewSubscription()
        self.roomId = roomId
        items = []
        canPaginate = true
        try? await client.timelineSubscribe(roomId: roomId, sink: sink)
    }

    /// Fetch older messages. `false` when there are none left.
    @discardableResult
    public func paginateBack(count: UInt16 = 20) async -> Bool {
        guard let roomId, !isPaginating, canPaginate else { return false }
        isPaginating = true
        defer { isPaginating = false }

        // **`paginate_backwards` returns whether it hit the *start* of the
        // timeline**, not whether more remains — the SDK documents it as
        // "Returns whether we hit the start of the timeline". Read the wrong
        // way round, the first successful page in any room with real history
        // (which does not reach the start) switched pagination off for good,
        // and nothing older than the opening screen would ever load.
        //
        // A failed call defaults to `false`: a network error is not evidence
        // that a room has no more history, and treating it as such would make
        // one dropped request permanent.
        let reachedStart =
            (try? await client.timelinePaginateBack(roomId: roomId, count: count)) ?? false
        applyPaginationResult(reachedStart: reachedStart)
        return canPaginate
    }

    /// Record what a pagination round trip reported.
    ///
    /// Separate from the call so the state transition can be tested without a
    /// homeserver — the inversion above was invisible until this had a name.
    func applyPaginationResult(reachedStart: Bool) {
        canPaginate = !reachedStart
    }

    public func markRead() async {
        guard let roomId else { return }
        try? await client.markRoomRead(roomId: roomId)
    }

    /// Re-ask for the timeline, for a store that came back to a quiet room.
    public func seed() async {
        await sync?.seed()
    }

    public func clear() {
        sync?.stop()
        items = []
        roomId = nil
    }
}
