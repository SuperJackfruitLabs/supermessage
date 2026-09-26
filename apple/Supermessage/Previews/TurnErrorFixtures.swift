#if DEBUG
import SupermessageFFI
import SupermessageKit
import SwiftUI

/// krishna's failed turn on 2026-09-26, as the core draws it.
///
/// Hand-written from `core::turn_error`, and pinned there: the Rust test
/// `host_fixtures::turn_error_card` parses the same payload and asserts these
/// strings, so a wording change fails on the side that makes it.
extension PreviewFixtures {
    static let turnErrorRouted =
        "Request is missing x-opencode-session and cannot be routed efficiently."

    static func turnErrorAttempt(
        _ provider: String, _ model: String, _ kind: TurnErrorKind, _ label: String,
        _ message: String, count: UInt32 = 1
    ) -> TurnErrorAttempt {
        TurnErrorAttempt(
            provider: provider, model: model, source: "\(provider) / \(model)", kind: kind,
            label: label, message: message, count: count)
    }

    static let turnError = TurnErrorCard(
        kind: .quota, label: "Usage limit reached", source: "kimi-coding / k2p6",
        headline: "Usage limit reached · kimi-coding / k2p6",
        message: "You've reached your weekly (7-day) usage limit.", harness: "openclaw",
        provider: "kimi-coding", model: "k2p6", retryable: false,
        attempts: [
            turnErrorAttempt(
                "kimi-coding", "k2p6", .quota, "Usage limit reached",
                "You've reached your weekly (7-day) usage limit."),
            turnErrorAttempt(
                "opencode-go", "hy3-preview", .badRequest, "Request rejected", turnErrorRouted),
            turnErrorAttempt(
                "opencode-go", "qwen3.7-plus", .badRequest, "Request rejected", turnErrorRouted,
                count: 4),
        ])

    /// The hub's message carrying it, as the timeline receives it.
    static var turnErrorRow: TimelineRow {
        let body = "This agent reported an error: You've reached your weekly (7-day) usage limit."
        return row(
            item(id: "$turnError", sender: "@agent_krishna:example.org", body: body),
            view: .turnError(card: turnError),
            senderName: "Krishna", senderShort: "Krishna", senderInitial: "K",
            replyPreview: body)
    }
}
#endif
