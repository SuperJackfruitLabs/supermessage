//! The first-run demo: a sample agent, Atlas, asks a permission and the
//! reader approves it.
//!
//! **Local, and nothing is sent.** This is not a Matrix room and holds no
//! session: the script is data, and the host holds the step. A host must hand
//! the card a local answer closure and never a session, so there is no client
//! anywhere in the demo to send through. (The iOS original,
//! `FirstRunDemo.swift`, is built the same way — its card's `onDecide` only
//! advances the local step.)
//!
//! Ported from `apple/SupermessageKit/FirstRunDemo.swift` and
//! `apple/Supermessage/Onboarding/FirstRunDemoView.swift`; every string and
//! delay is copied from there.

use crate::custom_events::{
    CustomEventDecision, CustomEventDecisionOption, CustomEventField, CustomEventView,
};

/// Where the demo conversation is. Order matters: each step follows the last.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum FirstRunStep {
    /// Atlas is typing its first message.
    Greeting,
    /// The message has landed; the permission request is on its way.
    Asking,
    /// The permission card is on screen, waiting on the reader.
    Waiting,
    /// The reader answered; the receipt is showing.
    Approved,
}

/// Everything the demo says and shows, so no host keeps its own copy.
///
/// `Serialize` only: [`CustomEventView`] has no `Deserialize`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct FirstRunScript {
    pub agent_name: String,
    pub greeting: String,
    pub request: String,
    /// Shown above the conversation so nobody mistakes it for a real room.
    pub banner: String,
    pub typing_label: String,
    pub agent_tag: String,
    pub card_label: String,
    pub card_event_type: String,
    pub room_id: String,
    pub initial: String,
    pub approved_title: String,
    pub approved_body: String,
    pub finish_label: String,
    pub skip_label: String,
    /// The permission request Atlas "sends", as the core would hand it to a host.
    pub card: CustomEventView,
    /// Pause before each automatic [`advance`]: greeting to asking, then
    /// asking to waiting.
    pub step_delays_ms: Vec<u64>,
}

/// The demo script, verbatim from the iOS first-run demo.
pub fn script() -> FirstRunScript {
    let request = "Can I edit docs/release-notes.md?".to_string();
    FirstRunScript {
        agent_name: "Atlas".into(),
        greeting:
            "Hi — I'm Atlas, a sample agent. I'd like to tidy the release notes before today's build."
                .into(),
        request: request.clone(),
        banner: "A sample conversation — nothing here is sent anywhere.".into(),
        typing_label: "Atlas is typing".into(),
        agent_tag: "Agent".into(),
        card_label: "Permission".into(),
        card_event_type: "dev.agentpod.permission.request.v1".into(),
        room_id: "demo.atlas".into(),
        initial: "✳".into(),
        approved_title: "That's it — approvals from chat.".into(),
        approved_body:
            "When an agent needs you, it asks in its room, and the Needs you tab keeps the list."
                .into(),
        finish_label: "Get started".into(),
        skip_label: "Skip".into(),
        card: CustomEventView::Rendered {
            fields: vec![
                CustomEventField {
                    label: "File".into(),
                    value: "docs/release-notes.md".into(),
                },
                CustomEventField {
                    label: "Change".into(),
                    value: "Tidy headings, add today's fixes".into(),
                },
            ],
            reasoning: None,
            newer_version: false,
            decision: Some(CustomEventDecision {
                prompt: request,
                options: vec![
                    CustomEventDecisionOption {
                        label: "Approve".into(),
                        id: "approve".into(),
                    },
                    CustomEventDecisionOption {
                        label: "Reject".into(),
                        id: "reject".into(),
                    },
                ],
                subject: Some("demo-permission".into()),
            }),
            link: None,
            outcome: None,
        },
        step_delays_ms: vec![1100, 900],
    }
}

/// Move the conversation along one step, stopping at the card: only the
/// reader's [`answer`] gets past `Waiting`.
pub fn advance(step: FirstRunStep) -> FirstRunStep {
    match step {
        FirstRunStep::Greeting => FirstRunStep::Asking,
        FirstRunStep::Asking => FirstRunStep::Waiting,
        FirstRunStep::Waiting => FirstRunStep::Waiting,
        FirstRunStep::Approved => FirstRunStep::Approved,
    }
}

/// The step after the reader answers the card, or `None` when there is no
/// card to answer yet or it was already answered.
pub fn answer(step: FirstRunStep) -> Option<FirstRunStep> {
    (step == FirstRunStep::Waiting).then_some(FirstRunStep::Approved)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Ported from `RosterViewsTests.swift:207`
    /// (`theDemoOnlyGetsPastTheCardOnTheReadersAnswer`).
    ///
    /// Mutation seen failing: `advance(Waiting)` returning `Approved` failed
    /// the `Waiting` assertion.
    #[test]
    fn the_demo_only_gets_past_the_card_on_the_readers_answer() {
        use FirstRunStep::*;
        assert_eq!(answer(Greeting), None);
        assert_eq!(advance(Greeting), Asking);
        assert_eq!(advance(Asking), Waiting);
        assert_eq!(advance(Waiting), Waiting);
        assert_eq!(answer(Waiting), Some(Approved));
        assert_eq!(answer(Approved), None);
        assert_eq!(advance(Approved), Approved);
    }

    /// Every string, timing and ordering value the iOS demo shows.
    ///
    /// Mutation seen failing: swapping the delays to `[900, 1100]` and
    /// changing the banner's final full stop each failed this test.
    #[test]
    fn the_script_is_what_ios_says() {
        let s = script();
        assert_eq!(s.agent_name, "Atlas");
        assert_eq!(
            s.greeting,
            "Hi — I'm Atlas, a sample agent. I'd like to tidy the release notes before today's build."
        );
        assert_eq!(s.request, "Can I edit docs/release-notes.md?");
        assert_eq!(
            s.banner,
            "A sample conversation — nothing here is sent anywhere."
        );
        assert_eq!(s.typing_label, "Atlas is typing");
        assert_eq!(s.agent_tag, "Agent");
        assert_eq!(s.card_label, "Permission");
        assert_eq!(s.card_event_type, "dev.agentpod.permission.request.v1");
        assert_eq!(s.room_id, "demo.atlas");
        assert_eq!(s.initial, "✳");
        assert_eq!(s.approved_title, "That's it — approvals from chat.");
        assert_eq!(
            s.approved_body,
            "When an agent needs you, it asks in its room, and the Needs you tab keeps the list."
        );
        assert_eq!(s.finish_label, "Get started");
        assert_eq!(s.skip_label, "Skip");
        assert_eq!(s.step_delays_ms, vec![1100, 900]);
        assert_eq!(
            s.card,
            CustomEventView::Rendered {
                fields: vec![
                    CustomEventField {
                        label: "File".into(),
                        value: "docs/release-notes.md".into()
                    },
                    CustomEventField {
                        label: "Change".into(),
                        value: "Tidy headings, add today's fixes".into()
                    },
                ],
                reasoning: None,
                newer_version: false,
                decision: Some(CustomEventDecision {
                    prompt: "Can I edit docs/release-notes.md?".into(),
                    options: vec![
                        CustomEventDecisionOption {
                            label: "Approve".into(),
                            id: "approve".into()
                        },
                        CustomEventDecisionOption {
                            label: "Reject".into(),
                            id: "reject".into()
                        },
                    ],
                    subject: Some("demo-permission".into()),
                }),
                link: None,
                outcome: None,
            }
        );
    }

    /// Hosts get the step as a lowercase word and the script as camelCase.
    ///
    /// Mutation seen failing: removing `rename_all` on the step enum failed
    /// the step assertion.
    #[test]
    fn the_wire_shape_is_camel_case() {
        assert_eq!(
            serde_json::to_string(&FirstRunStep::Waiting).unwrap(),
            "\"waiting\""
        );
        let v = serde_json::to_value(script()).unwrap();
        assert_eq!(v["agentName"], "Atlas");
        assert_eq!(v["stepDelaysMs"], serde_json::json!([1100, 900]));
        assert_eq!(v["card"]["status"], "rendered");
    }
}
