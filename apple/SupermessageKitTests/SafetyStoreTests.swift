import Foundation
import SupermessageFFI
import Testing

@testable import SupermessageKit

/// Block and report's local half (issue #60): the block list as the core sends
/// it, and the messages this device has reported and hidden.
@MainActor
struct SafetyStoreTests {
    private func row(_ id: String, eventId: String?, sender: String = "@troll:x.org") -> TimelineRow {
        TimelineRow(
            item: TimelineItemDto(
                id: id, eventId: eventId, kind: "message", msgtype: "m.text",
                detail: nil, sender: sender, senderDisplayName: nil, senderAvatar: nil,
                body: "hello", formattedBody: nil, media: nil, customPayload: nil,
                timestampMs: 1_700_000_000_000, isOwn: false, sendState: nil, replyTo: nil,
                edited: false, reactions: [], readBy: [], editable: false, membershipSubject: nil),
            view: .bubble(muted: false, blocks: [], voice: nil), senderName: "Troll", senderShort: "Troll",
            senderInitial: "T", membershipVerb: nil, replyQuote: nil, canReplyOrReact: eventId != nil,
            replyPreview: nil)
    }

    @Test("a reported message is hidden, and only that one")
    func hidesWhatWasReported() {
        let safety = SafetyStore()
        let rows = [row("a", eventId: "$a"), row("b", eventId: "$b"), row("c", eventId: "$c")]
        safety.hide(eventId: "$b")
        #expect(safety.visible(rows).map(\.item.id) == ["a", "c"])
    }

    @Test("a local echo is never hidden by a report")
    func aLocalEchoPasses() {
        // It has no event id, so it cannot have been reported — and a filter
        // keyed on `nil` would drop every unsent message at once.
        let safety = SafetyStore()
        safety.hide(eventId: "$b")
        #expect(safety.visible([row("echo", eventId: nil)]).count == 1)
    }

    @Test("the core's block list replaces what was here, including optimistic notes")
    func theCoresListWins() {
        let safety = SafetyStore()
        safety.noteBlocked("@a:x")
        safety.apply(ignored: ["@b:x"])
        #expect(safety.blocked == ["@b:x"], "an optimistic block outlived the core's answer")
        safety.noteUnblocked("@b:x")
        #expect(!safety.isBlocked("@b:x"))
    }

    @Test("hidden messages survive a relaunch, and sign-out forgets them")
    func persistsUntilSignOut() throws {
        let suite = "safety-store-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let first = SafetyStore(defaults: defaults)
        first.hide(eventId: "$b")
        first.apply(ignored: ["@troll:x.org"])

        let relaunched = SafetyStore(defaults: defaults)
        #expect(relaunched.hiddenEvents == ["$b"], "a reported message came back after a relaunch")
        // The block list is the core's to send again; it is not persisted here.
        #expect(relaunched.blocked.isEmpty)

        relaunched.clear()
        #expect(SafetyStore(defaults: defaults).hiddenEvents.isEmpty, "sign-out left hidden ids behind")
    }

    @Test("a message report offers to block its sender; a room report offers no one")
    func whoCanBeBlocked() {
        let message = ReportSubject.message(
            roomId: "!r:x", eventId: "$e", senderId: "@agent_atlas:x", senderName: "Atlas",
            isAgent: true)
        #expect(message.blockable?.userId == "@agent_atlas:x")
        #expect(message.blockable?.isAgent == true)
        #expect(ReportSubject.room(roomId: "!r:x", name: "Ops").blockable == nil)
        #expect(ReportSubject.user(userId: "@k:x", name: "K", isAgent: false).blockable?.name == "K")
    }
}
