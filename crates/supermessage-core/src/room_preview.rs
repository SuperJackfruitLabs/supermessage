//! The roster row's preview line.
//!
//! Ported from `$lib/components/roomPreview.ts`. It composes one line out of
//! facts the core already resolved — never a placeholder, and never a sender
//! prefix except your own.
//!
//! It moved because of one branch: the pending-decision line is the roster's
//! **amber switch**, and amber means the operator owes someone an answer and
//! nothing else. Which event types trip it is a contract shared with Superpipeline,
//! not a per-platform styling choice.
//!
//! ## When a decision is pending
//!
//! charter → `decisions/2026-11-08-waiting-cards-and-broker-approvals-reach-chat.md`
//! ("decisions light the Needs you inbox"). A room owes an answer while its
//! **newest message is a decision card this client draws** — a gate, a
//! question, a follow-up, a permission or a waiting card, in whichever form
//! the hub carries it ([`pending_decision_type`]). Every way a decision ends
//! puts a newer message after it: the reader's own answer (sent from this
//! device or another), and the hub's readable receipt line, which it posts
//! beside every structured outcome. So an answered or settled decision stops
//! counting by itself, with no per-device state to keep in sync, and a card
//! the room has moved past never keeps a row amber.
//!
//! The rule errs one way only: a decision followed by unrelated chatter stops
//! counting, though its card still waits in the room. Amber never lies about
//! a decision being owed; it can miss one that scrolled up.

use std::collections::HashSet;

use serde_json::Value;

use crate::dto::RoomSummary;

/// The event types a pending decision card is drawn as — the type
/// [`pending_decision_type`] reports for a message carrying one, and the
/// type a separate legacy card event has.
///
/// An entry here puts amber on a roster row, so each one is a decision a
/// person owes an answer to, from a versioned, documented suite schema
/// (`docs/agentpod-events.md`). A receipt, an answer or a context card (a
/// turn, a run, a station's status) is never one.
pub fn decision_bearing_event_types() -> &'static HashSet<String> {
    static TYPES: std::sync::OnceLock<HashSet<String>> = std::sync::OnceLock::new();
    TYPES.get_or_init(|| {
        [
            crate::custom_events::GATE_EVENT_TYPE,
            crate::custom_events::PERMISSION_REQUEST_EVENT_TYPE,
            crate::gate_v2::GATE_V2_EVENT_TYPE,
            crate::question_v2::QUESTION_V2_EVENT_TYPE,
            crate::delivery_v2::DELIVERY_V2_EVENT_TYPE,
            crate::permission_v2::PERMISSION_V2_EVENT_TYPE,
            crate::attention_v2::ATTENTION_V2_EVENT_TYPE,
        ]
        .into_iter()
        .map(str::to_string)
        .collect()
    })
}

/// The decision a message's `content` carries, as the event type its card
/// is drawn as, or `None` when it carries none this client would draw.
///
/// Read exactly as the timeline reads it (`crate::embedded::embedded_suite_event`),
/// so the roster and the room agree: a question, follow-up or waiting card
/// whose v2 fields do not validate, which the room shows as prose, is not
/// pending either. An answer (`*.answer.v2`, `decision.v2`) and a receipt
/// (`*.outcome.v1`) carry no decision.
///
/// A decision's reminder or escalation notice (`crate::decision_timeout`)
/// counts too, typed as the card it is about: the hub posts one only for a
/// decision that is still open, so a room whose newest message is a notice
/// still owes that answer.
pub fn pending_decision_type(content: &Value) -> Option<&'static str> {
    if let Some(event_type) = crate::decision_timeout::notice_card_type(content) {
        return Some(event_type);
    }
    let (event_type, _) = crate::embedded::embedded_suite_event(content)?;
    decision_bearing_event_types()
        .contains(event_type)
        .then_some(event_type)
}

/// The one string the pending path renders.
///
/// Fixed, not derived from the event: a gate's own prose is untrusted and
/// unbounded, and a roster line has room for neither.
const APPROVAL_NEEDED: &str = "Approval needed";

/// The prefix for a message this account sent.
const OWN_PREFIX: &str = "You: ";

/// The facts a preview is composed from.
///
/// Taken as its own struct rather than a whole room, so the mechanism can be
/// tested against a fixture without constructing a roster entry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RoomPreviewFacts<'a> {
    pub last_message: Option<&'a str>,
    pub last_message_is_own: bool,
    pub last_message_names_sender: bool,
    pub last_event_type: Option<&'a str>,
}

/// A composed preview line.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RoomPreview {
    /// Ready to render as text. Never empty.
    pub text: String,
    /// Whether this is the pending-decision line rather than a message
    /// preview — the row's amber switch.
    ///
    /// `true` only ever means "the operator owes someone an answer". It is not
    /// a severity, a warning, or an error.
    pub pending: bool,
}

/// Build the preview line, or `None` when the row must omit it entirely.
///
/// There is no placeholder string: a row with nothing to say says nothing.
///
/// `decision_types` is a parameter rather than a global read so a caller names
/// the set it is trusting, and so a test can prove the mechanism against a
/// fixture type without the production set ever gaining an entry.
///
/// **The pending check comes first, and deliberately ignores `last_message`.**
/// "Approval needed" *replaces* whatever the event's text would have been
/// rather than competing with it, so a gate can never leak its own body onto
/// the roster — and the amber cannot be suppressed by a preview that happened
/// to come back empty.
pub fn compose_room_preview(
    facts: &RoomPreviewFacts<'_>,
    decision_types: &HashSet<String>,
) -> Option<RoomPreview> {
    if let Some(event_type) = facts.last_event_type {
        if decision_types.contains(event_type) {
            return Some(RoomPreview {
                text: APPROVAL_NEEDED.to_string(),
                pending: true,
            });
        }
    }

    // The core already collapses whitespace and returns `None` rather than an
    // empty string, so this trim is defence in depth rather than a second
    // opinion: it is what stops a blank preview rendering as a bare "You: ",
    // a line that would look like a bug and say nothing.
    let message = facts.last_message?;
    if message.trim().is_empty() {
        return None;
    }

    let prefix = if facts.last_message_is_own && !facts.last_message_names_sender {
        OWN_PREFIX
    } else {
        ""
    };
    Some(RoomPreview {
        text: format!("{prefix}{message}"),
        pending: false,
    })
}

/// The preview for a room, against the production decision set.
pub fn room_preview(room: &RoomSummary) -> Option<RoomPreview> {
    compose_room_preview(
        &RoomPreviewFacts {
            last_message: room.last_message.as_deref(),
            last_message_is_own: room.last_message_is_own,
            last_message_names_sender: room.last_message_names_sender,
            last_event_type: room.last_event_type.as_deref(),
        },
        decision_bearing_event_types(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts<'a>(last_message: Option<&'a str>) -> RoomPreviewFacts<'a> {
        RoomPreviewFacts {
            last_message,
            last_message_is_own: false,
            last_message_names_sender: false,
            last_event_type: None,
        }
    }

    fn none() -> HashSet<String> {
        HashSet::new()
    }

    fn gates() -> HashSet<String> {
        HashSet::from(["org.example.gate.v1".to_string()])
    }

    #[test]
    fn shows_the_last_message() {
        assert_eq!(
            compose_room_preview(&facts(Some("hello there")), &none()),
            Some(RoomPreview {
                text: "hello there".into(),
                pending: false
            })
        );
    }

    #[test]
    fn prefixes_your_own_message() {
        let mut f = facts(Some("on it"));
        f.last_message_is_own = true;
        assert_eq!(
            compose_room_preview(&f, &none()).expect("a preview").text,
            "You: on it"
        );
    }

    #[test]
    fn does_not_double_name_a_sender_the_preview_already_names() {
        // An own emote already reads as a sentence about its sender, so the
        // prefix would render "You: <MyName> waves".
        let mut f = facts(Some("Rakesh waves"));
        f.last_message_is_own = true;
        f.last_message_names_sender = true;
        assert_eq!(
            compose_room_preview(&f, &none()).expect("a preview").text,
            "Rakesh waves"
        );
    }

    #[test]
    fn omits_the_line_entirely_when_there_is_nothing_to_show() {
        // No placeholder string: a row with nothing to say says nothing.
        assert_eq!(compose_room_preview(&facts(None), &none()), None);
        assert_eq!(compose_room_preview(&facts(Some("")), &none()), None);
        assert_eq!(compose_room_preview(&facts(Some("   ")), &none()), None);
    }

    #[test]
    fn a_blank_own_message_does_not_render_as_a_bare_prefix() {
        // The failure this guards is specific: "You: " alone, which looks like
        // a bug and says nothing.
        let mut f = facts(Some("   "));
        f.last_message_is_own = true;
        assert_eq!(compose_room_preview(&f, &none()), None);
    }

    #[test]
    fn a_decision_bearing_event_type_turns_the_row_amber() {
        let mut f = facts(Some("some gate prose"));
        f.last_event_type = Some("org.example.gate.v1");
        assert_eq!(
            compose_room_preview(&f, &gates()),
            Some(RoomPreview {
                text: "Approval needed".into(),
                pending: true
            })
        );
    }

    #[test]
    fn the_pending_line_replaces_the_events_own_text_rather_than_competing() {
        // A gate's prose is untrusted and unbounded; it must never reach the
        // roster.
        let mut f = facts(Some("PLEASE APPROVE ME urgently, click here"));
        f.last_event_type = Some("org.example.gate.v1");
        let preview = compose_room_preview(&f, &gates()).expect("a preview");
        assert_eq!(preview.text, "Approval needed");
        assert!(!preview.text.contains("urgently"));
    }

    #[test]
    fn the_amber_survives_a_preview_that_came_back_empty() {
        // The check comes first for exactly this: a decision must not be
        // suppressed by a message preview that happened to be blank.
        let mut f = facts(None);
        f.last_event_type = Some("org.example.gate.v1");
        assert!(
            compose_room_preview(&f, &gates())
                .expect("a preview")
                .pending
        );
    }

    #[test]
    fn an_ordinary_event_type_is_not_a_decision() {
        let mut f = facts(Some("hello"));
        f.last_event_type = Some("m.room.message");
        let preview = compose_room_preview(&f, &gates()).expect("a preview");
        assert!(!preview.pending);
        assert_eq!(preview.text, "hello");
    }

    // ---- Needs you: real decisions light it ---------------------------------

    use serde_json::json;

    const CORPUS: &str =
        include_str!("../../../fixtures/ecosystem-identity/matrix_gate_events.json");

    /// The first `accept` content the corpus pins for `suite_type`.
    fn corpus_accept(suite_type: &str) -> Value {
        let corpus: Value = serde_json::from_str(CORPUS).expect("the corpus is JSON");
        corpus["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type)
            .unwrap_or_else(|| panic!("the corpus pins {suite_type}"))["accept"][0]["content"]
            .clone()
    }

    /// The roster row for a room whose newest message has `content`, as
    /// `rooms::room_preview` composes it: the decision type only for a
    /// message someone else sent.
    fn row_for(content: &Value, is_own: bool) -> Option<RoomPreview> {
        let decision = if is_own {
            None
        } else {
            pending_decision_type(content)
        };
        let f = RoomPreviewFacts {
            last_message: content.get("body").and_then(Value::as_str),
            last_message_is_own: is_own,
            last_message_names_sender: false,
            last_event_type: decision,
        };
        compose_room_preview(&f, decision_bearing_event_types())
    }

    fn lit(content: &Value, is_own: bool) -> bool {
        row_for(content, is_own).is_some_and(|p| p.pending)
    }

    #[test]
    fn every_pending_decision_card_lights_needs_you() {
        for suite_type in [
            crate::gate_v2::GATE_V2_EVENT_TYPE,
            crate::question_v2::QUESTION_V2_EVENT_TYPE,
            crate::delivery_v2::DELIVERY_V2_EVENT_TYPE,
            crate::permission_v2::PERMISSION_V2_EVENT_TYPE,
            crate::attention_v2::ATTENTION_V2_EVENT_TYPE,
        ] {
            let content = corpus_accept(suite_type);
            assert_eq!(pending_decision_type(&content), Some(suite_type));
            let preview = row_for(&content, false).expect("a preview");
            assert!(preview.pending, "{suite_type}");
            // The card's own prose never reaches the roster.
            assert_eq!(preview.text, "Approval needed", "{suite_type}");
        }
    }

    #[test]
    fn a_v1_card_carried_on_its_message_lights_needs_you_too() {
        let permission = json!({
            "msgtype": "m.text",
            "body": "Permission needed: Run the tests",
            "dev.agentpod.permission": {
                "schema_version": 1,
                "session_id": "s1",
                "request_seq": 3,
                "title": "Run the tests",
                "options": [{ "option_id": "allow_once", "name": "Allow once" }]
            }
        });
        let permission_type = crate::custom_events::PERMISSION_REQUEST_EVENT_TYPE;
        assert_eq!(pending_decision_type(&permission), Some(permission_type));
        assert!(lit(&permission, false));
        let gate = json!({
            "msgtype": "m.text",
            "body": "Approve \"Ship\"?",
            "dev.superpipeline.gate": { "schema_version": 1, "gate_id": "g", "card_title": "Ship" }
        });
        assert!(lit(&gate, false));
    }

    #[test]
    fn a_decision_closed_by_its_outcome_no_longer_lights_needs_you() {
        // The hub's readable receipt line, posted beside the structured
        // outcome, is the room's newest message once a decision closes.
        let receipt = json!({
            "msgtype": "m.text",
            "body": "Resumed by fixture-person — the board has it."
        });
        assert!(!lit(&receipt, false));
        // And a structured outcome carried on a message closes nothing open.
        for suite_type in [
            crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            crate::permission_v2::PERMISSION_OUTCOME_EVENT_TYPE,
            crate::attention_v2::ATTENTION_OUTCOME_EVENT_TYPE,
        ] {
            let outcome = corpus_accept(suite_type);
            assert_eq!(pending_decision_type(&outcome), None, "{suite_type}");
            assert!(!lit(&outcome, false), "{suite_type}");
        }
    }

    #[test]
    fn a_decision_answered_on_this_device_no_longer_lights_needs_you() {
        // The answer this device sent is the room's newest message.
        for suite_type in [
            crate::gate_v2::GATE_DECISION_V2_SUITE_TYPE,
            crate::question_v2::QUESTION_ANSWER_V2_SUITE_TYPE,
            crate::delivery_v2::DELIVERY_ANSWER_V2_SUITE_TYPE,
            crate::permission_v2::PERMISSION_ANSWER_V2_SUITE_TYPE,
            crate::attention_v2::ATTENTION_ANSWER_V2_SUITE_TYPE,
        ] {
            let answer = corpus_accept(suite_type);
            assert_eq!(pending_decision_type(&answer), None, "{suite_type}");
            assert!(!lit(&answer, true), "{suite_type}");
            // Nor does an answer sent from another of the reader's devices,
            // or by someone else in the room, light it.
            assert!(!lit(&answer, false), "{suite_type}");
        }
    }

    #[test]
    fn a_card_whose_fields_do_not_validate_is_prose_and_not_pending() {
        let mut card = corpus_accept(crate::attention_v2::ATTENTION_V2_EVENT_TYPE);
        card.as_object_mut().unwrap().remove("wait_id");
        assert_eq!(pending_decision_type(&card), None);
        assert!(!lit(&card, false));
        let chat = json!({ "msgtype": "m.text", "body": "hello" });
        assert!(!lit(&chat, false));
    }

    #[test]
    fn a_reminder_or_an_escalation_keeps_needs_you_lit() {
        // The hub posts one only while its decision is open, so the room
        // still owes the answer. Typed as the card it is about (the corpus
        // notices are about a gate); the notice type itself is not a
        // decision-bearing type.
        for suite_type in [
            crate::decision_timeout::DECISION_REMINDED_EVENT_TYPE,
            crate::decision_timeout::DECISION_ESCALATED_EVENT_TYPE,
        ] {
            let notice = corpus_accept(suite_type);
            let gate = crate::gate_v2::GATE_V2_EVENT_TYPE;
            assert_eq!(pending_decision_type(&notice), Some(gate), "{suite_type}");
            let preview = row_for(&notice, false).expect("a preview");
            assert!(preview.pending, "{suite_type}");
            assert_eq!(preview.text, "Approval needed", "{suite_type}");
            assert!(!decision_bearing_event_types().contains(suite_type));
        }
    }

    #[test]
    fn a_notice_is_typed_as_the_kind_it_names() {
        let mut notice = corpus_accept(crate::decision_timeout::DECISION_REMINDED_EVENT_TYPE);
        for (kind, card) in [
            ("question", crate::question_v2::QUESTION_V2_EVENT_TYPE),
            ("delivery", crate::delivery_v2::DELIVERY_V2_EVENT_TYPE),
            ("attention", crate::attention_v2::ATTENTION_V2_EVENT_TYPE),
        ] {
            notice["decision_kind"] = json!(kind);
            assert_eq!(pending_decision_type(&notice), Some(card), "{kind}");
        }
    }

    #[test]
    fn a_notice_the_corpus_refuses_keeps_nothing_lit() {
        // "no m.relates_to": not a notice, just a sentence.
        let mut notice = corpus_accept(crate::decision_timeout::DECISION_ESCALATED_EVENT_TYPE);
        notice.as_object_mut().unwrap().remove("m.relates_to");
        assert_eq!(pending_decision_type(&notice), None);
        assert!(!lit(&notice, false));
        // And this account's own copy of one never lights it.
        let notice = corpus_accept(crate::decision_timeout::DECISION_REMINDED_EVENT_TYPE);
        assert!(!lit(&notice, true));
    }

    #[test]
    fn a_card_carrying_a_deadline_still_lights_needs_you() {
        let corpus: Value = serde_json::from_str(CORPUS).unwrap();
        let card = corpus["events"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|e| e["variant"] == "deadline")
            .find(|e| e["suiteEventType"] == crate::gate_v2::GATE_V2_EVENT_TYPE)
            .unwrap()["accept"][0]["content"]
            .clone();
        assert!(lit(&card, false));
    }

    #[test]
    fn the_production_decision_set_holds_decisions_only() {
        let types = decision_bearing_event_types();
        assert_eq!(types.len(), 7);
        for not_a_decision in [
            crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            crate::permission_v2::PERMISSION_OUTCOME_EVENT_TYPE,
            crate::attention_v2::ATTENTION_OUTCOME_EVENT_TYPE,
            crate::custom_events::TURN_ACTIVITY_EVENT_TYPE,
            "m.room.message",
        ] {
            assert!(!types.contains(not_a_decision), "{not_a_decision}");
        }
    }
}
