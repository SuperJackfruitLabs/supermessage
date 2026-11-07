//! question.v2 — an agent's question, answerable from its card.
//!
//! charter → `decisions/2026-11-07-the-envelope-covers-questions-and-follow-ups.md`.
//! The hub puts these fields on the prose `m.room.message` that asks the
//! question, marked `suite_event_type: "dev.superpipeline.question.v2"`, beside
//! the v1 card it still carries under `dev.superpipeline.elicitation` for older
//! clients. This client never drew that v1 card — a question without valid v2
//! fields stays the prose it is, answerable by replying a number or a label,
//! exactly as before. The wire is pinned by
//! `fixtures/ecosystem-identity/matrix_gate_events.json`.
//!
//! A question is drawn by the same rules as a gate.v2 (`crate::gate_v2`):
//! options by **meaning** (`choose` for an ordinary answer, `approve` /
//! `reject` on a strict decision), never by id; an option may ask for input
//! (`crate::gate_v2::OptionInput`). A question with **no** options carries
//! `free_text: { max_length }` and is answered with text alone.
//!
//! The answer is `dev.superpipeline.question.answer.v2`, built and checked by
//! [`answer_request`] against the question the reader saw.
//!
//! Everything here is room content, so it is hostile: read one level at a
//! time, never coerced, every drawn string bounded.

use serde_json::Value;

use crate::custom_events::{
    safe_link, safe_string_field, CustomEventDecision, CustomEventDecisionOption, CustomEventField,
    CustomEventRenderResult, CustomEventRenderer,
};
use crate::error::{CoreError, CoreResult};
use crate::gate_v2::{checked_input, id_field, parse_option_list, GateOptionV2};

/// The `suite_event_type` marker on a question.v2 prose message, and the
/// event type its card is registered under.
pub const QUESTION_V2_EVENT_TYPE: &str = "dev.superpipeline.question.v2";

/// The `suite_event_type` of the answer to a question.v2.
pub const QUESTION_ANSWER_V2_SUITE_TYPE: &str = "dev.superpipeline.question.answer.v2";

/// The key the v1 question card rides under on the same message. Not drawn
/// by this client; stripped from the row's payload.
pub const EMBEDDED_ELICITATION_KEY: &str = "dev.superpipeline.elicitation";

/// The longest free-text answer a question may ask for (the fixture's
/// "at most 2000").
pub const FREE_TEXT_MAX_LENGTH: u32 = 2_000;

/// How long the question itself may be on the card. It is the one sentence
/// the reader must answer, so far above a field's 300 characters.
const QUESTION_MAX_CHARS: usize = 2_000;
const FIELD_VALUE_MAX_CHARS: usize = 300;
const FIELD_LABEL_MAX_CHARS: usize = 60;

/// A question with nothing to choose: the reader types the answer.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct QuestionFreeText {
    /// In characters, 1 to [`FREE_TEXT_MAX_LENGTH`].
    pub max_length: u32,
}

/// A question.v2 as a host draws and answers it. Rides on
/// `CustomEventDecision::question_v2`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct QuestionV2Decision {
    /// Handed back verbatim in the answer.
    pub elicitation_id: String,
    /// The question, bounded at 2,000 characters — the decision's `prompt`
    /// is the same sentence cut at 300, so a host draws this one.
    pub question: String,
    /// In the question's order. Empty exactly when `free_text` is set.
    pub options: Vec<GateOptionV2>,
    pub free_text: Option<QuestionFreeText>,
}

/// A question.v2 that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedQuestionV2 {
    elicitation_id: String,
    card_title: String,
    stage_key: Option<String>,
    question: String,
    deep_link: Option<String>,
    options: Vec<GateOptionV2>,
    free_text: Option<QuestionFreeText>,
}

/// `content.free_text`: `Ok(None)` when absent, `Err(())` when present and
/// not `{ max_length }` with a whole number from 1 to the limit.
fn parse_free_text(content: &Value) -> Result<Option<QuestionFreeText>, ()> {
    let raw = match content.get("free_text") {
        None | Some(Value::Null) => return Ok(None),
        Some(raw) => raw,
    };
    let max_length = raw
        .get("max_length")
        .and_then(Value::as_u64)
        .filter(|n| (1..=u64::from(FREE_TEXT_MAX_LENGTH)).contains(n))
        .ok_or(())?;
    Ok(Some(QuestionFreeText {
        max_length: max_length as u32,
    }))
}

/// Read a question.v2 from a message's `content`, or `None` when it is not
/// one this client may draw — in which case the message stays prose.
///
/// Refused, as the corpus refuses them: no body, an option with no meaning,
/// an input spelled `maxLength`, duplicate option ids, `free_text` beside
/// options, and — because there would be nothing to answer with — no
/// options and no `free_text`.
pub(crate) fn parse(content: &Value, body: Option<&str>) -> Option<ParsedQuestionV2> {
    if content.get("suite_event_type")?.as_str()? != QUESTION_V2_EVENT_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let elicitation_id = id_field(content, "elicitation_id")?;
    let card_title = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)?;
    let question = safe_string_field(content, "question", QUESTION_MAX_CHARS)
        .filter(|q| !q.trim().is_empty())?;
    let options = parse_option_list(content.get("options")?.as_array()?, true)?;
    let free_text = parse_free_text(content).ok()?;
    match (options.is_empty(), free_text.is_some()) {
        // Text alone answers only a question with nothing to choose.
        (false, true) => return None,
        // Nothing to answer with at all.
        (true, false) => return None,
        _ => {}
    }
    Some(ParsedQuestionV2 {
        elicitation_id,
        card_title,
        stage_key: safe_string_field(content, "stage_key", FIELD_LABEL_MAX_CHARS),
        question,
        deep_link: safe_link(content, "deep_link"),
        options,
        free_text,
    })
}

/// The payload a question.v2 message rides on its timeline row, or `None`
/// when the message is not a valid question.v2 or too large to carry one.
/// The body (the row's own) and the v1 card are stripped first.
pub fn embedded_payload(content: &Value) -> Option<Value> {
    let body = content.get("body").and_then(Value::as_str);
    parse(content, body)?;
    let mut payload = content.as_object()?.clone();
    payload.remove("body");
    payload.remove(EMBEDDED_ELICITATION_KEY);
    let payload = Value::Object(payload);
    let size = serde_json::to_string(&payload)
        .map(|s| s.len())
        .unwrap_or(usize::MAX);
    (size <= crate::gate_v2::GATE_V2_PAYLOAD_MAX_BYTES).then_some(payload)
}

impl ParsedQuestionV2 {
    fn decision(&self) -> QuestionV2Decision {
        QuestionV2Decision {
            elicitation_id: self.elicitation_id.clone(),
            question: self.question.clone(),
            options: self.options.clone(),
            free_text: self.free_text.clone(),
        }
    }

    /// What a host that knows nothing of v2 may offer: the options a plain
    /// reply answers. Such a host answers a decision with no subject by
    /// sending the option's id as text (as for a permission request), and
    /// the hub matches a typed label — so each id here is the label. An
    /// option that asks for input is left out: its text cannot ride along.
    fn legacy_options(&self) -> Vec<CustomEventDecisionOption> {
        self.options
            .iter()
            .filter(|o| o.input.is_none())
            .map(|o| CustomEventDecisionOption {
                id: o.label.clone(),
                label: o.label.clone(),
            })
            .collect()
    }
}

/// Draws a question.v2 — registered under [`QUESTION_V2_EVENT_TYPE`].
pub struct QuestionV2Renderer;

impl CustomEventRenderer for QuestionV2Renderer {
    fn event_type(&self) -> &str {
        QUESTION_V2_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Question"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(question) = parse(content, body) else {
            return CustomEventRenderResult::default();
        };
        let mut fields = vec![CustomEventField {
            label: "Card".into(),
            value: question.card_title.clone(),
        }];
        if let Some(stage) = &question.stage_key {
            fields.push(CustomEventField {
                label: "Stage".into(),
                value: stage.clone(),
            });
        }
        CustomEventRenderResult {
            fields,
            reasoning: None,
            link: question.deep_link.clone(),
            decision: Some(CustomEventDecision {
                prompt: question.question.clone(),
                options: question.legacy_options(),
                subject: None,
                gate_v2: None,
                question_v2: Some(question.decision()),
                delivery_v2: None,
            }),
        }
    }
}

/// `text` with its first letter lowered, unless the first word is an
/// acronym ("URL", "CI") — so "Something else" + "Run only the unit tests"
/// reads "Something else: run only the unit tests".
fn continuing(text: &str) -> String {
    let mut chars = text.chars();
    let (Some(first), second) = (chars.next(), chars.next()) else {
        return text.to_string();
    };
    if !first.is_uppercase() || second.is_some_and(char::is_uppercase) {
        return text.to_string();
    }
    let mut out: String = first.to_lowercase().collect();
    out.push_str(&text[first.len_utf8()..]);
    out
}

/// The sentence an answer leaves in the room: the option's label, the label
/// and the text it asked for, or the free text itself.
pub(crate) fn answer_body(option: Option<&GateOptionV2>, text: Option<&str>) -> String {
    match (option, text) {
        (Some(option), Some(text)) => format!("{}: {}", option.label.trim(), continuing(text)),
        (Some(option), None) => option.label.trim().to_string(),
        (None, Some(text)) => text.to_string(),
        (None, None) => String::new(),
    }
}

/// A question.answer.v2's `content`, validated against the question the
/// reader saw — the whole of what goes on the wire.
///
/// `option_id` answers with one of the question's options (and `text`, when
/// that option asks for input); `None` answers a free-text question with
/// `text` alone. Refuses, with a reason a host can show: an option the
/// question did not offer, a required input left empty, an input over its
/// limit or a `url` input that is not `https://`, free text on a question
/// with options, empty free text, free text over the question's limit, and
/// a reference that is not an event id.
pub(crate) fn answer_request(
    question: &QuestionV2Decision,
    option_id: Option<&str>,
    text: Option<&str>,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if question.elicitation_id.is_empty() {
        return Err(CoreError::Protocol("an answer needs a question".into()));
    }
    let (option, text) = match option_id {
        Some(option_id) => {
            let option = question
                .options
                .iter()
                .find(|o| o.id == option_id)
                .ok_or_else(|| {
                    CoreError::Protocol(format!("not an option this question offers: {option_id}"))
                })?;
            (Some(option), checked_input(option, text)?)
        }
        None => {
            let Some(free_text) = &question.free_text else {
                return Err(CoreError::Protocol(
                    "this question is answered with one of its options".into(),
                ));
            };
            let text = text
                .map(str::trim)
                .filter(|t| !t.is_empty())
                .ok_or_else(|| CoreError::Protocol("the answer is empty".into()))?;
            if text.chars().count() > free_text.max_length as usize {
                return Err(CoreError::Protocol(format!(
                    "the answer takes at most {} characters",
                    free_text.max_length
                )));
            }
            (None, Some(text.to_string()))
        }
    };
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    let mut content = serde_json::json!({
        "msgtype": "m.text",
        "body": answer_body(option, text.as_deref()),
        "schema_version": 1,
        "suite_event_type": QUESTION_ANSWER_V2_SUITE_TYPE,
        "elicitation_id": question.elicitation_id,
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    });
    if let Some(option) = option {
        content["option_id"] = Value::String(option.id.clone());
    }
    if let Some(text) = text {
        content["text"] = Value::String(text);
    }
    Ok(content)
}

/// The corpus, end to end: every question.v2 `accept` draws, every `reject`
/// stays prose, and what the client sends for the corpus's questions is
/// exactly the corpus's question.answer.v2.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use crate::gate_v2::{GateOptionMeaning, OptionInput, OptionInputKind};
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

    /// The question with three options, one asking for text.
    fn with_options() -> Value {
        cases(QUESTION_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    /// The question with no options.
    fn free() -> Value {
        cases(QUESTION_V2_EVENT_TYPE, "accept")[1].1.clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    fn decision_for(content: &Value) -> CustomEventDecision {
        let payload = embedded_payload(content).expect("a valid question.v2");
        match resolve_custom_event(
            default_registry(),
            Some(QUESTION_V2_EVENT_TYPE),
            Some(&payload),
            body(content),
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    fn v2(content: &Value) -> QuestionV2Decision {
        decision_for(content).question_v2.expect("drawn from v2")
    }

    const QUESTION_EVENT: &str = "$questionEventId";

    #[test]
    fn every_accepted_question_v2_is_drawn_from_v2() {
        for (why, content) in cases(QUESTION_V2_EVENT_TYPE, "accept") {
            assert!(parse(&content, body(&content)).is_some(), "{why}");
            assert!(embedded_payload(&content).is_some(), "{why}");
        }
    }

    #[test]
    fn every_rejected_question_v2_stays_prose() {
        let rejects = cases(QUESTION_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
        }
    }

    #[test]
    fn a_question_offers_its_options_by_meaning_with_their_input() {
        let decision = decision_for(&with_options());
        let question = decision.question_v2.as_ref().unwrap();
        assert_eq!(question.elicitation_id, "elc_7f3c");
        assert_eq!(
            question.question,
            "The tests need network access. May I run them?"
        );
        assert_eq!(decision.prompt, question.question);
        assert_eq!(question.free_text, None);
        let ids: Vec<&str> = question.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(ids, ["run_them", "skip", "other"]);
        assert!(question
            .options
            .iter()
            .all(|o| o.meaning == GateOptionMeaning::Choose));
        assert_eq!(question.options[0].input, None);
        assert_eq!(
            question.options[2].input,
            Some(OptionInput {
                kind: OptionInputKind::Text,
                label: "What should I do instead?".into(),
                required: true,
                max_length: 500,
            })
        );
        // No subject: a question resolves no gate.
        assert_eq!(decision.subject, None);
        assert_eq!(decision.gate_v2, None);
    }

    #[test]
    fn a_host_without_v2_sees_only_what_a_plain_reply_answers() {
        let decision = decision_for(&with_options());
        let legacy: Vec<(&str, &str)> = decision
            .options
            .iter()
            .map(|o| (o.id.as_str(), o.label.as_str()))
            .collect();
        assert_eq!(
            legacy,
            [
                ("Run the tests", "Run the tests"),
                ("Skip them", "Skip them")
            ]
        );
        assert!(decision_for(&free()).options.is_empty());
    }

    #[test]
    fn a_question_with_no_options_asks_for_free_text() {
        let question = v2(&free());
        assert!(question.options.is_empty());
        assert_eq!(
            question.free_text,
            Some(QuestionFreeText { max_length: 2000 })
        );
    }

    #[test]
    fn a_question_with_nothing_to_answer_or_an_impossible_limit_stays_prose() {
        let mut nothing = free();
        nothing.as_object_mut().unwrap().remove("free_text");
        assert!(embedded_payload(&nothing).is_none());
        for limit in [json!(0), json!(2001), json!("500"), json!(-1)] {
            let mut bad = free();
            bad["free_text"]["max_length"] = limit.clone();
            assert!(embedded_payload(&bad).is_none(), "{limit}");
        }
        let mut bad_input = with_options();
        bad_input["options"][2]["input"]["kind"] = json!("number");
        assert!(embedded_payload(&bad_input).is_none());
    }

    #[test]
    fn the_v1_card_and_body_are_stripped_from_the_row() {
        let mut content = with_options();
        content[EMBEDDED_ELICITATION_KEY] = json!({ "elicitation_id": "elc_7f3c" });
        let payload = embedded_payload(&content).unwrap();
        assert!(payload.get("body").is_none());
        assert!(payload.get(EMBEDDED_ELICITATION_KEY).is_none());
    }

    #[test]
    fn a_message_without_the_marker_is_not_a_question() {
        let mut content = with_options();
        content.as_object_mut().unwrap().remove("suite_event_type");
        assert!(embedded_payload(&content).is_none());
    }

    // ---- question.answer.v2 ------------------------------------------------

    #[test]
    fn the_answers_are_exactly_the_corpus_answers() {
        let accepts = cases(QUESTION_ANSWER_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 3, "the corpus grew; read the new case");
        let question = v2(&with_options());
        assert_eq!(
            answer_request(&question, Some("run_them"), None, QUESTION_EVENT).unwrap(),
            accepts[0].1,
            "{}",
            accepts[0].0
        );
        assert_eq!(
            answer_request(
                &question,
                Some("other"),
                Some("  Run only the unit tests \n"),
                QUESTION_EVENT
            )
            .unwrap(),
            accepts[1].1,
            "{}",
            accepts[1].0
        );
        assert_eq!(
            answer_request(&v2(&free()), None, Some("main"), QUESTION_EVENT).unwrap(),
            accepts[2].1,
            "{}",
            accepts[2].0
        );
    }

    #[test]
    fn every_rejected_answer_is_refused() {
        // The corpus's question.answer.v2 rejects, as a client would try
        // to send them.
        let rejects = cases(QUESTION_ANSWER_V2_SUITE_TYPE, "reject");
        assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
        let options = v2(&with_options());
        let free = v2(&free());
        // An option the question did not offer.
        assert!(answer_request(&options, Some("ship_it"), None, QUESTION_EVENT).is_err());
        // The option whose input is required, with no text.
        for text in [None, Some(""), Some("   ")] {
            let err = answer_request(&options, Some("other"), text, QUESTION_EVENT)
                .expect_err("a required input sent empty");
            assert!(matches!(&err, CoreError::Protocol(m) if m.contains("needs an answer")));
        }
        // An empty free-text answer.
        assert!(answer_request(&free, None, Some("   "), QUESTION_EVENT).is_err());
        assert!(answer_request(&free, None, None, QUESTION_EVENT).is_err());
        // A free-text answer over the question's limit.
        let long = rejects[3].1["text"].as_str().unwrap();
        assert!(long.chars().count() > 2000);
        assert!(answer_request(&free, None, Some(long), QUESTION_EVENT).is_err());
        // No m.relates_to: a reference that is not an event cannot be sent.
        assert!(answer_request(&options, Some("run_them"), None, "not-an-event").is_err());
    }

    #[test]
    fn free_text_at_the_limit_goes_and_one_past_it_does_not() {
        let free = v2(&free());
        let at = "é".repeat(2000);
        assert!(answer_request(&free, None, Some(&at), QUESTION_EVENT).is_ok());
        let past = "é".repeat(2001);
        assert!(answer_request(&free, None, Some(&past), QUESTION_EVENT).is_err());
    }

    #[test]
    fn free_text_does_not_answer_a_question_with_options() {
        let options = v2(&with_options());
        assert!(answer_request(&options, None, Some("whatever"), QUESTION_EVENT).is_err());
    }

    #[test]
    fn an_input_over_its_limit_is_refused() {
        let options = v2(&with_options());
        let long = "x".repeat(501);
        let err = answer_request(&options, Some("other"), Some(&long), QUESTION_EVENT)
            .expect_err("over max_length");
        assert!(matches!(&err, CoreError::Protocol(m) if m.contains("at most 500")));
    }

    #[test]
    fn text_beside_an_option_without_input_is_not_sent() {
        let options = v2(&with_options());
        let content =
            answer_request(&options, Some("skip"), Some("ignored"), QUESTION_EVENT).unwrap();
        assert!(content.get("text").is_none());
        assert_eq!(content["body"], "Skip them");
    }

    #[test]
    fn a_continuing_answer_keeps_an_acronym() {
        assert_eq!(continuing("Run it"), "run it");
        assert_eq!(continuing("CI only"), "CI only");
        assert_eq!(continuing("x"), "x");
        assert_eq!(continuing("É bien"), "é bien");
    }
}
