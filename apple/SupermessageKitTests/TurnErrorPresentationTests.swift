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

    @Test("collapsed, only the model that was asked for is shown")
    func collapsed() {
        let shown = TurnErrorPresentation.attemptsToShow(Self.card, expanded: false)
        #expect(shown.map(\.source) == ["kimi-coding / k2p6"])
    }

    @Test("expanded, the whole chain is shown in order")
    func expanded() {
        let shown = TurnErrorPresentation.attemptsToShow(Self.card, expanded: true)
        #expect(
            shown.map(\.source) == [
                "kimi-coding / k2p6", "opencode-go / hy3-preview", "opencode-go / qwen3.7-plus",
            ])
    }

    @Test("the disclosure counts the lines it hides")
    func moreLabel() {
        #expect(TurnErrorPresentation.moreAttemptsLabel(Self.card) == "2 more attempts")
        let two = Self.with(attempts: Array(Self.card.attempts.prefix(2)))
        #expect(TurnErrorPresentation.moreAttemptsLabel(two) == "1 more attempt")
    }

    @Test("with one attempt or none there is nothing to disclose")
    func nothingToDisclose() {
        let one = Self.with(attempts: Array(Self.card.attempts.prefix(1)))
        #expect(TurnErrorPresentation.moreAttemptsLabel(one) == nil)
        #expect(TurnErrorPresentation.attemptsToShow(one, expanded: false).count == 1)
        let none = Self.with(attempts: [])
        #expect(TurnErrorPresentation.moreAttemptsLabel(none) == nil)
        #expect(TurnErrorPresentation.attemptsToShow(none, expanded: true).isEmpty)
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
