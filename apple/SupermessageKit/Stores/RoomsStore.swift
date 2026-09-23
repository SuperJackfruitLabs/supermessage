import Foundation
import Observation
import SupermessageFFI

/// The roster.
///
/// Every row arrives with its name already split, its preview already composed
/// and its affordance already chosen — `RoomRow` carries all three, decided by
/// the core. **This store parses nothing**, and neither does the view above
/// it: that is what stops iOS and the desktop disagreeing about what a room is
/// called or whether it owes an answer.
@MainActor
@Observable
public final class RoomsStore {
    public private(set) var rooms: [RoomRow] = []
    public private(set) var selectedId: String?

    /// The name held across a roster that no longer contains the open room.
    ///
    /// A space switch re-emits the roster as a `Reset` that drops the room the
    /// reader is looking at. The selection, its timeline and its title all
    /// have to outlive that.
    private var selectedNameFallback: String?

    private let client: any RoomsSnapshotting
    private var sync: GapSync<RoomRow>?
    private let onSelect: (String) -> Void
    /// Mark a room read. A closure rather than a wider protocol on `client`,
    /// so a store built for a test without it still compiles, and says so by
    /// doing nothing.
    private let reader: (@Sendable (String) async throws -> Void)?

    public init(
        client: any RoomsSnapshotting,
        markRead reader: (@Sendable (String) async throws -> Void)? = nil,
        onSelect: @escaping (String) -> Void = { _ in }
    ) {
        self.client = client
        self.reader = reader
        self.onSelect = onSelect
        sync = GapSync(
            resync: { [client] in
                let snapshot = try await client.roomsSnapshot()
                // The room list is a single-subject channel — every envelope
                // is by definition ours — so the subject is empty and the
                // `accepts` filter is left at its default.
                return Snapshot(subject: "", seq: snapshot.seq, items: snapshot.rooms)
            },
            onUpdate: { [weak self] rooms in self?.rooms = rooms })
    }

    public func handle(_ envelope: RoomDiffEnvelope) {
        sync?.handle(
            subject: envelope.subject, seq: envelope.seq, ops: envelope.ops.map(\.generic))
    }

    /// Fetch the roster now, rather than waiting for something to change.
    public func seed() async {
        await sync?.seed()
    }

    public func select(_ roomId: String) {
        selectedId = roomId
        selectedNameFallback = row(for: roomId)?.room.name
        onSelect(roomId)
    }

    /// Close whatever room is open, leaving the roster alone.
    ///
    /// For a phone coming back from a conversation: there, the roster is the
    /// previous screen rather than a column beside the room, so nothing is
    /// selected once you have returned to it. Distinct from `clear`, which
    /// empties the roster too and belongs to signing out.
    public func deselect() {
        selectedId = nil
        selectedNameFallback = nil
    }

    /// Mark `roomId` read from the roster, without opening it.
    ///
    /// The core's `mark_room_read`, the same call opening a room makes. The
    /// row's unread count is not touched here: the core re-emits the row when
    /// the receipt lands, and a count that dropped locally before the
    /// homeserver agreed would be a count the roster made up. Returns whether
    /// it landed.
    @discardableResult
    public func markRead(_ roomId: String) async -> Bool {
        guard let reader else { return false }
        do {
            try await reader(roomId)
            return true
        } catch {
            return false
        }
    }

    public func row(for roomId: String) -> RoomRow? {
        rooms.first { $0.room.id == roomId }
    }

    public var selectedRow: RoomRow? {
        selectedId.flatMap(row(for:))
    }

    /// The open room's title, surviving its disappearance from the roster.
    public var selectedName: String? {
        selectedRow?.identity.name ?? selectedNameFallback
    }

    public func clear() {
        rooms = []
        selectedId = nil
        selectedNameFallback = nil
        sync?.stop()
    }

    /// Undo `clear`'s stop, for a sign-in that follows a sign-out.
    ///
    /// The sync is built in `init` and never rebuilt, so its stopped latch
    /// outlives the session that set it. Unlike `TimelineStore` there is no
    /// per-subscription reset to carry this, so it is its own call — and
    /// `Session` makes it before the core is given the sink, so a diff
    /// arriving immediately cannot land on a still-stopped sync.
    public func resume() {
        sync?.resume()
    }
}
