import Testing

@testable import SupermessageKit
import SupermessageFFI

/// What is specific to the roster store. The gap/resync machinery underneath
/// it is `GapSync`'s, and is tested there rather than again through a second
/// front door.
@MainActor
struct RoomsStoreTests {
    static func row(_ id: String, name: String, membership: Membership = .joined) -> RoomRow {
        RoomRow(
            room: RoomSummary(
                id: id, name: name, avatarUrl: nil, unread: 0, lastMessage: nil,
                lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: nil, membership: membership),
            identity: RoomIdentity(glyph: nil, name: name, role: nil, initial: "X"),
            preview: nil,
            affordance: membership == .joined ? .compose : .respondToInvitation)
    }

    func store() -> RoomsStore {
        RoomsStore(client: CoreClient(dataDirectory: CoreClient.dataDirectory()))
    }

    @Test("the open room's name survives it being filtered out of the roster")
    func selectionOutlivesAReset() {
        // Exactly what a space switch does: the core re-emits the roster as a
        // Reset that no longer contains the open room. The selection, its
        // timeline and its title all have to outlive that, or switching space
        // with a room open blanks the header.
        let rooms = store()
        rooms.handle(
            RoomDiffEnvelope(
                channel: "sm://rooms/diff", subject: "", seq: 1,
                ops: [.reset(values: [Self.row("!a:x", name: "Ganesha")])]))
        rooms.select("!a:x")
        #expect(rooms.selectedName == "Ganesha")

        rooms.handle(
            RoomDiffEnvelope(
                channel: "sm://rooms/diff", subject: "", seq: 2,
                ops: [.reset(values: [Self.row("!b:x", name: "Ops")])]))

        #expect(rooms.selectedId == "!a:x", "the selection was dropped")
        #expect(rooms.selectedName == "Ganesha", "the header lost its title")
    }

    @Test("a rename lands immediately, because the row is the live one")
    func renameLands() {
        let rooms = store()
        rooms.handle(
            RoomDiffEnvelope(
                channel: "sm://rooms/diff", subject: "", seq: 1,
                ops: [.reset(values: [Self.row("!a:x", name: "Ganesha")])]))
        rooms.select("!a:x")

        rooms.handle(
            RoomDiffEnvelope(
                channel: "sm://rooms/diff", subject: "", seq: 2,
                ops: [.set(index: 0, value: Self.row("!a:x", name: "Ganesha Prime"))]))

        #expect(rooms.selectedName == "Ganesha Prime")
    }

    @Test("clearing drops everything, so a second account starts empty")
    func clearIsThorough() {
        let rooms = store()
        rooms.handle(
            RoomDiffEnvelope(
                channel: "sm://rooms/diff", subject: "", seq: 1,
                ops: [.reset(values: [Self.row("!a:x", name: "Ganesha")])]))
        rooms.select("!a:x")

        rooms.clear()

        #expect(rooms.rooms.isEmpty)
        #expect(rooms.selectedId == nil)
        #expect(rooms.selectedName == nil, "a stale title outlived the sign-out")
    }
}
