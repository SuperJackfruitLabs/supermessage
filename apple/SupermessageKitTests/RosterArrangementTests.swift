import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

/// The roster's rules, as this host sees them through the boundary.
///
/// **The rules themselves live in `core::roster`,** and are stated once
/// there, in Rust, with their own tests — they are product decisions about
/// what a fleet looks like, and two hosts each holding a copy is two clients
/// that disagree about what a roster is.
///
/// What remains worth asserting here is that the boundary carries those
/// answers faithfully: the choice maps to the right core view, sections come
/// back in order, and each row arrives already knowing its state. A rule that
/// changes should fail in Rust first and here second.
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
        #expect(sections.first?.rows.map(\.row.room.id) == ["owed"])
        #expect(sections.last?.rows.map(\.row.room.id) == ["fresh"])
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

    @Test("a row crosses the boundary already knowing what it is doing")
    func stateRidesOnTheRow() {
        // The reason `RosterRow` exists. A host that asked per row would pay
        // a boundary crossing per visible room per re-render, so if this ever
        // comes back `.quiet` for everything, the list has quietly lost its
        // state and only the dots would show it.
        let rows = [
            Self.row("owed", pending: true),
            Self.row("fresh", minutesAgo: 1),
            Self.row("ancient", minutesAgo: 60 * 24 * 30),
        ]
        let sections = RosterArrangement.sections(
            rows, view: .recent, showsInvitations: false, now: Self.now)
        let states = Dictionary(
            uniqueKeysWithValues: sections.flatMap(\.rows).map { ($0.row.room.id, $0.state) })

        #expect(states["owed"] == .needsYou)
        #expect(states["fresh"] == .active)
        #expect(states["ancient"] == .quiet)
    }

    @Test("each choice reaches the arrangement it names")
    func choicesMapToCoreViews() {
        // Three enums with the same three cases is exactly the shape that
        // silently maps `.waiting` to `.recent` and looks fine.
        let rows = [Self.row("owed", pending: true), Self.row("fresh")]
        #expect(
            RosterArrangement.sections(
                rows, view: .waiting, showsInvitations: false, now: Self.now
            ).first?.title == "Waiting on you")
        #expect(
            RosterArrangement.sections(
                rows, view: .recent, showsInvitations: false, now: Self.now
            ).first?.title == nil)
        #expect(
            RosterArrangement.sections(
                rows, view: .machine, showsInvitations: false, now: Self.now
            ).first?.title == "Elsewhere")
    }

    @Test("every arrangement orders by recency inside a section")
    func recencyWithinSections() {
        let rows = [Self.row("old", minutesAgo: 90), Self.row("new", minutesAgo: 2)]
        for view in RosterChoice.allCases {
            let sections = RosterArrangement.sections(
                rows, view: view, showsInvitations: false, now: Self.now)
            let ordered: [String] = sections.flatMap { $0.rows }.map { $0.row.room.id }
            #expect(
                ordered.first == "new",
                "\(view) did not put the newest first")
        }
    }
}
