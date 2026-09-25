import Foundation
import Testing

@testable import SupermessageKit
import SupermessageFFI

@MainActor
struct TimelineGroupingTests {
    static func row(
        id: String, sender: String, at ms: UInt64, isOwn: Bool = false, system: Bool = false
    ) -> TimelineRow {
        let item = TimelineItemDto(
            id: id, eventId: id, kind: system ? "state" : "message",
            msgtype: system ? nil : "m.text",
            detail: nil, sender: sender, senderDisplayName: nil, senderAvatar: nil, body: "hi", formattedBody: nil,
            media: nil, customPayload: nil, timestampMs: ms, isOwn: isOwn, sendState: nil,
            replyTo: nil, edited: false, reactions: [], readBy: [], editable: false, membershipSubject: nil)
        return TimelineRow(
            item: item,
            view: system ? .system(kind: .encryptionEnabled, text: "something happened") : .bubble(muted: false, blocks: []),
            senderName: sender, senderShort: sender, senderInitial: "?", membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
            replyPreview: nil)
    }

    @Test("a second message from the same sender, moments later, continues the run")
    func continuesForSameSender() {
        let first = Self.row(id: "$1", sender: "@a:x", at: 1_000)
        let second = Self.row(id: "$2", sender: "@a:x", at: 60_000)
        #expect(TimelineGrouping.continuesRun(second, after: first))
    }

    @Test("a different sender starts a new run")
    func breaksOnSender() {
        let first = Self.row(id: "$1", sender: "@a:x", at: 1_000)
        let second = Self.row(id: "$2", sender: "@b:x", at: 2_000)
        #expect(!TimelineGrouping.continuesRun(second, after: first))
    }

    @Test("a long gap starts a new run, even from the same sender")
    func breaksOnTime() {
        // Two messages an hour apart are two turns, whoever sent them.
        let first = Self.row(id: "$1", sender: "@a:x", at: 0)
        let second = Self.row(id: "$2", sender: "@a:x", at: TimelineGrouping.runWindowMs + 1)
        #expect(!TimelineGrouping.continuesRun(second, after: first))
        let inside = Self.row(id: "$3", sender: "@a:x", at: TimelineGrouping.runWindowMs)
        #expect(TimelineGrouping.continuesRun(inside, after: first))
    }

    @Test("anything that is not an ordinary message ends the run")
    func breaksOnNonMessage() {
        // Otherwise a message after a card reads as though the card's author
        // said it.
        let card = Self.row(id: "$1", sender: "@a:x", at: 1_000, system: true)
        let message = Self.row(id: "$2", sender: "@a:x", at: 2_000)
        #expect(!TimelineGrouping.continuesRun(message, after: card))
        #expect(!TimelineGrouping.continuesRun(card, after: message))
    }

    @Test("the first row never continues anything")
    func firstRowStandsAlone() {
        #expect(!TimelineGrouping.continuesRun(Self.row(id: "$1", sender: "@a:x", at: 1), after: nil))
    }

    @Test("an own message does not join a peer's run")
    func ownDoesNotJoinPeer() {
        // They are laid out on opposite sides; joining them would put a
        // trailing bubble under a leading header.
        let peer = Self.row(id: "$1", sender: "@a:x", at: 1_000)
        let own = Self.row(id: "$2", sender: "@a:x", at: 2_000, isOwn: true)
        #expect(!TimelineGrouping.continuesRun(own, after: peer))
    }

    @Test("a room where one agent speaks does not repeat its runtime")
    func singleSpeaker() {
        // The suffix is the same words under every message there, and the
        // header already says the name.
        #expect(
            TimelineGrouping.hasSingleSpeaker([
                Self.row(id: "1", sender: "@a:x", at: 1),
                Self.row(id: "2", sender: "@a:x", at: 2),
            ]))
    }

    @Test("a room where several speak keeps it")
    func severalSpeakers() {
        #expect(
            !TimelineGrouping.hasSingleSpeaker([
                Self.row(id: "1", sender: "@a:x", at: 1),
                Self.row(id: "2", sender: "@b:x", at: 2),
            ]))
    }

    @Test("your own messages do not make a room multi-voiced")
    func ownMessagesDoNotCount() {
        // Own messages are attributed by position, not by name, so they say
        // nothing about how many agents are talking.
        #expect(
            TimelineGrouping.hasSingleSpeaker([
                Self.row(id: "1", sender: "@a:x", at: 1),
                Self.row(id: "2", sender: "@me:x", at: 2, isOwn: true),
            ]))
    }
}

/// Collapsing membership churn, ported from the desktop.
@MainActor
struct MembershipRunTests {
    static func membership(_ id: String, _ sender: String, _ verb: String) -> TimelineRow {
        var row = TimelineGroupingTests.row(id: id, sender: sender, at: 1, system: true)
        let item = TimelineItemDto(
            id: id, eventId: id, kind: "membership", msgtype: nil, detail: verb,
            sender: sender, senderDisplayName: sender, senderAvatar: nil, body: nil, formattedBody: nil,
            media: nil, customPayload: nil, timestampMs: 1, isOwn: false, sendState: nil,
            replyTo: nil, edited: false, reactions: [], readBy: [], editable: false, membershipSubject: nil)
        row = TimelineRow(
            item: item, view: .system(kind: .membershipChanged(who: sender, detail: verb), text: "\(sender) \(verb)"), senderName: sender,
            senderShort: sender, senderInitial: "?", membershipVerb: verb, replyQuote: nil,
            canReplyOrReact: false, replyPreview: nil)
        return row
    }

    @Test("a run of the same change becomes one sentence")
    func collapsesARun() {
        // Ten identical "updated their membership" lines is what this replaces.
        let rows = [
            Self.membership("1", "Ganesha", "joined the room"),
            Self.membership("2", "Krishna", "joined the room"),
            Self.membership("3", "Annapurna", "joined the room"),
            Self.membership("4", "Surya", "joined the room"),
        ]
        let out = TimelineGrouping.collapseMembershipRuns(rows)
        #expect(out.count == 1)
        guard case let .membershipRun(_, text, _) = out[0] else {
            Issue.record("expected a run")
            return
        }
        #expect(text == "Ganesha, Krishna and 2 others joined the room")
    }

    @Test("interleaved churn by several people collapses into one line")
    func collapsesAStretch() {
        // The shape of the 2026-09-23 recording: two accounts and a person
        // interleaving, which neither of the first two rules can merge.
        let rows = [
            Self.membership("1", "Strategy Sam", "was invited"),
            Self.membership("2", "Rakesh", "updated their membership"),
            Self.membership("3", "Strategy Sam", "left the room"),
            Self.membership("4", "strategy-sam", "was invited"),
            Self.membership("5", "Strategy Sam", "joined the room"),
        ]
        let out = TimelineGrouping.collapseMembershipRuns(rows)
        #expect(out.count == 1)
        guard case let .membershipRun(id, text, collapsed) = out.first else {
            Issue.record("expected one summary line"); return
        }
        #expect(TimelineGrouping.isStretch(id))
        #expect(collapsed.count == 5)
        #expect(text == "5 membership changes · Strategy Sam, Rakesh and 1 other")
    }

    @Test("an opened stretch shows its lines again")
    func expandsAStretch() {
        let rows = [
            Self.membership("1", "Strategy Sam", "was invited"),
            Self.membership("2", "Rakesh", "updated their membership"),
            Self.membership("3", "Strategy Sam", "left the room"),
        ]
        let id = TimelineGrouping.stretchId("1")
        let out = TimelineGrouping.collapseMembershipRuns(rows, expanded: [id])
        #expect(out.count == 3)
    }

    @Test("two lines are not worth collapsing")
    func leavesAShortStretch() {
        let rows = [
            Self.membership("1", "Strategy Sam", "was invited"),
            Self.membership("2", "Rakesh", "updated their membership"),
        ]
        #expect(TimelineGrouping.collapseMembershipRuns(rows).count == 2)
    }

    @Test("one person changing twice is named once")
    func namesDistinctPeople() {
        let rows = [
            Self.membership("1", "Annapurna", "updated their membership"),
            Self.membership("2", "Annapurna", "updated their membership"),
            Self.membership("3", "Surya", "updated their membership"),
        ]
        #expect(
            TimelineGrouping.text(for: rows) == "Annapurna and Surya updated their membership")
    }

    @Test("a run of one reads exactly like an ungrouped line")
    func singleReadsNormally() {
        // Never "Ganesha and 0 others".
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.membership("1", "Ganesha", "joined the room")
        ])
        guard case let .membershipRun(_, text, _) = out[0] else {
            Issue.record("expected a run")
            return
        }
        #expect(text == "Ganesha joined the room")
    }

    @Test("different verbs stay different sentences")
    func differentVerbsSplit() {
        // One sentence covering both would be true of neither.
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.membership("1", "Ganesha", "joined the room"),
            Self.membership("2", "Krishna", "left the room"),
        ])
        #expect(out.count == 2)
    }

    @Test("messages pass through untouched and break a run")
    func messagesInterrupt() {
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.membership("1", "Ganesha", "joined the room"),
            TimelineGroupingTests.row(id: "m", sender: "@a:x", at: 2),
            Self.membership("2", "Krishna", "joined the room"),
        ])
        #expect(out.count == 3)
        guard case .row = out[1] else {
            Issue.record("a message became part of a run")
            return
        }
    }
}

/// Rows the core said to draw nothing for.
@MainActor
struct SilentRowTests {
    static func silent(_ id: String) -> TimelineRow {
        let item = TimelineItemDto(
            id: id, eventId: id, kind: "state", msgtype: nil, detail: nil, sender: "@a:x",
            senderDisplayName: nil, senderAvatar: nil, body: nil, formattedBody: nil, media: nil,
            customPayload: nil, timestampMs: 1, isOwn: false, sendState: nil, replyTo: nil,
            edited: false, reactions: [], readBy: [], editable: false, membershipSubject: nil)
        return TimelineRow(
            item: item, view: .none, senderName: "a", senderShort: "a", senderInitial: "?", membershipVerb: nil,
            replyQuote: nil, canReplyOrReact: false, replyPreview: nil)
    }

    @Test("a row that draws nothing gets no row")
    func silentRowsAreDropped() {
        // A cell with no content does not reliably collapse to no height —
        // one appeared as roughly three hundred points of blank in the middle
        // of two different rooms. Deliberately silent means absent.
        let out = TimelineGrouping.collapseMembershipRuns([
            TimelineGroupingTests.row(id: "m", sender: "@a:x", at: 1),
            Self.silent("s"),
            TimelineGroupingTests.row(id: "n", sender: "@a:x", at: 2),
        ])
        #expect(out.count == 2)
        #expect(out.map(\.id) == ["m", "n"])
    }

    @Test("a silent row does not break a membership run either")
    func silentRowsDoNotSplitRuns() {
        let out = TimelineGrouping.collapseMembershipRuns([
            MembershipRunTests.membership("1", "Ganesha", "joined the room"),
            Self.silent("s"),
            MembershipRunTests.membership("2", "Krishna", "joined the room"),
        ])
        #expect(out.count == 1, "an invisible row split a run that a reader sees as one")
    }

    @Test("a membership change the core silenced is not drawn as one")
    func silentMembershipRowsAreDropped() {
        // A join -> join that changed nothing arrives as kind "membership"
        // with view `.none`. Filtering on kind instead of view would turn it
        // back into "Krishna updated their membership".
        var noop = MembershipRunTests.membership("s", "Krishna", "updated their membership")
        noop.item.detail = "none"
        noop.view = .none
        let out = TimelineGrouping.collapseMembershipRuns([
            MembershipRunTests.membership("1", "Ganesha", "joined the room"),
            noop,
            MembershipRunTests.membership("2", "Annapurna", "joined the room"),
        ])
        #expect(out.count == 1)
        guard case let .membershipRun(_, text, rows) = out.first else {
            Issue.record("expected one membership run")
            return
        }
        #expect(text == "Ganesha and Annapurna joined the room")
        #expect(rows.map(\.item.id) == ["1", "2"])
    }
}

/// One person's churn, collapsed across verbs.
@MainActor
struct MembershipChurnTests {
    static func change(
        _ id: String, _ sender: String, _ verb: String, subject: String? = nil
    ) -> TimelineRow {
        var row = MembershipRunTests.membership(id, sender, verb)
        row.item.membershipSubject = subject
        return row
    }

    static func texts(_ out: [DisplayRow]) -> [String] {
        out.map { entry in
            if case let .membershipRun(_, text, _) = entry { return text }
            return "<row \(entry.id)>"
        }
    }

    @Test("one person joining, leaving and rejoining is one line")
    func churnIsOneLine() {
        // A bridge reconnecting did this to real rooms: three lines about one
        // agent, and nothing any of them said was news.
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.change("1", "Strategy Sam", "joined the room"),
            Self.change("2", "Strategy Sam", "left the room"),
            Self.change("3", "Strategy Sam", "joined the room"),
        ])
        #expect(Self.texts(out) == ["Strategy Sam made 3 membership changes"])
    }

    @Test("two changes read as what happened, in order")
    func twoChangesInOrder() {
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.change("1", "Strategy Sam", "joined the room"),
            Self.change("2", "Strategy Sam", "left the room"),
        ])
        #expect(Self.texts(out) == ["Strategy Sam joined the room, then left the room"])
    }

    @Test("a person's churn does not swallow the neighbours who share a verb with it")
    func churnKeepsNeighboursApart() {
        // Alice's "joined" matches Sam's first change, and Bob's "left" his
        // last: a rule that merged on the verb first would fold one of them
        // into a sentence about Sam. Three lines collapse into a summary
        // (rule 3), so this reads the stretch as the reader sees it opened.
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.change("1", "Alice", "joined the room"),
            Self.change("2", "Strategy Sam", "joined the room"),
            Self.change("3", "Strategy Sam", "left the room"),
            Self.change("4", "Bob", "left the room"),
        ], expanded: [TimelineGrouping.stretchId("1")])
        #expect(
            Self.texts(out) == [
                "Alice joined the room",
                "Strategy Sam joined the room, then left the room",
                "Bob left the room",
            ])
    }

    @Test("one person repeating one change still joins a crowd making it")
    func repeatedSameVerbStillGroups() {
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.change("1", "Annapurna", "updated their membership"),
            Self.change("2", "Annapurna", "updated their membership"),
            Self.change("3", "Surya", "updated their membership"),
        ])
        #expect(Self.texts(out) == ["Annapurna and Surya updated their membership"])
    }

    @Test("an invite and the join that follows are about the same person")
    func subjectIsThePerson() {
        // The invite is *sent* by someone else; it is about Sam. Keyed on the
        // sender, these were two people and two lines.
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.change("1", "Rakesh", "was invited", subject: "Strategy Sam"),
            Self.change("2", "Strategy Sam", "joined the room"),
        ])
        #expect(Self.texts(out) == ["Strategy Sam was invited, then joined the room"])
    }

    @Test("a message between two changes keeps them apart")
    func messageBreaksChurn() {
        let out = TimelineGrouping.collapseMembershipRuns([
            Self.change("1", "Strategy Sam", "joined the room"),
            TimelineGroupingTests.row(id: "m", sender: "@a:x", at: 2),
            Self.change("2", "Strategy Sam", "left the room"),
        ])
        #expect(
            Self.texts(out) == ["Strategy Sam joined the room", "<row m>", "Strategy Sam left the room"])
    }
}

/// Reply quotes that only repeat the row above.
@MainActor
struct QuoteRepetitionTests {
    static func reply(_ id: String, to parent: String) -> TimelineRow {
        var row = TimelineGroupingTests.row(id: id, sender: "@b:x", at: 2)
        row.item.replyTo = ReplyToDto(
            eventId: parent, available: true, sender: "@a:x", senderDisplayName: "a",
            excerpt: "hi", label: nil)
        return row
    }

    @Test("a reply directly under its parent drops the quote")
    func parentDirectlyAbove() {
        let parent = TimelineGroupingTests.row(id: "$p", sender: "@a:x", at: 1)
        #expect(TimelineGrouping.quoteRepeatsPrevious(Self.reply("$r", to: "$p"), after: parent))
    }

    @Test("a reply to anything further up keeps it")
    func parentElsewhere() {
        let between = TimelineGroupingTests.row(id: "$q", sender: "@a:x", at: 1)
        #expect(
            !TimelineGrouping.quoteRepeatsPrevious(Self.reply("$r", to: "$p"), after: between))
        #expect(!TimelineGrouping.quoteRepeatsPrevious(Self.reply("$r", to: "$p"), after: nil))
    }

    @Test("a local echo above has no event id, so it cannot be the parent")
    func localEchoAbove() {
        var echo = TimelineGroupingTests.row(id: "$p", sender: "@a:x", at: 1)
        echo.item.eventId = nil
        #expect(!TimelineGrouping.quoteRepeatsPrevious(Self.reply("$r", to: "$p"), after: echo))
    }

    @Test("a message that is not a reply never has a quote to drop")
    func notAReply() {
        let parent = TimelineGroupingTests.row(id: "$p", sender: "@a:x", at: 1)
        let plain = TimelineGroupingTests.row(id: "$r", sender: "@b:x", at: 2)
        #expect(!TimelineGrouping.quoteRepeatsPrevious(plain, after: parent))
    }
}

/// Agents and long reads.
@MainActor
struct AgentAndLongReadTests {
    @Test("the agent namespace marks an agent, and nothing else does")
    func agentNamespace() {
        #expect(TimelineGrouping.isAgent(TimelineGroupingTests.row(id: "1", sender: "@agent_ashram_openclaw-atlas:x", at: 1)))
        #expect(!TimelineGrouping.isAgent(TimelineGroupingTests.row(id: "2", sender: "@atlas:x", at: 1)))
        // Contains the word, not the prefix.
        #expect(!TimelineGrouping.isAgent(TimelineGroupingTests.row(id: "3", sender: "@my_agent_x:x", at: 1)))
        #expect(!TimelineGrouping.isAgent(TimelineGroupingTests.row(id: "4", sender: "@agent_x:x", at: 1, isOwn: true)))
    }

    static func body(_ text: String, isOwn: Bool = false) -> TimelineRow {
        var row = TimelineGroupingTests.row(id: "b", sender: "@a:x", at: 1, isOwn: isOwn)
        row.item.body = text
        return row
    }

    @Test("past six hundred characters a message is a long read")
    func threshold() {
        let limit = TimelineGrouping.longReadCharacters
        #expect(!TimelineGrouping.isLongRead(Self.body(String(repeating: "a", count: limit))))
        #expect(TimelineGrouping.isLongRead(Self.body(String(repeating: "a", count: limit + 1))))
    }

    @Test("characters are counted as a reader sees them, not as UTF-16")
    func countsCharacters() {
        // Each is one Character and four UTF-16 units: counted by encoding,
        // this is 2400 long and would be cut short at a quarter of the limit.
        let text = String(repeating: "👍🏽", count: TimelineGrouping.longReadCharacters)
        #expect(!TimelineGrouping.isLongRead(Self.body(text)))
    }

    @Test("your own long message is not offered back to you as a long read")
    func ownIsNot() {
        let text = String(repeating: "a", count: TimelineGrouping.longReadCharacters + 1)
        #expect(!TimelineGrouping.isLongRead(Self.body(text, isOwn: true)))
    }
}

/// What a day divider says.
struct TimelineDayTests {
    static func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    static let british = Locale(identifier: "en_GB")

    /// 2026-09-23 10:00 UTC.
    static let now = Date(timeIntervalSince1970: 1_790_157_600)

    static func ms(_ iso: String) -> UInt64 {
        UInt64(ISO8601DateFormatter().date(from: iso)!.timeIntervalSince1970 * 1000)
    }

    static func label(_ iso: String, zone: String = "UTC", now: Date = now) -> String {
        TimelineDay.label(ms(iso), now: now, calendar: calendar(zone), locale: british)
    }

    @Test("today and yesterday are words")
    func words() {
        #expect(Self.label("2026-09-23T00:05:00Z") == "Today")
        #expect(Self.label("2026-09-22T23:55:00Z") == "Yesterday")
    }

    @Test("an older day this year is a day and a month, in sentence case")
    func thisYear() {
        #expect(Self.label("2026-09-15T12:00:00Z") == "15 September")
        #expect(Self.label("2026-09-21T12:00:00Z") == "21 September")
    }

    @Test("a day in another year says which")
    func anotherYear() {
        #expect(Self.label("2025-09-15T12:00:00Z") == "15 September 2025")
    }

    @Test("today is the reader's today, not UTC's")
    func readersZone() {
        // 20:00 UTC on the 22nd is 01:30 on the 23rd in Kolkata, where it is
        // 07:30 now. One timestamp, two true answers.
        let early = Date(timeIntervalSince1970: 1_790_128_800)  // 2026-09-23 02:00 UTC
        #expect(Self.label("2026-09-22T20:00:00Z", zone: "Asia/Kolkata", now: early) == "Today")
        #expect(Self.label("2026-09-22T20:00:00Z", zone: "UTC", now: early) == "Yesterday")
    }

    @Test("no timestamp, no words")
    func missing() {
        #expect(TimelineDay.label(nil) == "")
    }
}
