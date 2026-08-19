import Testing

@testable import SupermessageKit
import SupermessageFFI

@MainActor
struct DraftStoreTests {
    @Test("a draft is kept per room, so switching away does not lose work")
    func draftsAreScoped() {
        let drafts = DraftStore()
        drafts.set("half a thought", for: "!a:x")
        drafts.set("something else", for: "!b:x")

        #expect(drafts.draft(for: "!a:x") == "half a thought")
        #expect(drafts.draft(for: "!b:x") == "something else")
    }

    @Test("a draft never follows the reader into another room")
    func draftsDoNotLeak() {
        // The desktop learned this one the hard way: a draft that followed the
        // reader put a half-written message in front of the wrong agent.
        let drafts = DraftStore()
        drafts.set("for ganesha only", for: "!a:x")
        #expect(drafts.draft(for: "!b:x").isEmpty)
    }

    @Test("emptying a draft forgets it rather than storing a blank")
    func emptyIsForgotten() {
        let drafts = DraftStore()
        drafts.set("typed", for: "!a:x")
        drafts.set("", for: "!a:x")
        #expect(drafts.draft(for: "!a:x").isEmpty)
    }
}

@MainActor
struct ReplyTargetTests {
    static func row(id: String, sender: String, preview: String?) -> TimelineRow {
        let item = TimelineItemDto(
            id: id, kind: "message", msgtype: "m.text", detail: nil, sender: "@a:x",
            senderDisplayName: sender, body: "body", formattedBody: nil, media: nil,
            customPayload: nil, timestampMs: 1, isOwn: false, sendState: nil, replyTo: nil,
            edited: false, reactions: [], readBy: [])
        return TimelineRow(
            item: item, view: .bubble(muted: false, blocks: []), senderName: sender,
            membershipVerb: nil, replyQuote: nil, canReplyOrReact: true, replyPreview: preview)
    }

    @Test("a reply target takes its name and preview from the row, not from the body")
    func readsTheRow() {
        // The attribution chain and the excerpt's bounding are the core's, so
        // the composer shows exactly what the timeline showed.
        let target = ReplyTarget()
        target.start(Self.row(id: "$1", sender: "Ganesha", preview: "the original"), in: "!a:x")

        let pending = target.pending(for: "!a:x")
        #expect(pending?.eventId == "$1")
        #expect(pending?.sender == "Ganesha")
        #expect(pending?.excerpt == "the original")
    }

    @Test("a parent with nothing to preview still makes a valid target")
    func previewMayBeAbsent() {
        // A media message with no caption. Replying to it must still work.
        let target = ReplyTarget()
        target.start(Self.row(id: "$1", sender: "Ganesha", preview: nil), in: "!a:x")
        #expect(target.pending(for: "!a:x")?.eventId == "$1")
        #expect(target.pending(for: "!a:x")?.excerpt == nil)
    }

    @Test("a reply target is scoped to its room")
    func targetsAreScoped() {
        let target = ReplyTarget()
        target.start(Self.row(id: "$1", sender: "Ganesha", preview: "x"), in: "!a:x")
        #expect(target.pending(for: "!b:x") == nil)
    }
}
