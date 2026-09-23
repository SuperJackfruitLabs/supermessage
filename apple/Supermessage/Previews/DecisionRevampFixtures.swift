#if DEBUG
import Foundation
import SupermessageFFI

/// The decision card's extra preview states: its receipts, a gate carrying a
/// handoff, and a permission request.
///
/// Beside `PreviewFixtures` rather than in it so the card's revamp could land
/// without touching the shared fixture file. The same rules hold: every value
/// here is one the core's renderers would have produced
/// (`core::custom_events::GateRenderer`, `PermissionRequestRenderer`), and
/// only the two *pending* fixtures — `gateWithHandoff` and
/// `permissionPending` — may draw amber.
enum DecisionRevampFixtures {
    /// An approval that landed a moment ago.
    static var answeredApprove: DecisionAnswerRecord {
        DecisionAnswerRecord(
            option: CustomEventDecisionOption(label: "Approve", id: "approve"), at: .now)
    }

    /// Changes requested five minutes ago — the relative time past "just now".
    static var answeredRequestChanges: DecisionAnswerRecord {
        DecisionAnswerRecord(
            option: CustomEventDecisionOption(label: "Request changes", id: "request_changes"),
            at: .now.addingTimeInterval(-5 * 60))
    }

    /// A gate the room reports as decided, with the details it was decided on.
    static var gateDecided: CustomEventView {
        .rendered(
            fields: [
                CustomEventField(label: "Card", value: "Add OAuth login"),
                CustomEventField(label: "Stage", value: "review"),
                CustomEventField(label: "Branch", value: "feat/oauth-login"),
                CustomEventField(label: "Decision", value: "Changes requested by Rakesh"),
            ],
            reasoning: nil, newerVersion: false, decision: nil, link: nil)
    }

    /// A schema-2 gate: a handoff summary as reasoning, a deep link, and more
    /// fields than belong on the card's face.
    static var gateWithHandoff: CustomEventView {
        .rendered(
            fields: [
                CustomEventField(label: "Card", value: "Add OAuth login"),
                CustomEventField(label: "Stage", value: "review"),
                CustomEventField(label: "Branch", value: "feat/oauth-login"),
                CustomEventField(label: "Changed", value: "12 files, +640 −112"),
            ],
            reasoning:
                "Implements the authorisation-code flow with PKCE, stores the refresh token "
                + "in the keychain, and adds a sign-in button to the login screen. Tests cover "
                + "the token refresh and the expired-session path.",
            newerVersion: false,
            decision: CustomEventDecision(
                prompt: "Ship \u{201C}Add OAuth login\u{201D} to the next stage?",
                options: [
                    CustomEventDecisionOption(label: "Approve", id: "approve"),
                    CustomEventDecisionOption(label: "Request changes", id: "request_changes"),
                    CustomEventDecisionOption(label: "Reject", id: "reject"),
                ],
                subject: "gate-91c4"),
            link: "https://superpipeline.example/cards/oauth-login")
    }

    /// AgentPod's permission request: the option ids are the hub's names, the
    /// prompt already says the one field, and there is no subject.
    static var permissionPending: CustomEventView {
        .rendered(
            fields: [CustomEventField(label: "Wants to", value: "restart the hermes gateway")],
            reasoning: nil, newerVersion: false,
            decision: CustomEventDecision(
                prompt: "Allow restart the hermes gateway?",
                options: [
                    CustomEventDecisionOption(label: "Allow once", id: "Allow once"),
                    CustomEventDecisionOption(label: "Allow always", id: "Allow always"),
                    CustomEventDecisionOption(label: "Reject", id: "Reject"),
                ],
                subject: nil),
            link: nil)
    }
}
#endif
