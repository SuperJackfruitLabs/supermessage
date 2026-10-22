import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

/// The roster fixtures, one boundary check for the roster views the core now
/// owns (filters, inbox, agents, status — tested in `core::roster`), the
/// generated avatar tile, and the first-run demo.
@MainActor
struct RosterViewsTests {
    static let now = Date(timeIntervalSince1970: 1_700_000_000)

    static func row(
        _ id: String,
        minutesAgo: Double = 1,
        unread: UInt64 = 0,
        pending: Bool = false,
        agent: Bool = false,
        invited: Bool = false
    ) -> RoomRow {
        let ms = UInt64((now.timeIntervalSince1970 - minutesAgo * 60) * 1000)
        return RoomRow(
            room: RoomSummary(
                id: id, name: id, avatarUrl: nil, unread: unread, lastMessage: "hi",
                lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: ms,
                runtime: agent ? RuntimeDto(harness: "claude-code", host: "foundry") : nil,
                membership: invited ? .invited : .joined),
            identity: RoomIdentity(glyph: nil, name: id, role: nil, initial: "X"),
            preview: RoomPreview(text: "hi", pending: pending),
            affordance: invited ? .respondToInvitation : .compose)
    }

    /// Newest first: a is newest, e is oldest.
    static var roster: [RoomRow] {
        [
            row("a", minutesAgo: 1, unread: 3, agent: true),
            row("b", minutesAgo: 2, pending: true, agent: true),
            row("c", minutesAgo: 3, unread: 1),
            row("d", minutesAgo: 4, invited: true),
            row("e", minutesAgo: 5, pending: true),
        ]
    }

    static func ids(_ sections: [RosterSection]) -> [[String]] {
        sections.map { $0.rows.map(\.row.room.id) }
    }

    static func waiting() -> [RosterSection] {
        RosterArrangement.sections(roster, view: .waiting, showsInvitations: false, now: now)
    }

    // --- the core's views ----------------------------------------------------

    @Test("the roster views are the core's")
    func rosterViewsAreTheCores() {
        // The rules and their falsification live in the core's tests; this is
        // the boundary check, and what catches a checksum mismatch at run time.
        #expect(RosterArrangement.needsYouInbox(Self.roster, now: Self.now).badgeCount == 2)
        #expect(
            Self.ids(rosterFilterSections(sections: Self.waiting(), filter: .unread))
                == [["a", "c"]])
    }

    // --- avatar tile -------------------------------------------------------

    @Test("the tile is the core's")
    func avatarIsTheCores() {
        // The rules and their falsification live in the core's tests; this
        // is the boundary check, and what catches a checksum mismatch at run time.
        let style = agentAvatarStyle(roomId: "!atlas:example.org")
        #expect(style.variant == 3)
        #expect(style.ground == .surfaceRaised)
        #expect(style.ink == .accent)
    }

    // --- first-run demo ----------------------------------------------------

    @Test("the demo only gets past the card on the reader's answer")
    func demoSteps() {
        let demo = FirstRunDemo()
        #expect(!demo.answer("approve"), "answered a card that was not on screen")
        demo.advance()
        demo.advance()
        #expect(demo.step == .waiting)
        demo.advance()
        #expect(demo.step == .waiting, "the demo approved itself")
        #expect(demo.answer("approve"))
        #expect(demo.step == .approved)
        #expect(demo.answer == "approve")
        #expect(!demo.answer("reject"), "a second answer replaced the first")
        #expect(demo.answer == "approve")
    }
}
