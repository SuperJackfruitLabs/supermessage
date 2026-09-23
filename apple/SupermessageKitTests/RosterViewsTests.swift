import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

/// The four destinations' views over the roster: filter chips, the Needs you
/// inbox, the Agents directory, the header status, and the generated avatar
/// tile. Each is checked against the real core — `rosterSections` crosses
/// the boundary in every one of these — so a test here cannot pass against an
/// order the core would never produce.
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

    // --- filters -----------------------------------------------------------

    @Test("All is the core's arrangement, unchanged")
    func allIsIdentity() {
        #expect(RosterFilter.all.apply(Self.waiting()) == Self.waiting())
    }

    @Test("Unread keeps only rooms with unread messages, in the core's order")
    func unreadFilters() {
        // Waiting view: [b, e] waiting, then [a, c] — only a and c are unread.
        #expect(Self.ids(RosterFilter.unread.apply(Self.waiting())) == [["a", "c"]])
    }

    @Test("Agents keeps rooms that read as an agent's, across sections")
    func agentsFilters() {
        #expect(Self.ids(RosterFilter.agents.apply(Self.waiting())) == [["b"], ["a"]])
    }

    @Test("Needs you keeps rooms owing an answer")
    func needsYouFilters() {
        #expect(Self.ids(RosterFilter.needsYou.apply(Self.waiting())) == [["b", "e"]])
    }

    @Test("filtering never reorders: the core's order survives in every chip")
    func filteringPreservesOrder() {
        let recent = RosterArrangement.sections(
            Self.roster, view: .recent, showsInvitations: true, now: Self.now)
        for filter in RosterFilter.allCases {
            let kept = RosterFilter.filterIDs(filter.apply(recent))
            let expected = recent.flatMap(\.rows).filter(filter.admits).map(\.row.room.id)
            #expect(kept == expected, "\(filter) reordered the roster")
        }
    }

    @Test("a section that lost rows drops its count; an untouched one keeps it")
    func detailFollowsTheRows() {
        let sections = Self.waiting()
        #expect(sections.first?.detail == "2", "fixture: the waiting section counts two")

        // Agents keeps only b of [b, e]: "2" would be a number not on screen.
        let agents = RosterFilter.agents.apply(sections)
        #expect(agents.first?.detail == nil)

        // Needs you keeps both waiting rows, so the count is still true.
        let needsYou = RosterFilter.needsYou.apply(sections)
        #expect(needsYou.first?.detail == "2")
        #expect(needsYou.first?.title == sections.first?.title)
    }

    // --- inbox -------------------------------------------------------------

    @Test("the inbox is pending decisions plus invitations, and the badge counts both")
    func inboxComposition() {
        let inbox = NeedsYouInbox.from(Self.roster, now: Self.now)
        #expect(inbox.decisions.map(\.row.room.id) == ["b", "e"])
        #expect(inbox.invitations.map(\.row.room.id) == ["d"])
        #expect(inbox.count == 3)
        #expect(!inbox.isEmpty)
    }

    @Test("an answered decision leaves the inbox, and an empty inbox says so")
    func inboxIsFinishable() {
        let answered = [Self.row("b", pending: false, agent: true), Self.row("c", unread: 4)]
        let inbox = NeedsYouInbox.from(answered, now: Self.now)
        #expect(inbox.isEmpty)
        #expect(inbox.count == 0)
    }

    @Test("an invitation is not also counted as a decision")
    func invitationCountedOnce() {
        let invite = Self.row("x", pending: true, invited: true)
        let inbox = NeedsYouInbox.from([invite], now: Self.now)
        #expect(inbox.decisions.isEmpty)
        #expect(inbox.count == 1)
    }

    // --- agents ------------------------------------------------------------

    @Test("the agents tab lists agent rooms newest first and leaves out invitations")
    func agentDirectory() {
        let invitedAgent = Self.row("z", minutesAgo: 0.5, agent: true, invited: true)
        let rows = AgentDirectory.rows(Self.roster + [invitedAgent], now: Self.now)
        #expect(rows.map(\.row.room.id) == ["a", "b"])
    }

    // --- header status -----------------------------------------------------

    @Test("the header status vocabulary")
    func headerStatus() {
        #expect(RoomStatus.of(state: .active, describesAgent: true, turnInProgress: true) == .working)
        #expect(RoomStatus.of(state: .needsYou, describesAgent: true, turnInProgress: true) == .needsYou)
        // The roster's green dot and the header must agree: spoke recently
        // is "Active", not "Idle".
        #expect(RoomStatus.of(state: .active, describesAgent: true, turnInProgress: false) == .active)
        #expect(RoomStatus.of(state: .idle, describesAgent: true, turnInProgress: false) == .idle)
        #expect(RoomStatus.of(state: .quiet, describesAgent: true, turnInProgress: false) == .quiet)
        #expect(RoomStatus.of(state: .needsYou, describesAgent: false, turnInProgress: false) == nil)
        #expect(RoomStatus.of(state: .active, describesAgent: false, turnInProgress: true) == nil)
    }

    @Test("a single room's roster row is the one the core arranged")
    func rosterRowLookup() {
        let row = RosterArrangement.rosterRow(for: "b", in: Self.roster, now: Self.now)
        #expect(row?.state == .needsYou)
        #expect(row?.describesAgent == true)
        #expect(RosterArrangement.rosterRow(for: "c", in: Self.roster, now: Self.now)?.describesAgent == false)
        #expect(RosterArrangement.rosterRow(for: "nope", in: Self.roster, now: Self.now) == nil)
    }

    @Test("unmuting restores the default rather than inventing a rule")
    func muteToggle() {
        #expect(RoomToggles.nextNotificationMode(from: .muted) == .default)
        #expect(RoomToggles.nextNotificationMode(from: .default) == .muted)
        #expect(RoomToggles.nextNotificationMode(from: .allMessages) == .muted)
        #expect(RoomToggles.nextNotificationMode(from: .mentionsOnly) == .muted)
    }

    // --- avatar tile -------------------------------------------------------

    @Test("a room's tile is the same every time it is asked for")
    func avatarIsDeterministic() {
        // Pinned values, not just "equal to itself": `Hasher` would pass a
        // same-process equality check and still repaint every room on the
        // next launch.
        #expect(AgentAvatarStyle.fnv1a("") == 0xcbf2_9ce4_8422_2325)
        #expect(AgentAvatarStyle.fnv1a("a") == 0xaf63_dc4c_8601_ec8c)
        #expect(AgentAvatarStyle.forRoom("!atlas:example.org") == AgentAvatarStyle.forRoom("!atlas:example.org"))
    }

    @Test("tiles spread across the palette rather than collapsing onto one")
    func avatarSpreads() {
        let ids = (0..<60).map { "!room\($0):example.org" }
        let used = Set(ids.map { AgentAvatarStyle.variants.firstIndex(of: AgentAvatarStyle.forRoom($0))! })
        #expect(used.count == AgentAvatarStyle.variants.count)
    }

    @Test("no tile draws amber or red, and no tile is ink on its own ground")
    func avatarPalette() {
        for style in AgentAvatarStyle.variants {
            #expect(style.ground != style.ink)
        }
        let roles = Set(AgentAvatarStyle.Role.allCases.map(\.rawValue))
        #expect(!roles.contains("signal"))
        #expect(!roles.contains("danger"))
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

extension RosterFilter {
    fileprivate static func filterIDs(_ sections: [RosterSection]) -> [String] {
        sections.flatMap(\.rows).map(\.row.room.id)
    }
}
