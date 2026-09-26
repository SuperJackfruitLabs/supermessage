import SupermessageFFI
import Testing

@testable import SupermessageKit

/// How the turn error card lays out its fallback chain. The wording itself is
/// the core's (`core::turn_error`); these are the rules the web's
/// `turnErrorView.ts` shares.
struct TurnErrorPresentationTests {
    static let routed = "Request is missing x-opencode-session and cannot be routed efficiently."

    static func attempt(
        _ provider: String, _ model: String, _ kind: TurnErrorKind, _ label: String,
        _ message: String, count: UInt32 = 1
    ) -> TurnErrorAttempt {
        TurnErrorAttempt(
            provider: provider, model: model, source: "\(provider) / \(model)", kind: kind,
            label: label, message: message, count: count)
    }

    /// krishna's card, as `host_fixtures::turn_error_card` pins it.
    static let card = TurnErrorCard(
        kind: .quota, label: "Usage limit reached", source: "kimi-coding / k2p6",
        headline: "Usage limit reached · kimi-coding / k2p6",
        message: "You've reached your weekly (7-day) usage limit.", harness: "openclaw",
        provider: "kimi-coding", model: "k2p6", retryable: false,
        attempts: [
            attempt(
                "kimi-coding", "k2p6", .quota, "Usage limit reached",
                "You've reached your weekly (7-day) usage limit."),
            attempt("opencode-go", "hy3-preview", .badRequest, "Request rejected", routed),
            attempt("opencode-go", "qwen3.7-plus", .badRequest, "Request rejected", routed, count: 4),
        ])

    static func row(id: String, view: ItemView, at ms: UInt64) -> TimelineRow {
        let item = TimelineItemDto(
            id: id, eventId: id, kind: "message", msgtype: "m.text", detail: nil,
            sender: "@agent_krishna:id.agentpod.dev", senderDisplayName: "Krishna",
            senderAvatar: nil, body: "This agent reported an error: usage limit",
            formattedBody: nil, media: nil, customPayload: nil, timestampMs: ms, isOwn: false,
            sendState: nil, replyTo: nil, edited: false, reactions: [], readBy: [],
            editable: false, membershipSubject: nil)
        return TimelineRow(
            item: item, view: view, senderName: "Krishna", senderShort: "Krishna",
            senderInitial: "K", membershipVerb: nil, replyQuote: nil, canReplyOrReact: true,
            replyPreview: "This agent reported an error: usage limit")
    }

    static func with(attempts: [TurnErrorAttempt]) -> TurnErrorCard {
        var card = Self.card
        card.attempts = attempts
        return card
    }

    // The headline already says the first attempt. Listing it again under the
    // headline said the same thing twice, and "2 more attempts" beside it read
    // as if the card were hiding the cause.
    @Test("the fallbacks are the attempts after the headline's own")
    func fallbacks() {
        #expect(
            TurnErrorPresentation.fallbacks(Self.card).map(\.source) == [
                "opencode-go / hy3-preview", "opencode-go / qwen3.7-plus",
            ])
    }

    @Test("collapsed, no attempt is repeated under the headline")
    func collapsed() {
        #expect(TurnErrorPresentation.attemptsToShow(Self.card, expanded: false).isEmpty)
    }

    @Test("expanded, the fallbacks are shown in order")
    func expanded() {
        #expect(
            TurnErrorPresentation.attemptsToShow(Self.card, expanded: true).map(\.source) == [
                "opencode-go / hy3-preview", "opencode-go / qwen3.7-plus",
            ])
    }

    @Test("the disclosure says how many models the agent fell back to")
    func moreLabel() {
        #expect(TurnErrorPresentation.moreAttemptsLabel(Self.card) == "Fell back to 2 models")
        let two = Self.with(attempts: Array(Self.card.attempts.prefix(2)))
        #expect(TurnErrorPresentation.moreAttemptsLabel(two) == "Fell back to 1 model")
    }

    @Test("with no fallback there is nothing to disclose")
    func nothingToDisclose() {
        let one = Self.with(attempts: Array(Self.card.attempts.prefix(1)))
        #expect(TurnErrorPresentation.moreAttemptsLabel(one) == nil)
        #expect(TurnErrorPresentation.attemptsToShow(one, expanded: true).isEmpty)
        let none = Self.with(attempts: [])
        #expect(TurnErrorPresentation.moreAttemptsLabel(none) == nil)
        #expect(TurnErrorPresentation.repeatsLabel(none) == nil)
    }

    @Test("the headline's model retried is said once, not hidden")
    func repeats() {
        var asked = Self.card.attempts[0]
        asked.count = 3
        #expect(TurnErrorPresentation.repeatsLabel(Self.with(attempts: [asked])) == "Tried 3 times")
        #expect(TurnErrorPresentation.repeatsLabel(Self.card) == nil)
    }

    @Test("an attempt list that does not start with the headline's model is shown whole")
    func notHeadlineFirst() {
        let other = Self.with(attempts: Array(Self.card.attempts.dropFirst()))
        #expect(TurnErrorPresentation.fallbacks(other).count == 2)
        #expect(TurnErrorPresentation.repeatsLabel(other) == nil)
    }

    @Test("a folded line says how many times, a single one says nothing")
    func count() {
        #expect(TurnErrorPresentation.count(Self.card.attempts[2]) == "×4")
        #expect(TurnErrorPresentation.count(Self.card.attempts[0]) == nil)
    }

    @Test("a turn error is not an ordinary message, so it does not group into one")
    func doesNotGroup() {
        // It carries its own header — the sender and the failure — like a
        // card does, and a message after it must not read as the card's.
        let error = Self.row(id: "$1", view: .turnError(card: Self.card), at: 1_000)
        let message = Self.row(id: "$2", view: .bubble(muted: false, blocks: []), at: 2_000)
        #expect(!TimelineGrouping.continuesRun(message, after: error))
        #expect(!TimelineGrouping.continuesRun(error, after: message))
    }
}
