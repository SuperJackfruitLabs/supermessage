//! attention.v2 — a card that waits on a person, answerable from chat.
//!
//! charter → `decisions/2026-11-08-waiting-cards-and-broker-approvals-reach-chat.md`
//! §1–2. When a superpipeline card parks for a person for a reason other than
//! a question or a review — it failed repeatedly, it is blocked, or its agent
//! was not authorised — the hub posts one prose `m.room.message` into the
//! board's room, marked `suite_event_type: "dev.superpipeline.attention.v2"`.
//! There is no v1 card for a waiting card: a message whose v2 fields do not
//! validate stays the prose it is. The wire is pinned by
//! `fixtures/ecosystem-identity/matrix_gate_events.json`.
//!
//! What chat may offer, and nothing else (§2):
//!
//! - `resume_here` — meaning `approve`, with a **required** text input: the
//!   instruction for the next attempt;
//! - `resume_at:<stageKey>` — meaning `choose`, one per earlier stage, with
//!   the same required input;
//! - `archive` — meaning `reject`, no input.
//!
//! A forward move is never offered from chat (it skips a stage's work and
//! cancels its gates), so an option this module has no call for — or a
//! resume whose input is optional, missing or longer than the board takes —
//! keeps the whole message prose rather than drawing a button that would do
//! something else. A waiting card has no subject: nothing is frozen, so a
//! message carrying one is not the wire.
//!
//! The answer is `dev.superpipeline.attention.answer.v2` ([`answer_request`]),
//! carrying the card's **wait id**: the board refuses a stale one
//! (`WAIT_CHANGED`), so a late tap never acts on a different wait. The card
//! is closed by the hub's `dev.superpipeline.attention.outcome.v1`
//! ([`resolved_attention`]) — its own type, because `gate.outcome.v1` is
//! keyed by gate and a wait has no gate.
//!
//! Everything here is room content, so it is hostile: read one level at a
//! time, never coerced, every drawn string bounded.

use std::collections::HashMap;

use serde_json::Value;

use crate::custom_events::{
    bound_text, safe_link, safe_string_field, CustomEventDecision, CustomEventField,
    CustomEventOutcome, CustomEventRenderResult, CustomEventRenderer,
};
use crate::dto::{TimelineItemDto, TimelineRow};
use crate::error::{CoreError, CoreResult};
use crate::gate_v2::{
    checked_input, id_field, parse_option_list, GateOptionMeaning, GateOptionV2, OptionInputKind,
};

/// The `suite_event_type` marker on an attention.v2 prose message, and the
/// event type its card is registered under.
pub const ATTENTION_V2_EVENT_TYPE: &str = "dev.superpipeline.attention.v2";

/// The `suite_event_type` of the answer to an attention.v2.
pub const ATTENTION_ANSWER_V2_SUITE_TYPE: &str = "dev.superpipeline.attention.answer.v2";

/// The receipt that closes a waiting card — the custom event's Matrix type,
/// repeated as `suite_event_type` in its content.
pub const ATTENTION_OUTCOME_EVENT_TYPE: &str = "dev.superpipeline.attention.outcome.v1";

/// Resume the card at the stage it waits in.
pub const RESUME_HERE: &str = "resume_here";

/// Resume the card at an earlier stage: `resume_at:<stageKey>`.
pub const RESUME_AT_PREFIX: &str = "resume_at:";

/// Archive the card.
pub const ARCHIVE: &str = "archive";

/// The outcome's decision when the wait ended anywhere but this room.
pub const SETTLED_ELSEWHERE: &str = "settled_elsewhere";

/// The longest instruction the board takes (its comment limit). A resume
/// input asking for more was not sent by a hub that follows the contract.
pub const ATTENTION_INPUT_MAX_LENGTH: u32 = 2_730;

const FIELD_VALUE_MAX_CHARS: usize = 300;
const FIELD_LABEL_MAX_CHARS: usize = 60;
/// The agent's detail is prose — fenced in the hub's body — so bounded like
/// a card's reasoning, far above a field's 300 characters.
const DETAIL_MAX_CHARS: usize = 4_000;
/// How long a `decided_by` may be on a receipt. A handle, not a paragraph.
const DECIDED_BY_MAX_CHARS: usize = 60;

/// Why a card waits on a person.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum AttentionReason {
    /// Its attempts at this stage kept failing.
    RepeatedFailure,
    /// Its agent said it cannot go on.
    Blocked,
    /// Its agent was not allowed to do what the stage needs.
    NotAuthorised,
}

impl AttentionReason {
    fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "repeated-failure" => Self::RepeatedFailure,
            "blocked" => Self::Blocked,
            "not-authorised" => Self::NotAuthorised,
            _ => return None,
        })
    }
}

/// The reason in words, as a card says it: "Failed twice", "Blocked",
/// "Not authorised". Worded here so no host words it differently.
pub fn reason_words(reason: AttentionReason, failure_count: Option<u32>) -> String {
    match reason {
        AttentionReason::RepeatedFailure => match failure_count {
            Some(1) => "Failed once".to_string(),
            Some(2) => "Failed twice".to_string(),
            Some(n) if n > 2 => format!("Failed {n} times"),
            _ => "Failed repeatedly".to_string(),
        },
        AttentionReason::Blocked => "Blocked".to_string(),
        AttentionReason::NotAuthorised => "Not authorised".to_string(),
    }
}

/// A waiting card as a host draws and answers it. Rides on
/// `CustomEventDecision::attention_v2`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct AttentionV2Decision {
    /// Handed back verbatim in the answer.
    pub board_id: String,
    /// Handed back verbatim in the answer.
    pub card_id: String,
    /// This particular wait, handed back verbatim: the board refuses an
    /// answer naming a wait that has ended.
    pub wait_id: String,
    /// Display text, bounded.
    pub card_title: String,
    /// Display text, bounded.
    pub stage_name: String,
    pub reason: AttentionReason,
    /// [`reason_words`] for this card.
    pub reason_text: String,
    /// The park's one-line summary, verbatim (bounded), when it gave one.
    pub summary: Option<String>,
    /// The agent's detail, verbatim (bounded), when it gave one. Drawn as
    /// preformatted text: the hub fences it.
    pub detail: Option<String>,
    /// How many attempts failed, when the board counted.
    pub failure_count: Option<u32>,
    /// In the card's order, by meaning: `resume_here` (approve) and each
    /// `resume_at:<stageKey>` (choose) ask for a required text input;
    /// `archive` (reject) asks for none.
    pub options: Vec<GateOptionV2>,
}

/// An attention.v2 that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedAttentionV2 {
    decision: AttentionV2Decision,
    deep_link: Option<String>,
}

/// `content[key]`, present as a string or `null` — never absent, as the
/// corpus has it ("null when the park gave none; never absent"). `Err(())`
/// when absent or of another type. A blank string reads as none.
fn nullable_text(content: &Value, key: &str, max_chars: usize) -> Result<Option<String>, ()> {
    match content.get(key) {
        Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => {
            let s = s.trim();
            Ok((!s.is_empty()).then(|| bound_text(s, max_chars)))
        }
        _ => Err(()),
    }
}

/// `content.failure_count`: a whole number or `null`, never absent.
fn parse_failure_count(content: &Value) -> Result<Option<u32>, ()> {
    match content.get("failure_count") {
        Some(Value::Null) => Ok(None),
        Some(raw) => raw
            .as_u64()
            .and_then(|n| u32::try_from(n).ok())
            .map(Some)
            .ok_or(()),
        None => Err(()),
    }
}

/// The stage key a `resume_at:<stageKey>` option names, or `None` for any
/// other id.
fn resume_at_stage(option_id: &str) -> Option<&str> {
    option_id
        .strip_prefix(RESUME_AT_PREFIX)
        .filter(|key| !key.is_empty())
}

/// Whether a resume option asks for what the board needs: a required text
/// input of at most [`ATTENTION_INPUT_MAX_LENGTH`] characters.
fn resume_input_ok(option: &GateOptionV2) -> bool {
    option.input.as_ref().is_some_and(|input| {
        input.kind == OptionInputKind::Text
            && input.required
            && input.max_length <= ATTENTION_INPUT_MAX_LENGTH
    })
}

/// Whether `options` are a waiting card's: at least one, and each one the
/// board has a call for, with the meaning and input that call needs.
fn waiting_options(options: &[GateOptionV2]) -> bool {
    !options.is_empty()
        && options.iter().all(|o| {
            if o.id == RESUME_HERE {
                o.meaning == GateOptionMeaning::Approve && resume_input_ok(o)
            } else if resume_at_stage(&o.id).is_some() {
                o.meaning == GateOptionMeaning::Choose && resume_input_ok(o)
            } else if o.id == ARCHIVE {
                o.meaning == GateOptionMeaning::Reject && o.input.is_none()
            } else {
                // A forward move, or anything else the board has no chat
                // call for.
                false
            }
        })
}

/// Read an attention.v2 from a message's `content`, or `None` when it is not
/// one this client may draw — in which case the message stays prose.
///
/// Refused, as the corpus refuses them: no body, no `wait_id` (an answer
/// could not name the wait), a forward move or any option the board has no
/// call for, a resume whose instruction is optional, an input spelled
/// `maxLength`, an input longer than the board takes, and a subject.
pub(crate) fn parse(content: &Value, body: Option<&str>) -> Option<ParsedAttentionV2> {
    if content.get("suite_event_type")?.as_str()? != ATTENTION_V2_EVENT_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let board_id = id_field(content, "board_id")?;
    let card_id = id_field(content, "card_id")?;
    let wait_id = id_field(content, "wait_id")?;
    // Required by the wire, never drawn: the board resumes by it.
    id_field(content, "stage_key")?;
    let card_title = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)
        .filter(|t| !t.trim().is_empty())?;
    let stage_name = safe_string_field(content, "stage_name", FIELD_LABEL_MAX_CHARS)
        .filter(|s| !s.trim().is_empty())?;
    let reason = AttentionReason::parse(content.get("reason")?.as_str()?)?;
    let detail = nullable_text(content, "detail", DETAIL_MAX_CHARS).ok()?;
    let summary = nullable_text(content, "summary", FIELD_VALUE_MAX_CHARS).ok()?;
    let failure_count = parse_failure_count(content).ok()?;
    // No subject: nothing is frozen on a waiting card.
    if !matches!(content.get("subject"), None | Some(Value::Null)) {
        return None;
    }
    let options = parse_option_list(content.get("options")?.as_array()?, true)?;
    if !waiting_options(&options) {
        return None;
    }
    Some(ParsedAttentionV2 {
        decision: AttentionV2Decision {
            board_id,
            card_id,
            wait_id,
            card_title,
            stage_name,
            reason,
            reason_text: reason_words(reason, failure_count),
            summary,
            detail,
            failure_count,
            options,
        },
        deep_link: safe_link(content, "deep_link"),
    })
}

/// The payload an attention.v2 message rides on its timeline row, or `None`
/// when the message is not a valid attention.v2 or too large to carry one.
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

/// Draws an attention.v2 — registered under [`ATTENTION_V2_EVENT_TYPE`].
pub struct AttentionV2Renderer;

impl CustomEventRenderer for AttentionV2Renderer {
    fn event_type(&self) -> &str {
        ATTENTION_V2_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Waiting card"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(parsed) = parse(content, body) else {
            return CustomEventRenderResult::default();
        };
        let attention = parsed.decision;
        let prompt = format!("\u{201C}{}\u{201D} is waiting on you", attention.card_title);
        let mut fields = vec![
            CustomEventField {
                label: "Card".into(),
                value: attention.card_title.clone(),
            },
            CustomEventField {
                label: "Stage".into(),
                value: attention.stage_name.clone(),
            },
            CustomEventField {
                label: "Why".into(),
                value: attention.reason_text.clone(),
            },
        ];
        if let Some(summary) = &attention.summary {
            fields.push(CustomEventField {
                label: "Summary".into(),
                value: summary.clone(),
            });
        }
        CustomEventRenderResult {
            fields,
            // The agent's own words, drawn below the fields.
            reasoning: attention.detail.clone(),
            link: parsed.deep_link,
            decision: Some(CustomEventDecision {
                prompt,
                // No plain reply answers a waiting card: every resume needs
                // its instruction, and the hub reads no typed reply here.
                options: Vec::new(),
                subject: None,
                gate_v2: None,
                question_v2: None,
                delivery_v2: None,
                permission_v2: None,
                attention_v2: Some(attention),
                deadline: crate::decision_timeout::parse_deadline(content),
            }),
        }
    }
}

/// An attention.answer.v2's `content`, validated against the card the reader
/// saw — the whole of what goes on the wire, exactly the corpus's shape.
///
/// Refuses, with a reason a host can show: an option the card did not offer
/// (a forward move included), a resume with no instruction or one longer
/// than its limit, and a reference that is not an event id. The board, card
/// and wait are copied from the card, never computed, so an answer can name
/// no other wait. Text beside `archive` (which asks for none) is dropped.
pub(crate) fn answer_request(
    attention: &AttentionV2Decision,
    option_id: &str,
    text: Option<&str>,
    in_reply_to: &str,
) -> CoreResult<Value> {
    let named = [&attention.board_id, &attention.card_id, &attention.wait_id];
    if named.iter().any(|id| id.is_empty()) {
        return Err(CoreError::Protocol("an answer needs a waiting card".into()));
    }
    let option = attention
        .options
        .iter()
        .find(|o| o.id == option_id)
        .ok_or_else(|| {
            CoreError::Protocol(format!("not an option this card offers: {option_id}"))
        })?;
    // Checked again here, so a host that edited the card cannot send a
    // resume without its instruction.
    if (option.id == RESUME_HERE || resume_at_stage(&option.id).is_some())
        && !resume_input_ok(option)
    {
        return Err(CoreError::Protocol(format!(
            "\u{201C}{}\u{201D} needs an instruction",
            option.label
        )));
    }
    let text = checked_input(option, text)?;
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    let mut content = serde_json::json!({
        "msgtype": "m.text",
        "body": option.label.trim(),
        "suite_event_type": ATTENTION_ANSWER_V2_SUITE_TYPE,
        "board_id": attention.board_id,
        "card_id": attention.card_id,
        "wait_id": attention.wait_id,
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

// ---------------------------------------------------------------------------
// The outcome: the hub's receipt closes the card.
// ---------------------------------------------------------------------------

/// One outcome as read off a row.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Outcome {
    board_id: String,
    card_id: String,
    wait_id: String,
    decision: String,
    decided_by: Option<String>,
    references: String,
    sender: String,
}

/// The event an outcome's `m.reference` points at.
fn reference(content: &Value) -> Option<String> {
    let relates = content.get("m.relates_to")?;
    if relates.get("rel_type")?.as_str()? != "m.reference" {
        return None;
    }
    id_field(relates, "event_id")
}

/// The outcome a row carries, or `None`. An outcome with no wait (a client
/// cannot tell which wait closed), no `m.reference` to the card's message, or
/// no decision is not the schema and closes nothing.
fn outcome_of(item: &TimelineItemDto) -> Option<Outcome> {
    if item.kind != "customMessage" || item.detail.as_deref() != Some(ATTENTION_OUTCOME_EVENT_TYPE)
    {
        return None;
    }
    let content = &item.custom_payload.as_ref()?.0;
    let declared = content.get("suite_event_type").and_then(Value::as_str);
    if declared != Some(ATTENTION_OUTCOME_EVENT_TYPE) {
        return None;
    }
    Some(Outcome {
        board_id: id_field(content, "board_id")?,
        card_id: id_field(content, "card_id")?,
        wait_id: id_field(content, "wait_id")?,
        decision: id_field(content, "decision")?,
        decided_by: safe_string_field(content, "decided_by", DECIDED_BY_MAX_CHARS)
            .map(|who| who.trim().to_string())
            .filter(|who| !who.is_empty()),
        references: reference(content)?,
        sender: item.sender.clone()?,
    })
}

/// A waiting card's carrier, as the matching needs it.
struct Carrier<'a> {
    identity: String,
    board_id: &'a str,
    card_id: &'a str,
    wait_id: &'a str,
    event_id: Option<&'a str>,
    sender: Option<&'a str>,
    payload: &'a Value,
}

fn carrier_of(item: &TimelineItemDto) -> Option<Carrier<'_>> {
    if !matches!(item.kind.as_str(), "message" | "customMessage")
        || item.detail.as_deref() != Some(ATTENTION_V2_EVENT_TYPE)
    {
        return None;
    }
    let payload = &item.custom_payload.as_ref()?.0;
    Some(Carrier {
        identity: crate::embedded::decision_identity(ATTENTION_V2_EVENT_TYPE, payload)?,
        board_id: payload.get("board_id")?.as_str()?,
        card_id: payload.get("card_id")?.as_str()?,
        wait_id: payload.get("wait_id")?.as_str()?,
        event_id: item.event_id.as_deref(),
        sender: item.sender.as_deref(),
        payload,
    })
}

/// Whether `outcome` closes the card `carrier` draws: sent by the identity
/// that posted the card (anyone in a room can send an event shaped like this
/// one, and closing someone else's waiting card is what a forged one would
/// be for), referencing exactly this card's message, about the same wait.
fn closes(outcome: &Outcome, carrier: &Carrier<'_>) -> bool {
    carrier.sender == Some(outcome.sender.as_str())
        && carrier.event_id == Some(outcome.references.as_str())
        && carrier.board_id == outcome.board_id
        && carrier.card_id == outcome.card_id
        && carrier.wait_id == outcome.wait_id
}

/// The stage a `resume_at:<key>` decision went back to, as the card named
/// it: the option's label without its "Resume at " lead ("Plan"), or the
/// key itself when the card said nothing better.
fn stage_named(payload: &Value, decision: &str, key: &str) -> String {
    let options = payload.get("options").and_then(Value::as_array);
    let label = options
        .into_iter()
        .flatten()
        .find(|o| o.get("id").and_then(Value::as_str) == Some(decision))
        .and_then(|o| safe_string_field(o, "label", FIELD_LABEL_MAX_CHARS));
    let stage = label
        .as_deref()
        .and_then(|l| l.trim().strip_prefix("Resume at "));
    match stage.map(str::trim).filter(|s| !s.is_empty()) {
        Some(stage) => stage.to_string(),
        None => bound_text(key, FIELD_LABEL_MAX_CHARS),
    }
}

/// The sentence a receipt says. Past tense, because it is over: "Resumed by
/// rakesh", "Resumed at Plan by rakesh", "Archived by rakesh", "Settled on
/// the board" — the hub's own verbs (`receiptWords`, agentpod
/// `attention-answer.ts`), which say "Resumed (Resume at Plan)" where this
/// names the stage.
fn summary(outcome: &Outcome, payload: &Value) -> String {
    let verb = match outcome.decision.as_str() {
        RESUME_HERE => "Resumed".to_string(),
        ARCHIVE => "Archived".to_string(),
        // Nobody in this room decided it: never "by" anyone.
        SETTLED_ELSEWHERE => return "Settled on the board".to_string(),
        decision => match resume_at_stage(decision) {
            Some(key) => format!("Resumed at {}", stage_named(payload, decision, key)),
            // A decision this build has no word for.
            None => "Decided".to_string(),
        },
    };
    match &outcome.decided_by {
        Some(who) => format!("{verb} by {who}"),
        None => verb,
    }
}

/// Every waiting card the loaded timeline says is closed, keyed by the
/// card's decision identity (`attention:<board>:<card>:<wait>`), with the
/// receipt to draw. Empty — and cheap — for a timeline holding no attention
/// outcome. A wait with two outcomes takes the later one in the timeline.
pub fn resolved_attention(rows: &[TimelineRow]) -> HashMap<String, CustomEventOutcome> {
    let outcomes: Vec<Outcome> = rows
        .iter()
        .filter_map(|row| outcome_of(&row.item))
        .collect();
    if outcomes.is_empty() {
        return HashMap::new();
    }
    let carriers: Vec<Carrier<'_>> = rows
        .iter()
        .filter_map(|row| carrier_of(&row.item))
        .collect();

    let mut resolved = HashMap::new();
    for outcome in &outcomes {
        let Some(carrier) = carriers.iter().find(|c| closes(outcome, c)) else {
            continue;
        };
        resolved.insert(
            carrier.identity.clone(),
            CustomEventOutcome {
                decision: outcome.decision.clone(),
                decided_by: outcome.decided_by.clone(),
                summary: summary(outcome, carrier.payload),
                prompt: None,
                held: None,
            },
        );
    }
    resolved
}

/// The corpus, end to end: every attention.v2 `accept` draws, every `reject`
/// stays prose, what the client sends is exactly the corpus's
/// attention.answer.v2, and the corpus's outcomes close the card.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use crate::dto::CustomPayload;
    use crate::embedded::{embedded_suite_event, reconcile};
    use crate::gate_v2::OptionInput;
    use crate::item_view::ItemView;
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

    /// The blocked card: resume here, resume at Plan, archive.
    fn blocked() -> Value {
        cases(ATTENTION_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    /// The card that failed twice at the first stage: no earlier stage.
    fn failed() -> Value {
        cases(ATTENTION_V2_EVENT_TYPE, "accept")[1].1.clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    fn rendered(content: &Value) -> CustomEventView {
        let payload = embedded_payload(content).expect("a valid attention.v2");
        resolve_custom_event(
            default_registry(),
            Some(ATTENTION_V2_EVENT_TYPE),
            Some(&payload),
            body(content),
        )
    }

    fn decision_for(content: &Value) -> CustomEventDecision {
        match rendered(content) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    fn v2(content: &Value) -> AttentionV2Decision {
        decision_for(content).attention_v2.expect("drawn from v2")
    }

    fn ids(attention: &AttentionV2Decision) -> Vec<&str> {
        attention.options.iter().map(|o| o.id.as_str()).collect()
    }

    const ATTENTION_EVENT: &str = "$attentionEventId";

    #[test]
    fn every_accepted_attention_v2_is_drawn_from_v2() {
        for (why, content) in cases(ATTENTION_V2_EVENT_TYPE, "accept") {
            assert!(parse(&content, body(&content)).is_some(), "{why}");
            assert_eq!(
                embedded_suite_event(&content).map(|(t, _)| t),
                Some(ATTENTION_V2_EVENT_TYPE),
                "{why}"
            );
        }
    }

    #[test]
    fn every_rejected_attention_v2_stays_prose() {
        let rejects = cases(ATTENTION_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 6, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
            assert_eq!(embedded_suite_event(&content), None, "{why}");
        }
    }

    #[test]
    fn a_blocked_card_offers_its_options_by_meaning_with_their_input() {
        let decision = decision_for(&blocked());
        assert!(decision.options.is_empty(), "no plain reply answers it");
        assert_eq!(decision.subject, None);
        assert_eq!(
            decision.prompt,
            "\u{201C}Ship the release\u{201D} is waiting on you"
        );
        let attention = decision.attention_v2.unwrap();
        assert_eq!(attention.board_id, "brd_7c1f");
        assert_eq!(attention.card_id, "crd_6b40");
        assert_eq!(attention.wait_id, "wait_0123456789abcdef");
        assert_eq!(attention.card_title, "Ship the release");
        assert_eq!(attention.stage_name, "Build");
        assert_eq!(attention.reason, AttentionReason::Blocked);
        assert_eq!(attention.reason_text, "Blocked");
        assert_eq!(attention.summary.as_deref(), Some("Staging DB down"));
        assert_eq!(
            attention.detail.as_deref(),
            Some("the staging database is down")
        );
        assert_eq!(attention.failure_count, None);
        assert_eq!(ids(&attention), [RESUME_HERE, "resume_at:plan", ARCHIVE]);
        use GateOptionMeaning::{Approve, Choose, Reject};
        let meanings: Vec<GateOptionMeaning> =
            attention.options.iter().map(|o| o.meaning).collect();
        assert_eq!(meanings, [Approve, Choose, Reject]);
        let instruction = Some(OptionInput {
            kind: OptionInputKind::Text,
            label: "What should the next attempt do?".into(),
            required: true,
            max_length: 2730,
        });
        assert_eq!(attention.options[0].input, instruction);
        assert_eq!(attention.options[1].input, instruction);
        assert_eq!(attention.options[2].input, None);
    }

    #[test]
    fn the_card_says_its_reason_in_words_and_links_the_card() {
        match rendered(&blocked()) {
            CustomEventView::Rendered {
                fields,
                reasoning,
                link,
                ..
            } => {
                let drawn: Vec<(&str, &str)> = fields
                    .iter()
                    .map(|f| (f.label.as_str(), f.value.as_str()))
                    .collect();
                assert_eq!(
                    drawn,
                    [
                        ("Card", "Ship the release"),
                        ("Stage", "Build"),
                        ("Why", "Blocked"),
                        ("Summary", "Staging DB down"),
                    ]
                );
                assert_eq!(reasoning.as_deref(), Some("the staging database is down"));
                assert_eq!(
                    link.as_deref(),
                    Some("https://superpipeline.dev/b/brd_7c1f/c/crd_6b40")
                );
            }
            other => panic!("expected a card, got {other:?}"),
        }
        // Failed twice, no summary, no link.
        let attention = v2(&failed());
        assert_eq!(attention.reason, AttentionReason::RepeatedFailure);
        assert_eq!(attention.reason_text, "Failed twice");
        assert_eq!(attention.failure_count, Some(2));
        assert_eq!(attention.summary, None);
        assert_eq!(ids(&attention), [RESUME_HERE, ARCHIVE]);
        match rendered(&failed()) {
            CustomEventView::Rendered { link, .. } => assert_eq!(link, None),
            other => panic!("expected a card, got {other:?}"),
        }
    }

    #[test]
    fn reasons_read_as_words() {
        use AttentionReason::{Blocked, NotAuthorised, RepeatedFailure};
        assert_eq!(reason_words(RepeatedFailure, Some(1)), "Failed once");
        assert_eq!(reason_words(RepeatedFailure, Some(2)), "Failed twice");
        assert_eq!(reason_words(RepeatedFailure, Some(5)), "Failed 5 times");
        assert_eq!(reason_words(RepeatedFailure, None), "Failed repeatedly");
        assert_eq!(reason_words(Blocked, Some(3)), "Blocked");
        assert_eq!(reason_words(NotAuthorised, None), "Not authorised");
        let mut content = blocked();
        content["reason"] = json!("not-authorised");
        assert_eq!(v2(&content).reason_text, "Not authorised");
    }

    #[test]
    fn an_unknown_option_meaning_or_reason_stays_prose() {
        let mut meaning = blocked();
        meaning["options"][2]["meaning"] = json!("defer");
        let mut wrong = blocked();
        wrong["options"][0]["meaning"] = json!("choose");
        let mut unknown = blocked();
        unknown["options"].as_array_mut().unwrap().push(json!({
            "id": "skip", "label": "Skip", "meaning": "defer", "needs_comment": false
        }));
        let mut no_stage = blocked();
        no_stage["options"][1]["id"] = json!("resume_at:");
        let mut reason = blocked();
        reason["reason"] = json!("tired");
        let mut absent = blocked();
        absent.as_object_mut().unwrap().remove("detail");
        let mut no_options = blocked();
        no_options["options"] = json!([]);
        let mut archive_input = blocked();
        archive_input["options"][2]["input"] = blocked()["options"][0]["input"].clone();
        for (why, content) in [
            ("archive meaning defer", meaning),
            ("resume here meaning choose", wrong),
            ("an option the board has no call for", unknown),
            ("resume at no stage", no_stage),
            ("a reason this build does not know", reason),
            ("detail absent rather than null", absent),
            ("no options", no_options),
            ("an archive asking for input", archive_input),
        ] {
            assert!(embedded_payload(&content).is_none(), "{why}");
            assert_eq!(embedded_suite_event(&content), None, "{why}");
        }
    }

    #[test]
    fn the_body_is_stripped_and_the_identity_names_the_wait() {
        let payload = embedded_payload(&blocked()).unwrap();
        assert!(payload.get("body").is_none());
        assert_eq!(
            crate::embedded::decision_identity(ATTENTION_V2_EVENT_TYPE, &payload).as_deref(),
            Some("attention:brd_7c1f:crd_6b40:wait_0123456789abcdef")
        );
    }

    #[test]
    fn a_message_without_the_marker_is_not_a_waiting_card() {
        let mut content = blocked();
        content.as_object_mut().unwrap().remove("suite_event_type");
        assert!(embedded_payload(&content).is_none());
        assert_eq!(embedded_suite_event(&content), None);
    }

    // ---- attention.answer.v2 ----------------------------------------------

    #[test]
    fn the_answers_are_exactly_the_corpus_answers() {
        let accepts = cases(ATTENTION_ANSWER_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 3, "the corpus grew; read the new case");
        let attention = v2(&blocked());
        assert_eq!(
            answer_request(
                &attention,
                RESUME_HERE,
                Some("  The database is back; run the migrations again.\n"),
                ATTENTION_EVENT
            )
            .unwrap(),
            accepts[0].1,
            "{}",
            accepts[0].0
        );
        assert_eq!(
            answer_request(
                &attention,
                "resume_at:plan",
                Some("Re-plan around the outage."),
                ATTENTION_EVENT
            )
            .unwrap(),
            accepts[1].1,
            "{}",
            accepts[1].0
        );
        assert_eq!(
            answer_request(&attention, ARCHIVE, None, ATTENTION_EVENT).unwrap(),
            accepts[2].1,
            "{}",
            accepts[2].0
        );
    }

    #[test]
    fn every_rejected_answer_is_refused_or_never_what_the_client_sends() {
        let rejects = cases(ATTENTION_ANSWER_V2_SUITE_TYPE, "reject");
        assert_eq!(rejects.len(), 6, "the corpus grew; read the new case");
        let attention = v2(&blocked());
        for (why, content) in &rejects {
            let option = content["option_id"].as_str().unwrap();
            let text = content.get("text").and_then(Value::as_str);
            match answer_request(&attention, option, text, ATTENTION_EVENT) {
                // The wait and the reference are copied, never computed: a
                // different wait, a quoted reply or another message's
                // reference is never what goes out.
                Ok(sent) => {
                    assert_ne!(&sent, content, "{why}");
                    assert_eq!(sent["wait_id"], "wait_0123456789abcdef", "{why}");
                    assert_eq!(
                        sent["m.relates_to"],
                        json!({ "rel_type": "m.reference", "event_id": ATTENTION_EVENT }),
                        "{why}"
                    );
                }
                // A resume with no instruction, one too long, or a forward
                // move the card did not offer.
                Err(_) => assert!(
                    why.contains("no instruction")
                        || why.contains("longer than")
                        || why.contains("forward move"),
                    "{why}"
                ),
            }
        }
    }

    #[test]
    fn a_resume_needs_its_instruction_within_its_limit() {
        let attention = v2(&blocked());
        for option in [RESUME_HERE, "resume_at:plan"] {
            for text in [None, Some(""), Some("   \n")] {
                let err = answer_request(&attention, option, text, ATTENTION_EVENT)
                    .expect_err("a required instruction sent empty");
                assert!(
                    matches!(&err, CoreError::Protocol(m) if m.contains("needs an answer")),
                    "{option}: {err:?}"
                );
            }
            let at = "é".repeat(2730);
            assert!(answer_request(&attention, option, Some(&at), ATTENTION_EVENT).is_ok());
            let past = "é".repeat(2731);
            let err = answer_request(&attention, option, Some(&past), ATTENTION_EVENT)
                .expect_err("over max_length");
            assert!(matches!(&err, CoreError::Protocol(m) if m.contains("at most 2730")));
        }
    }

    #[test]
    fn a_resume_whose_input_a_host_removed_cannot_be_sent() {
        let mut forged = v2(&blocked());
        forged.options[0].input = None;
        assert!(answer_request(&forged, RESUME_HERE, Some("go"), ATTENTION_EVENT).is_err());
        let mut optional = v2(&blocked());
        if let Some(input) = optional.options[0].input.as_mut() {
            input.required = false;
        }
        assert!(answer_request(&optional, RESUME_HERE, None, ATTENTION_EVENT).is_err());
    }

    #[test]
    fn archive_sends_no_text_and_an_answer_must_reference_the_card() {
        let attention = v2(&blocked());
        let sent = answer_request(&attention, ARCHIVE, Some("stale"), ATTENTION_EVENT).unwrap();
        assert!(sent.get("text").is_none());
        assert_eq!(sent["body"], "Archive");
        assert!(
            sent.get("schema_version").is_none(),
            "the corpus carries none"
        );
        assert!(answer_request(&attention, ARCHIVE, None, "not-an-event").is_err());
        assert!(answer_request(&attention, "move_to:ship", None, ATTENTION_EVENT).is_err());
    }

    // ---- attention.outcome.v1 ---------------------------------------------

    const HUB: &str = "@board_speaker:hs";

    fn item(id: &str, kind: &str, msgtype: Option<&str>, sender: &str) -> TimelineItemDto {
        crate::timeline::project_item_parts(
            id,
            Some(&format!("${id}")),
            kind,
            msgtype,
            None,
            Some(sender),
            None,
            None,
            false,
            Some("A card is waiting on you"),
            None,
            None,
            None,
            Some(1),
            false,
            None,
            None,
            false,
            Vec::new(),
            Vec::new(),
        )
    }

    fn row(
        id: &str,
        kind: &str,
        msgtype: Option<&str>,
        sender: &str,
        detail: &str,
        payload: Value,
    ) -> TimelineRow {
        let mut it = item(id, kind, msgtype, sender);
        it.detail = Some(detail.into());
        it.custom_payload = Some(CustomPayload(payload));
        TimelineRow::new(it)
    }

    /// The blocked card's prose message. Its event id is
    /// `$attentionEventId`, the corpus outcome's reference.
    fn card() -> TimelineRow {
        row(
            "attentionEventId",
            "message",
            Some("m.text"),
            HUB,
            ATTENTION_V2_EVENT_TYPE,
            embedded_payload(&blocked()).unwrap(),
        )
    }

    fn outcome(sender: &str, content: Value) -> TimelineRow {
        row(
            "o",
            "customMessage",
            None,
            sender,
            ATTENTION_OUTCOME_EVENT_TYPE,
            content,
        )
    }

    fn corpus_outcome(index: usize) -> Value {
        cases(ATTENTION_OUTCOME_EVENT_TYPE, "accept")[index]
            .1
            .clone()
    }

    fn state(row: &TimelineRow) -> (bool, Option<CustomEventOutcome>) {
        match &row.view {
            ItemView::CustomEvent {
                view:
                    CustomEventView::Rendered {
                        decision, outcome, ..
                    },
                ..
            } => (decision.is_some(), outcome.clone()),
            other => panic!("expected a card, got {other:?}"),
        }
    }

    fn receipt(rows: &mut [TimelineRow]) -> Option<String> {
        reconcile(rows);
        let (buttons, outcome) = state(&rows[0]);
        match outcome {
            Some(outcome) => {
                assert!(!buttons, "a closed card draws no buttons");
                Some(outcome.summary)
            }
            None => {
                assert!(buttons, "an open card keeps its buttons");
                None
            }
        }
    }

    #[test]
    fn each_corpus_outcome_closes_the_card_in_words() {
        for (index, said) in [
            (0, "Resumed by fixture-person"),
            (1, "Resumed at Plan by fixture-person"),
            (2, "Archived by fixture-person"),
            (3, "Settled on the board"),
        ] {
            let mut rows = vec![card(), outcome(HUB, corpus_outcome(index))];
            assert_eq!(receipt(&mut rows).as_deref(), Some(said), "{said}");
            // The outcome itself is never drawn.
            assert_eq!(rows[1].view, ItemView::None);
        }
    }

    #[test]
    fn the_outcome_may_arrive_first() {
        let mut rows = vec![outcome(HUB, corpus_outcome(2)), card()];
        reconcile(&mut rows);
        let (buttons, outcome) = state(&rows[1]);
        assert!(!buttons);
        assert_eq!(
            outcome.map(|o| o.summary).as_deref(),
            Some("Archived by fixture-person")
        );
    }

    #[test]
    fn someone_elses_outcome_closes_nothing() {
        let mut rows = vec![card(), outcome("@mallory:hs", corpus_outcome(0))];
        assert_eq!(receipt(&mut rows), None);
    }

    #[test]
    fn an_outcome_for_another_message_or_wait_closes_nothing() {
        let mut elsewhere = corpus_outcome(0);
        elsewhere["m.relates_to"]["event_id"] = json!("$another");
        let mut quoted = corpus_outcome(0);
        quoted["m.relates_to"]["rel_type"] = json!("m.in_reply_to");
        let mut wait = corpus_outcome(0);
        wait["wait_id"] = json!("wait_ffffffffffffffff");
        let mut card_id = corpus_outcome(0);
        card_id["card_id"] = json!("crd_other");
        for (why, content) in [
            ("another message", elsewhere),
            ("a quoted reply", quoted),
            ("another wait", wait),
            ("another card", card_id),
        ] {
            let mut rows = vec![card(), outcome(HUB, content)];
            assert_eq!(receipt(&mut rows), None, "{why}");
        }
    }

    #[test]
    fn every_corpus_outcome_is_read_and_every_reject_closes_nothing() {
        for (why, content) in cases(ATTENTION_OUTCOME_EVENT_TYPE, "accept") {
            assert!(outcome_of(&outcome(HUB, content).item).is_some(), "{why}");
        }
        let rejects = cases(ATTENTION_OUTCOME_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 4, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(
                outcome_of(&outcome(HUB, content.clone()).item).is_none(),
                "{why}"
            );
            let mut rows = vec![card(), outcome(HUB, content)];
            assert_eq!(receipt(&mut rows), None, "{why}");
        }
    }

    #[test]
    fn a_gate_outcome_does_not_close_a_waiting_card() {
        let gate_outcome = json!({
            "suite_event_type": crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            "board_id": "brd_7c1f",
            "decision": "approve",
            "decided_by": "fixture-person",
            "m.relates_to": { "rel_type": "m.reference", "event_id": ATTENTION_EVENT }
        });
        let mut rows = vec![
            card(),
            row(
                "g",
                "customMessage",
                None,
                HUB,
                crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
                gate_outcome,
            ),
        ];
        assert_eq!(receipt(&mut rows), None);
    }

    #[test]
    fn a_resume_at_a_stage_the_card_did_not_label_names_its_key() {
        let mut content = corpus_outcome(1);
        content["decision"] = json!("resume_at:design");
        let mut rows = vec![card(), outcome(HUB, content)];
        assert_eq!(
            receipt(&mut rows).as_deref(),
            Some("Resumed at design by fixture-person")
        );
    }
}
