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
            detail: nil, sender: sender, senderDisplayName: nil, body: "hi", formattedBody: nil,
            media: nil, customPayload: nil, timestampMs: ms, isOwn: isOwn, sendState: nil,
            replyTo: nil, edited: false, reactions: [], readBy: [])
        return TimelineRow(
            item: item,
            view: system ? .system(text: "something happened") : .bubble(muted: false, blocks: []),
            senderName: sender, senderShort: sender, membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
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
            sender: sender, senderDisplayName: sender, body: nil, formattedBody: nil,
            media: nil, customPayload: nil, timestampMs: 1, isOwn: false, sendState: nil,
            replyTo: nil, edited: false, reactions: [], readBy: [])
        row = TimelineRow(
            item: item, view: .system(text: "\(sender) \(verb)"), senderName: sender,
            senderShort: sender, membershipVerb: verb, replyQuote: nil,
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
