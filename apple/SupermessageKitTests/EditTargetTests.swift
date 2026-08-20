import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

@MainActor
struct EditTargetTests {
    private func row(editable: Bool, eventId: String? = "$e1:x.org", body: String? = "hello")
        -> TimelineRow
    {
        TimelineRow(
            item: TimelineItemDto(
                id: "unique-1", eventId: eventId, kind: "message", msgtype: "m.text",
                detail: nil, sender: "@me:x.org", senderDisplayName: "Me", senderAvatar: nil,
                body: body, formattedBody: nil, media: nil, customPayload: nil,
                timestampMs: 1_700_000_000_000, isOwn: true, sendState: nil, replyTo: nil,
                edited: false, reactions: [], readBy: [], editable: editable),
            view: .bubble(muted: false, blocks: []), senderName: "Me", senderShort: "Me",
            membershipVerb: nil, replyQuote: nil, canReplyOrReact: eventId != nil,
            replyPreview: nil)
    }

    @Test("an edit starts from what the message actually says")
    func seedsTheComposer() {
        let edits = EditTarget()
        #expect(edits.start(row(editable: true), in: "!r:x") == "hello")
        #expect(edits.pending(for: "!r:x")?.eventId == "$e1:x.org")
    }

    @Test("a message the SDK will not let this account rewrite starts nothing")
    func refusesTheUneditable() {
        // Not `isOwn`: "mine" and "editable" are different sets, and offering
        // an Edit the homeserver then refuses is worse than not offering it.
        let edits = EditTarget()
        #expect(edits.start(row(editable: false), in: "!r:x") == nil)
        #expect(edits.pending(for: "!r:x") == nil, "an edit was staged against an uneditable row")
    }

    @Test("a message the homeserver has not acknowledged has no address to edit")
    func refusesALocalEcho() {
        let edits = EditTarget()
        #expect(edits.start(row(editable: true, eventId: nil), in: "!r:x") == nil)
        #expect(edits.pending(for: "!r:x") == nil)
    }

    @Test("edits are kept per room, and cancelling one leaves the other")
    func perRoom() {
        let edits = EditTarget()
        edits.start(row(editable: true), in: "!a:x")
        edits.start(row(editable: true), in: "!b:x")
        edits.cancel("!a:x")
        #expect(edits.pending(for: "!a:x") == nil)
        #expect(edits.pending(for: "!b:x") != nil, "cancelling one room's edit cleared another's")
    }
}
