import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

/// Marking a room read from the roster — the leading swipe.
@MainActor
struct RoomsStoreMarkReadTests {
    struct NoRooms: RoomsSnapshotting {
        func roomsSnapshot() async throws -> RoomsSnapshot { RoomsSnapshot(seq: 0, rooms: []) }
    }

    actor Calls {
        var ids: [String] = []
        func record(_ id: String) { ids.append(id) }
    }

    struct Refused: Error {}

    @Test("mark read asks the core about the swiped room, not the open one")
    func marksTheNamedRoom() async {
        let calls = Calls()
        let rooms = RoomsStore(client: NoRooms(), markRead: { id in await calls.record(id) })
        rooms.select("!open:x")

        #expect(await rooms.markRead("!swiped:x"))
        #expect(await calls.ids == ["!swiped:x"])
    }

    @Test("a refusal is reported rather than swallowed as success")
    func refusalIsFalse() async {
        let rooms = RoomsStore(client: NoRooms(), markRead: { _ in throw Refused() })
        #expect(await rooms.markRead("!a:x") == false)
    }

    @Test("a store with no reader says it did nothing")
    func noReader() async {
        let rooms = RoomsStore(client: NoRooms())
        #expect(await rooms.markRead("!a:x") == false)
    }
}
