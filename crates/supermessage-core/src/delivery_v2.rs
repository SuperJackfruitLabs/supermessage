//! delivery.v2 — the follow-up a post approved for manual delivery gets.
//!
//! charter → `decisions/2026-11-07-the-envelope-covers-questions-and-follow-ups.md`.
//! When a subject-bound gate resolves `approve_manual` ("I'll post it
//! myself"), the hub posts a follow-up into the gate's room: an
//! `m.room.message` marked `suite_event_type: "dev.superpipeline.delivery.v2"`
//! naming the gate and its subject, with the subject's preview and two
//! options:
//!
//! - `record_live_url` — meaning `defer`, with a required `url` input: where
//!   the post went live;
//! - `switch_to_automatic` — meaning `approve`, `delivery: "automatic"`: let
//!   the board post it after all. Offered only while the preview was
//!   complete, the rule gate.v2 set for every approving option — and applied
//!   here again, so a follow-up whose preview this client could not draw in
//!   full never offers it.
//!
//! There is no v1 form: a follow-up without valid v2 fields stays the prose
//! it is. The answer is `dev.superpipeline.delivery.answer.v2`
//! ([`answer_request`]); the follow-up is closed by the hub's
//! `dev.superpipeline.gate.outcome.v1` referencing it (`crate::gate_outcome`).
//!
//! The card is the same shape as a gate.v2's (`crate::gate_v2::GateV2Decision`)
//! so a host draws a follow-up's preview and options with what it already has.

use serde_json::Value;

use crate::custom_events::{
    safe_link, safe_string_field, CustomEventDecision, CustomEventField, CustomEventRenderResult,
    CustomEventRenderer,
};
use crate::error::{CoreError, CoreResult};
use crate::gate_v2::{
    checked_input, id_field, parse_option_list, parse_subject, GateOptionMeaning, GateOptionV2,
    GateV2Decision, OptionInputKind, Subject,
};

/// The `suite_event_type` marker on a delivery.v2 prose message, and the
/// event type its card is registered under.
pub const DELIVERY_V2_EVENT_TYPE: &str = "dev.superpipeline.delivery.v2";

/// The `suite_event_type` of the answer to a delivery.v2.
pub const DELIVERY_ANSWER_V2_SUITE_TYPE: &str = "dev.superpipeline.delivery.answer.v2";

/// Record where the post went live.
pub const RECORD_LIVE_URL: &str = "record_live_url";

/// Let the board post it automatically after all.
pub const SWITCH_TO_AUTOMATIC: &str = "switch_to_automatic";

/// What the card says when it withholds `switch_to_automatic`. Worded here
/// so no host words it differently.
pub const SWITCH_WITHHELD_NOTE: &str =
    "This card can't show everything that would be posted. Open the card to switch to automatic posting.";

const FIELD_VALUE_MAX_CHARS: usize = 300;
const FIELD_LABEL_MAX_CHARS: usize = 60;

/// A delivery.v2 that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedDeliveryV2 {
    gate_id: String,
    card_title: String,
    stage_key: Option<String>,
    deep_link: Option<String>,
    options: Vec<GateOptionV2>,
    subject: Option<Subject>,
}

/// Whether `options` are a follow-up's: `record_live_url`, present, with a
/// required `url` input; `switch_to_automatic` optional and meaning
/// `approve`; nothing else, because each maps to one call on the board's
/// delivery route and an option it has no call for is a button that does
/// nothing.
fn follow_up_options(options: &[GateOptionV2]) -> bool {
    let record = options.iter().find(|o| o.id == RECORD_LIVE_URL);
    let record_ok = record.is_some_and(|o| {
        o.input
            .as_ref()
            .is_some_and(|i| i.kind == OptionInputKind::Url && i.required)
    });
    let rest_ok = options.iter().all(|o| match o.id.as_str() {
        RECORD_LIVE_URL => true,
        SWITCH_TO_AUTOMATIC => o.meaning == GateOptionMeaning::Approve && o.input.is_none(),
        _ => false,
    });
    record_ok && rest_ok
}

/// Read a delivery.v2 from a message's `content`, or `None` when it is not
/// one this client may draw — in which case the message stays prose.
///
/// Refused, as the corpus refuses them: no body, `record_live_url` with no
/// input or a `text` one, an input spelled `maxLength`, an option the board
/// has no call for, and a subject with no id or digest. A
/// `switch_to_automatic` on an incomplete preview is not refused but
/// withheld ([`ParsedDeliveryV2::decision`]), so the live link can still be
/// recorded.
pub(crate) fn parse(content: &Value, body: Option<&str>) -> Option<ParsedDeliveryV2> {
    if content.get("suite_event_type")?.as_str()? != DELIVERY_V2_EVENT_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let gate_id = id_field(content, "gate_id")?;
    let card_title = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)?;
    let options = parse_option_list(content.get("options")?.as_array()?, true)?;
    if !follow_up_options(&options) {
        return None;
    }
    let subject = parse_subject(content).ok()?;
    Some(ParsedDeliveryV2 {
        gate_id,
        card_title,
        stage_key: safe_string_field(content, "stage_key", FIELD_LABEL_MAX_CHARS),
        deep_link: safe_link(content, "deep_link"),
        options,
        subject,
    })
}

/// The payload a delivery.v2 message rides on its timeline row, or `None`
/// when the message is not a valid delivery.v2 or too large to carry one.
pub fn embedded_payload(content: &Value) -> Option<Value> {
    let body = content.get("body").and_then(Value::as_str);
    parse(content, body)?;
    let mut payload = content.as_object()?.clone();
    payload.remove("body");
    let payload = Value::Object(payload);
    let size = serde_json::to_string(&payload)
        .map(|s| s.len())
        .unwrap_or(usize::MAX);
    (size <= crate::gate_v2::GATE_V2_PAYLOAD_MAX_BYTES).then_some(payload)
}

impl ParsedDeliveryV2 {
    /// Whether this client may offer `switch_to_automatic`: only when it can
    /// draw the whole of what would be posted. A follow-up with no subject
    /// has no preview to show, so never.
    fn approvable(&self) -> bool {
        self.subject.as_ref().is_some_and(|s| s.complete)
    }

    fn decision(&self) -> GateV2Decision {
        let approvable = self.approvable();
        let options: Vec<GateOptionV2> = self
            .options
            .iter()
            .filter(|o| approvable || o.meaning != GateOptionMeaning::Approve)
            .cloned()
            .collect();
        let withheld = !approvable
            && self
                .options
                .iter()
                .any(|o| o.meaning == GateOptionMeaning::Approve);
        GateV2Decision {
            gate_id: self.gate_id.clone(),
            options,
            preview: self
                .subject
                .as_ref()
                .map(|s| s.preview.clone())
                .unwrap_or_default(),
            approvals_withheld: withheld.then(|| SWITCH_WITHHELD_NOTE.to_string()),
            subject: self.subject.as_ref().map(|s| s.binding.clone()),
            variants: Vec::new(),
        }
    }
}

/// Draws a delivery.v2 — registered under [`DELIVERY_V2_EVENT_TYPE`].
pub struct DeliveryV2Renderer;

impl CustomEventRenderer for DeliveryV2Renderer {
    fn event_type(&self) -> &str {
        DELIVERY_V2_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Delivery"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(delivery) = parse(content, body) else {
            return CustomEventRenderResult::default();
        };
        let mut fields = vec![CustomEventField {
            label: "Card".into(),
            value: delivery.card_title.clone(),
        }];
        if let Some(stage) = &delivery.stage_key {
            fields.push(CustomEventField {
                label: "Stage".into(),
                value: stage.clone(),
            });
        }
        CustomEventRenderResult {
            fields,
            reasoning: None,
            link: delivery.deep_link.clone(),
            decision: Some(CustomEventDecision {
                prompt: format!("Where did \u{201C}{}\u{201D} go live?", delivery.card_title),
                // No plain reply answers a follow-up.
                options: Vec::new(),
                subject: None,
                gate_v2: None,
                question_v2: None,
                delivery_v2: Some(delivery.decision()),
                permission_v2: None,
                attention_v2: None,
                // The stage's timeout (`crate::decision_timeout`).
                deadline: crate::decision_timeout::parse_deadline(content),
            }),
        }
    }
}

/// The sentence an answer leaves in the room.
pub(crate) fn answer_body(option: &GateOptionV2, text: Option<&str>) -> String {
    match (option.id.as_str(), text) {
        (RECORD_LIVE_URL, Some(url)) => format!("Live at {url}"),
        (_, Some(text)) => format!("{}: {text}", option.label.trim()),
        (_, None) => option.label.trim().to_string(),
    }
}

/// A delivery.answer.v2's `content`, validated against the follow-up the
/// reader saw — the whole of what goes on the wire.
///
/// Refuses, with a reason a host can show: an option the follow-up did not
/// offer (or withheld), `switch_to_automatic` when it was withheld, the live
/// link left empty, a link that is not `https://` or is over its limit, and a
/// reference that is not an event id.
pub(crate) fn answer_request(
    delivery: &GateV2Decision,
    option_id: &str,
    text: Option<&str>,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if delivery.gate_id.is_empty() {
        return Err(CoreError::Protocol("an answer needs a gate".into()));
    }
    let option = delivery
        .options
        .iter()
        .find(|o| o.id == option_id)
        .ok_or_else(|| {
            CoreError::Protocol(format!("not an option this follow-up offers: {option_id}"))
        })?;
    if option.meaning == GateOptionMeaning::Approve && delivery.approvals_withheld.is_some() {
        return Err(CoreError::Protocol(
            "this card could not show everything that would be posted".into(),
        ));
    }
    let text = checked_input(option, text)?;
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    let mut content = serde_json::json!({
        "msgtype": "m.text",
        "body": answer_body(option, text.as_deref()),
        "schema_version": 1,
        "suite_event_type": DELIVERY_ANSWER_V2_SUITE_TYPE,
        "gate_id": delivery.gate_id,
        "option_id": option.id,
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    });
    if let Some(text) = text {
        content["text"] = Value::String(text);
    }
    Ok(content)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use crate::gate_v2::GatePreviewBlock;
    use serde_json::json;

    const CORPUS: &str =
        include_str!("../../../fixtures/ecosystem-identity/matrix_gate_events.json");

    fn cases(suite_type: &str, which: &str) -> Vec<(String, Value)> {
        let corpus: Value = serde_json::from_str(CORPUS).expect("the corpus is JSON");
        corpus["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type)
            .unwrap_or_else(|| panic!("the corpus pins {suite_type}"))[which]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| (c["why"].as_str().unwrap().to_string(), c["content"].clone()))
            .collect()
    }

    /// A complete preview: both choices.
    fn complete() -> Value {
        cases(DELIVERY_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    /// An incomplete preview: recording the live link only.
    fn incomplete() -> Value {
        cases(DELIVERY_V2_EVENT_TYPE, "accept")[1].1.clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    fn decision_for(content: &Value) -> CustomEventDecision {
        let payload = embedded_payload(content).expect("a valid delivery.v2");
        match resolve_custom_event(
            default_registry(),
            Some(DELIVERY_V2_EVENT_TYPE),
            Some(&payload),
            body(content),
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    fn v2(content: &Value) -> GateV2Decision {
        decision_for(content).delivery_v2.expect("drawn from v2")
    }

    fn ids(delivery: &GateV2Decision) -> Vec<&str> {
        delivery.options.iter().map(|o| o.id.as_str()).collect()
    }

    const DELIVERY_EVENT: &str = "$deliveryEventId";

    #[test]
    fn every_accepted_delivery_v2_is_drawn_from_v2() {
        for (why, content) in cases(DELIVERY_V2_EVENT_TYPE, "accept") {
            assert!(parse(&content, body(&content)).is_some(), "{why}");
            assert!(embedded_payload(&content).is_some(), "{why}");
        }
    }

    #[test]
    fn every_rejected_delivery_v2_offers_nothing_it_cannot_honestly_resolve() {
        let rejects = cases(DELIVERY_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
        for (why, content) in rejects {
            if why.starts_with("switch_to_automatic on an incomplete preview") {
                // Drawn — the live link can still be recorded — but never
                // with the switch. A renderer's obligation for a reject is to
                // offer no decision it cannot honestly resolve.
                let delivery = v2(&content);
                assert_eq!(ids(&delivery), [RECORD_LIVE_URL], "{why}");
                assert_eq!(
                    delivery.approvals_withheld.as_deref(),
                    Some(SWITCH_WITHHELD_NOTE)
                );
                continue;
            }
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
        }
    }

    #[test]
    fn a_complete_preview_offers_both_choices_by_meaning() {
        let decision = decision_for(&complete());
        assert!(decision.options.is_empty(), "no plain reply answers it");
        assert_eq!(decision.subject, None);
        assert_eq!(
            decision.prompt,
            "Where did \u{201C}Announce the release\u{201D} go live?"
        );
        let delivery = decision.delivery_v2.unwrap();
        assert_eq!(delivery.gate_id, "gate_8f3a");
        assert_eq!(ids(&delivery), [RECORD_LIVE_URL, SWITCH_TO_AUTOMATIC]);
        assert_eq!(delivery.options[0].meaning, GateOptionMeaning::Defer);
        assert_eq!(
            delivery.options[0]
                .input
                .as_ref()
                .map(|i| (i.kind, i.required, i.max_length)),
            Some((OptionInputKind::Url, true, 2000))
        );
        assert_eq!(delivery.options[1].meaning, GateOptionMeaning::Approve);
        assert_eq!(delivery.approvals_withheld, None);
        assert_eq!(
            delivery.preview[0],
            GatePreviewBlock::Text {
                label: Some("Post".into()),
                text: "Our next release ships today.\n\nThanks to everyone who tested it.".into(),
            }
        );
        assert_eq!(delivery.subject.unwrap().id, "aps_2c9e71");
    }

    #[test]
    fn switch_to_automatic_is_withheld_on_an_incomplete_preview() {
        // The board marked it incomplete …
        let mut said = complete();
        said["subject"]["preview_complete"] = json!(false);
        // … or this client could not draw a block.
        let mut undrawable = complete();
        undrawable["subject"]["preview"]
            .as_array_mut()
            .unwrap()
            .push(json!({ "type": "image", "url": "https://x.example/a.png" }));
        // … or there is no subject to preview at all.
        let mut no_subject = complete();
        no_subject.as_object_mut().unwrap().remove("subject");
        for (why, content) in [
            ("said incomplete", said),
            ("undrawable block", undrawable),
            ("no subject", no_subject),
        ] {
            let delivery = v2(&content);
            assert_eq!(ids(&delivery), [RECORD_LIVE_URL], "{why}");
            assert_eq!(
                delivery.approvals_withheld.as_deref(),
                Some(SWITCH_WITHHELD_NOTE),
                "{why}"
            );
        }
        // The corpus's incomplete follow-up never offered it: nothing withheld.
        let delivery = v2(&incomplete());
        assert_eq!(ids(&delivery), [RECORD_LIVE_URL]);
        assert_eq!(delivery.approvals_withheld, None);
    }

    #[test]
    fn an_option_the_board_has_no_call_for_is_not_drawn() {
        let mut content = complete();
        content["options"].as_array_mut().unwrap().push(json!({
            "id": "remind_me", "label": "Remind me", "meaning": "defer", "needs_comment": false
        }));
        assert!(embedded_payload(&content).is_none());
    }

    // ---- delivery.answer.v2 ------------------------------------------------

    #[test]
    fn the_answers_are_exactly_the_corpus_answers() {
        let accepts = cases(DELIVERY_ANSWER_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 2, "the corpus grew; read the new case");
        let delivery = v2(&complete());
        assert_eq!(
            answer_request(
                &delivery,
                RECORD_LIVE_URL,
                Some(" https://social.example/posts/1 "),
                DELIVERY_EVENT
            )
            .unwrap(),
            accepts[0].1,
            "{}",
            accepts[0].0
        );
        assert_eq!(
            answer_request(&delivery, SWITCH_TO_AUTOMATIC, None, DELIVERY_EVENT).unwrap(),
            accepts[1].1,
            "{}",
            accepts[1].0
        );
    }

    #[test]
    fn every_rejected_answer_is_refused() {
        let rejects = cases(DELIVERY_ANSWER_V2_SUITE_TYPE, "reject");
        assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
        let delivery = v2(&complete());
        // A link that is not https.
        for url in [
            "http://social.example/posts/1",
            "javascript:alert(1)",
            "social.example/posts/1",
            "https://",
        ] {
            let err = answer_request(&delivery, RECORD_LIVE_URL, Some(url), DELIVERY_EVENT)
                .expect_err(url);
            assert!(
                matches!(&err, CoreError::Protocol(m) if m.contains("https://")),
                "{url}"
            );
        }
        // Recording the live link with no link.
        for text in [None, Some(""), Some("  ")] {
            let err = answer_request(&delivery, RECORD_LIVE_URL, text, DELIVERY_EVENT)
                .expect_err("no link");
            assert!(matches!(&err, CoreError::Protocol(m) if m.contains("needs a link")));
        }
        // An option the follow-up did not offer.
        assert!(answer_request(&delivery, "remind_me", None, DELIVERY_EVENT).is_err());
        // Switching to automatic on a follow-up whose preview was incomplete.
        assert!(answer_request(
            &v2(&incomplete()),
            SWITCH_TO_AUTOMATIC,
            None,
            DELIVERY_EVENT
        )
        .is_err());
        // No m.relates_to.
        assert!(answer_request(
            &delivery,
            RECORD_LIVE_URL,
            Some("https://social.example/posts/1"),
            "not-an-event"
        )
        .is_err());
    }

    #[test]
    fn a_withheld_switch_cannot_be_sent_even_if_a_host_puts_it_back() {
        let mut content = complete();
        content["subject"]["preview_complete"] = json!(false);
        let delivery = v2(&content);
        let mut forged = delivery.clone();
        forged.options.push(GateOptionV2 {
            id: SWITCH_TO_AUTOMATIC.into(),
            label: "Post it automatically".into(),
            meaning: GateOptionMeaning::Approve,
            needs_comment: false,
            input: None,
            scope: None,
            requires: None,
        });
        assert!(answer_request(&forged, SWITCH_TO_AUTOMATIC, None, DELIVERY_EVENT).is_err());
        assert!(answer_request(
            &delivery,
            RECORD_LIVE_URL,
            Some("https://social.example/posts/1"),
            DELIVERY_EVENT
        )
        .is_ok());
    }

    #[test]
    fn a_link_over_its_limit_is_refused() {
        let delivery = v2(&complete());
        let url = format!("https://x.example/{}", "a".repeat(2000));
        assert!(answer_request(&delivery, RECORD_LIVE_URL, Some(&url), DELIVERY_EVENT).is_err());
    }
}
