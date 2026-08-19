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
            senderName: sender, membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
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
}
