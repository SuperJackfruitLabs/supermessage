import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

/// The product decisions behind the roster, each stated once.
@MainActor
struct RosterArrangementTests {
    static let now = Date(timeIntervalSince1970: 1_700_000_000)

    static func row(
        _ id: String,
        minutesAgo: Double = 1,
        pending: Bool = false,
        host: String? = nil,
        invited: Bool = false
    ) -> RoomRow {
        let ms = UInt64((now.timeIntervalSince1970 - minutesAgo * 60) * 1000)
        let summary = RoomSummary(
            id: id, name: id, avatarUrl: nil, unread: 0, lastMessage: "hi",
            lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
            lastActivityMs: ms,
            runtime: host.map { RuntimeDto(harness: "OpenClaw", host: $0) },
            membership: invited ? .invited : .joined)
        return RoomRow(
            room: summary,
            identity: RoomIdentity(glyph: nil, name: id, role: nil, initial: "X"),
            preview: RoomPreview(text: "hi", pending: pending),
            affordance: invited ? .respondToInvitation : .compose)
    }

    // --- state ------------------------------------------------------------

    @Test("owing an answer outranks how recently a room spoke")
    func pendingWins() {
        // A room that needs you is not described by its timestamp, however
        // fresh or stale that is.
        let fresh = Self.row("a", minutesAgo: 1, pending: true)
        let stale = Self.row("b", minutesAgo: 60 * 24 * 30, pending: true)
        #expect(RosterArrangement.state(for: fresh, now: Self.now) == .needsYou)
        #expect(RosterArrangement.state(for: stale, now: Self.now) == .needsYou)
    }

    @Test("recency reads as active, then idle, then quiet")
    func agesThroughStates() {
        #expect(RosterArrangement.state(for: Self.row("a", minutesAgo: 2), now: Self.now) == .active)
        #expect(RosterArrangement.state(for: Self.row("b", minutesAgo: 120), now: Self.now) == .idle)
        #expect(
            RosterArrangement.state(for: Self.row("c", minutesAgo: 60 * 48), now: Self.now)
                == .quiet)
    }

    @Test("a room that never said anything is quiet, not active")
    func silenceIsNotFreshness() {
        // `lastActivityMs` is nil for a room with no events. Treating a
        // missing timestamp as "now" would put empty rooms at the top of a
        // roster sorted by life.
        var row = Self.row("a")
        row = RoomRow(
            room: RoomSummary(
                id: "a", name: "a", avatarUrl: nil, unread: 0, lastMessage: nil,
                lastMessageIsOwn: false, lastMessageNamesSender: false, lastEventType: nil,
                lastActivityMs: nil, runtime: nil, membership: .joined),
            identity: row.identity, preview: nil, affordance: .compose)
        #expect(RosterArrangement.state(for: row, now: Self.now) == .quiet)
    }

    // --- arrangement ------------------------------------------------------

    @Test("invitations are withheld but counted")
    func invitationsHiddenNotLost() {
        // Hidden must never mean gone. A roster that silently drops a room you
        // were invited to is a roster that lost it.
        let rows = [Self.row("a"), Self.row("i", invited: true)]
        let sections = RosterArrangement.sections(
            rows, view: .recent, showsInvitations: false, now: Self.now)
        #expect(sections.flatMap(\.rows).count == 1)
        #expect(RosterArrangement.hiddenInvitations(rows, showsInvitations: false) == 1)
        #expect(RosterArrangement.hiddenInvitations(rows, showsInvitations: true) == 0)
    }

    @Test("what needs you comes first, whatever spoke last")
    func waitingIsPromoted() {
        let rows = [
            Self.row("fresh", minutesAgo: 1),
            Self.row("owed", minutesAgo: 600, pending: true),
        ]
        let sections = RosterArrangement.sections(
            rows, view: .waiting, showsInvitations: false, now: Self.now)
        #expect(sections.first?.attention == true)
        #expect(sections.first?.rows.map(\.room.id) == ["owed"])
        #expect(sections.last?.rows.map(\.room.id) == ["fresh"])
    }

    @Test("a quiet fleet gets no headings at all")
    func noWaitingNoSections() {
        // "Everything else" above the whole roster is a label for the absence
        // of a section.
        let sections = RosterArrangement.sections(
            [Self.row("a")], view: .waiting, showsInvitations: false, now: Self.now)
        #expect(sections.count == 1)
        #expect(sections[0].title == nil)
    }

    @Test("machines group their agents and say how many want something")
    func groupsByHost() {
        let rows = [
            Self.row("g", host: "Ashram"),
            Self.row("k", pending: true, host: "Ashram"),
            Self.row("s", host: "Pi"),
        ]
        let sections = RosterArrangement.sections(
            rows, view: .machine, showsInvitations: false, now: Self.now)
        #expect(sections.map(\.id) == ["Ashram", "Pi"])
        #expect(sections[0].detail == "2 agents · 1 waiting")
        #expect(sections[0].attention)
        #expect(sections[1].detail == "1 agent")
        #expect(!sections[1].attention)
    }

    @Test("a room with no runtime is filed, not guessed at")
    func roomsWithoutARuntime() {
        // Rooms people made have no harness and no host. They belong in the
        // roster; they do not belong under someone's laptop.
        let sections = RosterArrangement.sections(
            [Self.row("g", host: "Ashram"), Self.row("notes")],
            view: .machine, showsInvitations: false, now: Self.now)
        #expect(sections.map(\.id) == ["Ashram", "Elsewhere"])
    }

    @Test("every arrangement orders by recency inside a section")
    func recencyWithinSections() {
        let rows = [Self.row("old", minutesAgo: 90), Self.row("new", minutesAgo: 2)]
        for view in RosterView.allCases {
            let sections = RosterArrangement.sections(
                rows, view: view, showsInvitations: false, now: Self.now)
            let ordered: [String] = sections.flatMap { $0.rows }.map { $0.room.id }
            #expect(
                ordered.first == "new",
                "\(view) did not put the newest first")
        }
    }
}
