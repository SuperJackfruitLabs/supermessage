//! gate.v2 — a superpipeline gate whose options and subject come from the gate.
//!
//! charter → `decisions/2026-11-07-gate-v2-is-the-decision-envelope.md`. The
//! hub puts these fields on the same prose `m.room.message` that carries the
//! v1 card under `dev.superpipeline.gate` (`crate::embedded`), marked with
//! `suite_event_type: "dev.superpipeline.gate.v2"`. The wire is pinned by
//! `fixtures/ecosystem-identity/matrix_gate_events.json`, vendored from
//! AgentPod byte for byte and read by the tests below.
//!
//! What v2 changes for this client, and where each rule lives:
//!
//! - **No list of valid ids.** v1 filters options to superpipeline's three
//!   `GateDecision`s (`custom_events::GATE_OPTION_IDS`). A v2 gate says what
//!   each of its options *means* — `approve`, `reject`, `revise`, `choose`,
//!   `defer` — and a host draws by meaning, never by id. superpipeline is the
//!   only judge of an id; this client only refuses an id the gate it drew did
//!   not offer.
//! - **A subject and its preview.** A gate bound to an approval subject (an X
//!   post today) carries the exact bytes being approved as preview blocks. A
//!   client that cannot draw every block, or is told the preview is
//!   incomplete, must not offer an approving option. That rule is applied
//!   **here**, in [`render`]: an approving option the reader could not see the
//!   subject of never reaches a host, so no host can draw it by mistake.
//! - **The answer carries the binding.** `decision.v2` copies the subject's id
//!   and digest from the gate the reader saw ([`decision_request`]).
//!
//! A message whose v2 fields do not validate is read exactly as before: the
//! embedded v1 card. [`parse`] is the one place that decides which.
//!
//! Everything here is room content, so it is hostile: fields are read one
//! level at a time, never coerced, and every string a host draws is bounded.

use serde_json::Value;

use crate::custom_events::{
    bound_text, safe_link, safe_string_field, safe_url, CustomEventDecision,
    CustomEventDecisionOption, CustomEventField, CustomEventRenderResult, CustomEventRenderer,
    GATE_OPTION_IDS,
};
use crate::error::{CoreError, CoreResult};

/// The `suite_event_type` marker on a gate.v2 prose message, and the event
/// type its card is registered under.
pub const GATE_V2_EVENT_TYPE: &str = "dev.superpipeline.gate.v2";

/// The `suite_event_type` of the answer to a gate.v2.
pub const GATE_DECISION_V2_SUITE_TYPE: &str = "dev.superpipeline.gate.decision.v2";

/// How large a gate.v2's payload may be on a timeline row.
///
/// Four times `timeline::CUSTOM_PAYLOAD_MAX_BYTES`, and deliberately: the
/// preview is the exact post the reader approves, and the hub bounds it at
/// 16,000 bytes of JSON-encoded strings (`PREVIEW_TOTAL_BYTES` in its
/// `gate-wire.ts`). The prose body and the v1 card ride on the same message
/// and are stripped before this is measured ([`embedded_payload`]). A payload
/// over this falls back to the v1 card rather than drawing nothing.
pub const GATE_V2_PAYLOAD_MAX_BYTES: usize = 32 * 1024;

/// The hub's own per-block bounds (`gate-wire.ts`). A block past them was not
/// sent by a hub that follows the contract, so it is not drawn — and the
/// preview is then incomplete.
const PREVIEW_TEXT_MAX_CHARS: usize = 4_000;
const PREVIEW_FIELD_MAX_CHARS: usize = 200;
const PREVIEW_MAX_BLOCKS: usize = 20;

/// How many options a v2 card draws. The publishing gate has four; a gate
/// offering more than a person can weigh at a glance falls back to v1.
const OPTIONS_MAX: usize = 8;

/// An option's label is a button.
const OPTION_LABEL_MAX_CHARS: usize = 60;

const FIELD_VALUE_MAX_CHARS: usize = 300;
const FIELD_LABEL_MAX_CHARS: usize = 60;
const REASONING_MAX_CHARS: usize = 4_000;

/// What the card says when it withholds every approving option.
///
/// Worded here so no host words it differently. It names the way forward
/// (the card's link), because a card that silently has no Approve button
/// reads as a bug.
pub const APPROVALS_WITHHELD_NOTE: &str =
    "This card can't show everything you would be approving. Open the card to approve it.";

/// What a gate.v2 option means. A host draws by this, never by id.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum GateOptionMeaning {
    /// The main action. A publishing gate has two (automatic and manual).
    Approve,
    /// Destructive.
    Reject,
    /// Sends the work back; always carries a comment in practice.
    Revise,
    /// One of several neutral choices.
    Choose,
    /// Neutral: not now.
    Defer,
}

impl GateOptionMeaning {
    fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "approve" => Self::Approve,
            "reject" => Self::Reject,
            "revise" => Self::Revise,
            "choose" => Self::Choose,
            "defer" => Self::Defer,
            _ => return None,
        })
    }
}

/// One answer a gate.v2 offers.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateOptionV2 {
    /// Handed back verbatim; never drawn, never cut.
    pub id: String,
    /// Free text from the board, bounded. Display only.
    pub label: String,
    pub meaning: GateOptionMeaning,
    /// The host must ask for text, and the core refuses to send this option
    /// without it.
    pub needs_comment: bool,
}

/// One block of a subject's preview — what the reader must see before
/// approving. Every string is text to draw verbatim: no markdown, no links
/// detected in it, line breaks kept.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "type")]
pub enum GatePreviewBlock {
    /// The exact bytes of the thing approved — an X post — character for
    /// character.
    Text {
        label: Option<String>,
        text: String,
    },
    Field {
        label: String,
        value: String,
    },
    /// `url` passed `custom_events::safe_url`: `https://`, printable ASCII.
    Link {
        label: String,
        url: String,
    },
}

/// The subject a decision.v2 is bound to, copied back from the gate shown.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateSubjectBinding {
    pub id: String,
    pub digest: String,
}

/// A gate.v2 as a host draws and answers it. Rides on
/// `CustomEventDecision::gate_v2`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateV2Decision {
    pub gate_id: String,
    /// What this client may offer, in the gate's order. Approving options
    /// are already gone when the preview could not be shown in full.
    pub options: Vec<GateOptionV2>,
    /// Empty for a gate bound to no subject.
    pub preview: Vec<GatePreviewBlock>,
    /// [`APPROVALS_WITHHELD_NOTE`] when approving options were withheld.
    pub approvals_withheld: Option<String>,
    /// Present on a subject-bound gate; copied into the answer.
    pub subject: Option<GateSubjectBinding>,
}

/// A subject as read off the wire.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Subject {
    binding: GateSubjectBinding,
    preview: Vec<GatePreviewBlock>,
    /// Every block drawable, none dropped, and the board said it was whole.
    complete: bool,
}

/// A gate.v2 that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedGateV2 {
    gate_id: String,
    card_title: String,
    stage_key: Option<String>,
    prompt: Option<String>,
    handoff_summary: Option<String>,
    deep_link: Option<String>,
    options: Vec<GateOptionV2>,
    subject: Option<Subject>,
}

/// A non-empty string at `value[key]`, unbounded — an identifier, compared
/// and handed back, never drawn. The payload is bounded as a whole.
fn id_field(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)?
        .as_str()
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

/// A string at `value[key]` that fits `max_chars` exactly, or `None` —
/// never cut, because a preview shown cut is not the thing approved.
fn exact_string(value: &Value, key: &str, max_chars: usize) -> Option<String> {
    let s = value.get(key)?.as_str()?;
    (s.chars().count() <= max_chars).then(|| s.to_string())
}

/// One preview block, or `None` when this client cannot draw it as sent.
fn parse_block(raw: &Value) -> Option<GatePreviewBlock> {
    match raw.get("type")?.as_str()? {
        "text" => {
            let label = match raw.get("label") {
                None | Some(Value::Null) => None,
                Some(_) => Some(exact_string(raw, "label", PREVIEW_FIELD_MAX_CHARS)?),
            };
            Some(GatePreviewBlock::Text {
                label,
                text: exact_string(raw, "text", PREVIEW_TEXT_MAX_CHARS)?,
            })
        }
        "field" => Some(GatePreviewBlock::Field {
            label: exact_string(raw, "label", PREVIEW_FIELD_MAX_CHARS)?,
            value: exact_string(raw, "value", PREVIEW_FIELD_MAX_CHARS)?,
        }),
        "link" => {
            let url = raw.get("url")?.as_str()?;
            // Exactly as sent, or not at all: `safe_url` trims, and a link
            // drawn other than as sent is not the link approved.
            let safe = safe_url(url).filter(|safe| safe == url)?;
            Some(GatePreviewBlock::Link {
                label: exact_string(raw, "label", PREVIEW_FIELD_MAX_CHARS)?,
                url: safe,
            })
        }
        // A kind of block this build has never heard of. It cannot be drawn,
        // so the preview is incomplete — the case the charter names.
        _ => None,
    }
}

/// `content.subject`: `Ok(None)` when absent, `Err(())` when present and not
/// a subject an answer could be bound to (no id or no digest).
fn parse_subject(content: &Value) -> Result<Option<Subject>, ()> {
    let raw = match content.get("subject") {
        None | Some(Value::Null) => return Ok(None),
        Some(raw) => raw,
    };
    let (Some(id), Some(digest)) = (id_field(raw, "id"), id_field(raw, "digest")) else {
        return Err(());
    };
    // Only an explicit `true` counts. Absent is a board that did not say.
    let mut complete = raw.get("preview_complete").and_then(Value::as_bool) == Some(true);
    let mut preview = Vec::new();
    match raw.get("preview").and_then(Value::as_array) {
        Some(blocks) => {
            for block in blocks {
                if preview.len() == PREVIEW_MAX_BLOCKS {
                    complete = false;
                    break;
                }
                match parse_block(block) {
                    Some(block) => preview.push(block),
                    None => complete = false,
                }
            }
        }
        None => complete = false,
    }
    if preview.is_empty() {
        // Nothing shown is not a preview, whatever the flag says.
        complete = false;
    }
    Ok(Some(Subject {
        binding: GateSubjectBinding { id, digest },
        preview,
        complete,
    }))
}

/// The gate's options, or `None` when they are not v2 options a client can
/// honestly answer: empty, too many, an option with no id, no meaning a
/// client knows, `needs_comment` that is not a boolean, or two sharing an id.
fn parse_options(content: &Value) -> Option<Vec<GateOptionV2>> {
    let raw = content.get("options")?.as_array()?;
    if raw.is_empty() || raw.len() > OPTIONS_MAX {
        return None;
    }
    let mut options: Vec<GateOptionV2> = Vec::with_capacity(raw.len());
    for entry in raw {
        let id = id_field(entry, "id")?;
        let meaning = GateOptionMeaning::parse(entry.get("meaning")?.as_str()?)?;
        let needs_comment = entry.get("needs_comment")?.as_bool()?;
        if options.iter().any(|o| o.id == id) {
            // Answering is by id: two entries sharing one are ambiguous.
            return None;
        }
        let label = safe_string_field(entry, "label", OPTION_LABEL_MAX_CHARS)
            .filter(|l| !l.trim().is_empty())
            .unwrap_or_else(|| bound_text(&id, OPTION_LABEL_MAX_CHARS));
        options.push(GateOptionV2 {
            id,
            label,
            meaning,
            needs_comment,
        });
    }
    Some(options)
}

/// Read a gate.v2 from a message's `content`, or `None` when it is not one
/// this client may draw from v2 — in which case the v1 card is read instead.
///
/// `body` is the message's own; a gate with none is one a stock client would
/// show as nothing (the corpus refuses it).
pub(crate) fn parse(content: &Value, body: Option<&str>) -> Option<ParsedGateV2> {
    if content.get("suite_event_type")?.as_str()? != GATE_V2_EVENT_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let gate_id = id_field(content, "gate_id")?;
    let card_title = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)?;
    let options = parse_options(content)?;
    let subject = parse_subject(content).ok()?;
    Some(ParsedGateV2 {
        gate_id,
        card_title,
        stage_key: safe_string_field(content, "stage_key", FIELD_LABEL_MAX_CHARS),
        prompt: safe_string_field(content, "prompt", FIELD_VALUE_MAX_CHARS),
        handoff_summary: safe_string_field(content, "handoff_summary", REASONING_MAX_CHARS),
        deep_link: safe_link(content, "deep_link"),
        options,
        subject,
    })
}

/// The payload a gate.v2 message rides on its timeline row, or `None` when
/// the message is not a valid gate.v2 (or too large to carry one).
///
/// The message's `content` without what the row already has or will not
/// read: the `body` (the row's own), the embedded v1 card, and — for a
/// subject-bound gate — the handoff summary, which is the preview's text
/// again.
pub fn embedded_payload(content: &Value) -> Option<Value> {
    let body = content.get("body").and_then(Value::as_str);
    let parsed = parse(content, body)?;
    let mut payload = content.as_object()?.clone();
    payload.remove("body");
    payload.remove(crate::embedded::EMBEDDED_GATE_KEY);
    if parsed.subject.is_some() {
        payload.remove("handoff_summary");
    }
    let payload = Value::Object(payload);
    let size = serde_json::to_string(&payload)
        .map(|s| s.len())
        .unwrap_or(usize::MAX);
    (size <= GATE_V2_PAYLOAD_MAX_BYTES).then_some(payload)
}

impl ParsedGateV2 {
    /// Whether this client may offer an approving option: a gate bound to no
    /// subject, or one whose whole preview it can draw.
    fn approvable(&self) -> bool {
        self.subject.as_ref().is_none_or(|s| s.complete)
    }

    /// The card a host draws.
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
            approvals_withheld: withheld.then(|| APPROVALS_WITHHELD_NOTE.to_string()),
            subject: self.subject.as_ref().map(|s| s.binding.clone()),
        }
    }

    /// The options a host that knows nothing of v2 may offer and answer with
    /// a decision.v1: superpipeline's three ids — what the v1 card beside it
    /// offers — and never an approval of a subject, which needs the preview
    /// such a host does not draw.
    fn legacy_options(&self) -> Vec<CustomEventDecisionOption> {
        self.options
            .iter()
            .filter(|o| GATE_OPTION_IDS.contains(&o.id.as_str()))
            .filter(|o| self.subject.is_none() || o.meaning != GateOptionMeaning::Approve)
            .map(|o| CustomEventDecisionOption {
                id: o.id.clone(),
                label: o.label.clone(),
            })
            .collect()
    }
}

/// Draws a gate.v2 — registered under [`GATE_V2_EVENT_TYPE`].
pub struct GateV2Renderer;

impl CustomEventRenderer for GateV2Renderer {
    fn event_type(&self) -> &str {
        GATE_V2_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Approval"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(gate) = parse(content, body) else {
            return CustomEventRenderResult::default();
        };
        let mut fields = vec![CustomEventField {
            label: "Card".into(),
            value: gate.card_title.clone(),
        }];
        if let Some(stage) = &gate.stage_key {
            fields.push(CustomEventField {
                label: "Stage".into(),
                value: stage.clone(),
            });
        }
        CustomEventRenderResult {
            fields,
            // A subject-bound gate's summary is its preview's text, drawn
            // above as the preview itself.
            reasoning: gate
                .subject
                .is_none()
                .then(|| gate.handoff_summary.clone())
                .flatten(),
            link: gate.deep_link.clone(),
            decision: Some(CustomEventDecision {
                prompt: gate
                    .prompt
                    .clone()
                    .unwrap_or_else(|| format!("Approve \"{}\"?", gate.card_title)),
                options: gate.legacy_options(),
                subject: Some(gate.gate_id.clone()),
                gate_v2: Some(gate.decision()),
            }),
        }
    }
}

/// The sentence a decision.v2 leaves in the room.
///
/// The past tense of what the option *means*, so the record reads the same
/// whatever a board labels its buttons — plus, when the label qualifies the
/// verb after an em dash ("Approve — post automatically"), that
/// qualification, which is the difference between the two approvals a
/// publishing gate offers. A choice says what was chosen.
pub(crate) fn decision_body(option: &GateOptionV2) -> String {
    let verb = match option.meaning {
        GateOptionMeaning::Approve => "Approved",
        GateOptionMeaning::Reject => "Rejected",
        GateOptionMeaning::Revise => "Requested changes",
        GateOptionMeaning::Choose => "Chose",
        GateOptionMeaning::Defer => "Deferred",
    };
    let label = option.label.trim();
    let detail = match option.meaning {
        GateOptionMeaning::Choose => Some(label),
        _ => label.split_once(" — ").map(|(_, rest)| rest.trim()),
    }
    .filter(|d| !d.is_empty());
    match detail {
        Some(detail) => format!("{verb} — {detail}"),
        None => verb.to_string(),
    }
}

/// A decision.v2's `content`, validated against the gate the reader saw —
/// the whole of what goes on the wire.
///
/// Refuses, with a reason a host can show: an option the gate did not offer
/// (or that was withheld), an approval of a subject whose preview was not
/// shown in full, an option that needs a comment sent without one, and a
/// reference that is not an event id.
///
/// `comment` is always present — `null` when there is none — matching the
/// corpus's decision.v2 examples; `subject` is present exactly when the gate
/// was bound to one.
pub(crate) fn decision_request(
    gate: &GateV2Decision,
    option_id: &str,
    comment: Option<&str>,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if gate.gate_id.is_empty() {
        return Err(CoreError::Protocol("a decision needs a gate".into()));
    }
    let option = gate
        .options
        .iter()
        .find(|o| o.id == option_id)
        .ok_or_else(|| {
            CoreError::Protocol(format!("not an option this gate offers: {option_id}"))
        })?;
    if option.meaning == GateOptionMeaning::Approve && gate.approvals_withheld.is_some() {
        return Err(CoreError::Protocol(
            "this card could not show everything being approved".into(),
        ));
    }
    let comment = comment.filter(|c| !c.trim().is_empty());
    if option.needs_comment && comment.is_none() {
        return Err(CoreError::Protocol(format!(
            "\u{201C}{}\u{201D} needs a comment",
            option.label
        )));
    }
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    let mut content = serde_json::json!({
        "msgtype": "m.text",
        "body": decision_body(option),
        "schema_version": 1,
        "suite_event_type": GATE_DECISION_V2_SUITE_TYPE,
        "gate_id": gate.gate_id,
        "option_id": option.id,
        "comment": comment,
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    });
    if let Some(subject) = &gate.subject {
        content["subject"] = serde_json::json!({ "id": subject.id, "digest": subject.digest });
    }
    Ok(content)
}

/// The corpus, checked end to end: every gate.v2 `accept` draws from v2,
/// every `reject` falls back, and what the client sends for the corpus's
/// gate is exactly the corpus's decision.v2.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use serde_json::json;

    const CORPUS: &str =
        include_str!("../../../fixtures/ecosystem-identity/matrix_gate_events.json");

    fn corpus() -> Value {
        serde_json::from_str(CORPUS).expect("the corpus is JSON")
    }

    fn event(suite_type: &str) -> Value {
        corpus()["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type)
            .unwrap_or_else(|| panic!("the corpus pins {suite_type}"))
            .clone()
    }

    fn cases(suite_type: &str, which: &str) -> Vec<(String, Value)> {
        event(suite_type)[which]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| (c["why"].as_str().unwrap().to_string(), c["content"].clone()))
            .collect()
    }

    /// The publishing gate: subject-bound, four options.
    fn publishing() -> Value {
        cases(GATE_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    /// The ordinary gate: no subject.
    fn ordinary() -> Value {
        cases(GATE_V2_EVENT_TYPE, "accept")[1].1.clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    /// The card a host gets for `content`, through the production registry.
    fn decision_for(content: &Value) -> CustomEventDecision {
        let payload = embedded_payload(content).expect("a valid gate.v2");
        match resolve_custom_event(
            default_registry(),
            Some(GATE_V2_EVENT_TYPE),
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
        decision_for(content).gate_v2.expect("drawn from v2")
    }

    #[test]
    fn every_accepted_gate_v2_is_drawn_from_v2() {
        for (why, content) in cases(GATE_V2_EVENT_TYPE, "accept") {
            assert!(parse(&content, body(&content)).is_some(), "{why}");
            assert!(embedded_payload(&content).is_some(), "{why}");
        }
    }

    #[test]
    fn every_rejected_gate_v2_falls_back() {
        let rejects = cases(GATE_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
        }
    }

    #[test]
    fn the_publishing_gate_offers_four_options_by_meaning() {
        let gate = v2(&publishing());
        let drawn: Vec<(&str, &str, GateOptionMeaning, bool)> = gate
            .options
            .iter()
            .map(|o| (o.id.as_str(), o.label.as_str(), o.meaning, o.needs_comment))
            .collect();
        assert_eq!(
            drawn,
            [
                (
                    "approve_manual",
                    "Approve — I'll post it myself",
                    GateOptionMeaning::Approve,
                    false
                ),
                (
                    "approve_automatic",
                    "Approve — post automatically",
                    GateOptionMeaning::Approve,
                    false
                ),
                (
                    "request_changes",
                    "Request changes",
                    GateOptionMeaning::Revise,
                    true
                ),
                ("reject", "Reject", GateOptionMeaning::Reject, false),
            ]
        );
        assert_eq!(gate.approvals_withheld, None);
        assert_eq!(
            gate.subject,
            Some(GateSubjectBinding {
                id: "aps_2c9e71".into(),
                digest: "sha256:9b2f4e0c1a7d".into()
            })
        );
    }

    #[test]
    fn the_preview_text_is_the_post_character_for_character() {
        let gate = v2(&publishing());
        assert_eq!(
            gate.preview,
            [
                GatePreviewBlock::Text {
                    label: Some("Post".into()),
                    text: "Our next release ships today.\n\nThanks to everyone who tested it."
                        .into(),
                },
                GatePreviewBlock::Field {
                    label: "Account".into(),
                    value: "@fixture_account on X".into(),
                },
                GatePreviewBlock::Field {
                    label: "Approval expires".into(),
                    value: "2030-01-02T03:04:05Z".into(),
                },
            ]
        );
    }

    #[test]
    fn markdown_in_the_post_is_kept_not_interpreted() {
        let mut content = publishing();
        let post = "**not bold**\n  `two spaces`\n# not a heading\n<b>tag</b> _x_";
        content["subject"]["preview"][0]["text"] = json!(post);
        let GatePreviewBlock::Text { text, .. } = &v2(&content).preview[0] else {
            panic!("a text block");
        };
        assert_eq!(text, post);
    }

    fn approve_ids(gate: &GateV2Decision) -> Vec<&str> {
        gate.options
            .iter()
            .filter(|o| o.meaning == GateOptionMeaning::Approve)
            .map(|o| o.id.as_str())
            .collect()
    }

    fn assert_withheld(content: &Value, why: &str) {
        let decision = decision_for(content);
        let gate = decision.gate_v2.as_ref().unwrap();
        assert!(approve_ids(gate).is_empty(), "{why}: approval offered");
        assert_eq!(
            gate.approvals_withheld.as_deref(),
            Some(APPROVALS_WITHHELD_NOTE),
            "{why}"
        );
        // Revise and reject stay, and so does the link to the card.
        let rest: Vec<&str> = gate.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(rest, ["request_changes", "reject"], "{why}");
        assert!(
            !decision.options.iter().any(|o| o.id.starts_with("approve")),
            "{why}: the legacy list must not approve either"
        );
    }

    #[test]
    fn an_incomplete_preview_offers_no_approval() {
        let mut content = publishing();
        content["subject"]["preview_complete"] = json!(false);
        assert_withheld(&content, "preview_complete false");
    }

    #[test]
    fn a_preview_that_does_not_say_it_is_complete_offers_no_approval() {
        let mut content = publishing();
        content["subject"]
            .as_object_mut()
            .unwrap()
            .remove("preview_complete");
        assert_withheld(&content, "preview_complete absent");
    }

    #[test]
    fn a_block_this_client_cannot_draw_offers_no_approval() {
        let mut content = publishing();
        content["subject"]["preview"]
            .as_array_mut()
            .unwrap()
            .push(json!({ "type": "image", "label": "Card", "url": "https://x.example/a.png" }));
        assert_withheld(&content, "an unknown block type");
        // The blocks it can draw are still shown.
        assert_eq!(v2(&content).preview.len(), 3);
    }

    #[test]
    fn a_block_past_its_bounds_or_with_an_unsafe_link_offers_no_approval() {
        let mut long = publishing();
        long["subject"]["preview"][0]["text"] = json!("x".repeat(PREVIEW_TEXT_MAX_CHARS + 1));
        assert_withheld(&long, "a text block past 4000 characters");

        for url in [
            "javascript:alert(1)",
            "http://x.example",
            " https://x.example",
        ] {
            let mut link = publishing();
            link["subject"]["preview"]
                .as_array_mut()
                .unwrap()
                .push(json!({ "type": "link", "label": "Where", "url": url }));
            assert_withheld(&link, url);
        }
    }

    #[test]
    fn an_empty_or_missing_preview_offers_no_approval() {
        let mut empty = publishing();
        empty["subject"]["preview"] = json!([]);
        assert_withheld(&empty, "empty preview");
        let mut missing = publishing();
        missing["subject"]
            .as_object_mut()
            .unwrap()
            .remove("preview");
        assert_withheld(&missing, "missing preview");
    }

    #[test]
    fn a_safe_link_block_is_drawn_and_keeps_approval() {
        let mut content = publishing();
        content["subject"]["preview"]
            .as_array_mut()
            .unwrap()
            .push(json!({ "type": "link", "label": "Thread", "url": "https://x.example/t/1" }));
        let gate = v2(&content);
        assert_eq!(approve_ids(&gate), ["approve_manual", "approve_automatic"]);
        assert_eq!(
            gate.preview[3],
            GatePreviewBlock::Link {
                label: "Thread".into(),
                url: "https://x.example/t/1".into()
            }
        );
    }

    #[test]
    fn an_ordinary_gate_offers_approval_with_no_preview() {
        let decision = decision_for(&ordinary());
        let gate = decision.gate_v2.as_ref().unwrap();
        assert_eq!(approve_ids(gate), ["approve"]);
        assert!(gate.preview.is_empty());
        assert_eq!(gate.subject, None);
        assert_eq!(decision.subject.as_deref(), Some("gate_4e8b"));
    }

    #[test]
    fn a_host_without_v2_sees_only_what_a_decision_v1_can_answer() {
        // Desktop and the notification actions read `options` and answer with
        // decision.v1. For the publishing gate that is what the v1 card beside
        // it offers: no approval of a subject it cannot show.
        let ids =
            |d: &CustomEventDecision| d.options.iter().map(|o| o.id.clone()).collect::<Vec<_>>();
        assert_eq!(
            ids(&decision_for(&publishing())),
            ["request_changes", "reject"]
        );
        assert_eq!(
            ids(&decision_for(&ordinary())),
            ["approve", "request_changes", "reject"]
        );
        // A subject-bound gate whose approval is spelled with v1's own id is
        // still an approval of a subject such a host cannot show.
        let mut plain_id = publishing();
        plain_id["options"][0]["id"] = json!("approve");
        assert_eq!(ids(&decision_for(&plain_id)), ["request_changes", "reject"]);
    }

    #[test]
    fn labels_are_bounded_and_ids_are_not() {
        let mut content = ordinary();
        let id = format!("choose_{}", "a".repeat(150));
        content["options"][0] = json!({
            "id": id, "label": "L".repeat(500), "meaning": "choose", "needs_comment": false
        });
        let gate = v2(&content);
        assert_eq!(gate.options[0].id, id);
        assert_eq!(
            gate.options[0].label.chars().count(),
            OPTION_LABEL_MAX_CHARS + 1
        );
    }

    #[test]
    fn a_message_without_the_v2_marker_is_not_v2() {
        let mut content = ordinary();
        content["suite_event_type"] = json!("dev.superpipeline.gate.v1");
        assert!(embedded_payload(&content).is_none());
        content.as_object_mut().unwrap().remove("suite_event_type");
        assert!(embedded_payload(&content).is_none());
    }

    #[test]
    fn an_oversized_gate_falls_back_rather_than_vanishing() {
        let mut content = publishing();
        let blocks: Vec<Value> = (0..PREVIEW_MAX_BLOCKS)
            .map(|_| json!({ "type": "text", "text": "y".repeat(PREVIEW_TEXT_MAX_CHARS) }))
            .collect();
        content["subject"]["preview"] = json!(blocks);
        assert!(embedded_payload(&content).is_none());
    }

    #[test]
    fn the_payload_drops_what_the_row_already_has() {
        let mut content = publishing();
        content[crate::embedded::EMBEDDED_GATE_KEY] = json!({ "gate_id": "gate_8f3a" });
        let payload = embedded_payload(&content).unwrap();
        assert!(payload.get("body").is_none());
        assert!(payload.get(crate::embedded::EMBEDDED_GATE_KEY).is_none());
        assert!(payload.get("handoff_summary").is_none());
        assert!(embedded_payload(&ordinary())
            .unwrap()
            .get("handoff_summary")
            .is_some());
    }

    // ---- decision.v2 -------------------------------------------------------

    const GATE_EVENT: &str = "$gateEventId";

    #[test]
    fn the_answers_are_exactly_the_corpus_decisions() {
        let gate = v2(&publishing());
        let accepts = cases(GATE_DECISION_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 2, "the corpus grew; read the new case");
        assert_eq!(
            decision_request(&gate, "approve_automatic", None, GATE_EVENT).unwrap(),
            accepts[0].1,
            "{}",
            accepts[0].0
        );
        assert_eq!(
            decision_request(
                &gate,
                "request_changes",
                Some("Say which release."),
                GATE_EVENT
            )
            .unwrap(),
            accepts[1].1,
            "{}",
            accepts[1].0
        );
    }

    #[test]
    fn an_answer_to_a_subject_gate_always_carries_the_binding() {
        // Corpus reject: "an answer to a subject-bound gate with no subject
        // binding".
        let gate = v2(&publishing());
        for option in ["approve_manual", "approve_automatic", "reject"] {
            let content = decision_request(&gate, option, None, GATE_EVENT).unwrap();
            assert_eq!(
                content["subject"],
                json!({ "id": "aps_2c9e71", "digest": "sha256:9b2f4e0c1a7d" })
            );
        }
        // And an ordinary gate's answer carries none.
        let ordinary = v2(&ordinary());
        let content = decision_request(&ordinary, "approve", None, GATE_EVENT).unwrap();
        assert!(content.get("subject").is_none());
        assert_eq!(content["body"], "Approved");
    }

    #[test]
    fn an_option_that_needs_a_comment_is_refused_without_one() {
        // Corpus reject: "an option that needs a comment, without one".
        let gate = v2(&publishing());
        for comment in [None, Some(""), Some("  \n ")] {
            let err = decision_request(&gate, "request_changes", comment, GATE_EVENT)
                .expect_err("an empty revise must not reach the room");
            assert!(matches!(&err, CoreError::Protocol(m) if m.contains("needs a comment")));
        }
    }

    #[test]
    fn an_option_the_gate_did_not_offer_is_refused() {
        // Corpus reject: "an option the gate did not offer".
        let gate = v2(&publishing());
        let err = decision_request(&gate, "approve", None, GATE_EVENT).expect_err("not offered");
        assert!(matches!(&err, CoreError::Protocol(m) if m.contains("approve")));
    }

    #[test]
    fn a_withheld_approval_cannot_be_sent() {
        let mut content = publishing();
        content["subject"]["preview_complete"] = json!(false);
        let gate = v2(&content);
        assert!(decision_request(&gate, "approve_automatic", None, GATE_EVENT).is_err());
        // Even if a host put it back.
        let mut forged = gate.clone();
        forged.options.insert(
            0,
            GateOptionV2 {
                id: "approve_automatic".into(),
                label: "Approve".into(),
                meaning: GateOptionMeaning::Approve,
                needs_comment: false,
            },
        );
        assert!(decision_request(&forged, "approve_automatic", None, GATE_EVENT).is_err());
        // Revise and reject still go.
        assert!(decision_request(&gate, "reject", None, GATE_EVENT).is_ok());
    }

    #[test]
    fn an_answer_must_reference_the_gate_event() {
        // Corpus reject: "no m.relates_to".
        let gate = v2(&publishing());
        assert!(decision_request(&gate, "reject", None, "not-an-event").is_err());
        let content = decision_request(&gate, "reject", None, GATE_EVENT).unwrap();
        assert_eq!(
            content["m.relates_to"],
            json!({ "rel_type": "m.reference", "event_id": GATE_EVENT })
        );
    }

    #[test]
    fn the_sentence_says_what_the_option_means() {
        let option = |label: &str, meaning| GateOptionV2 {
            id: "x".into(),
            label: label.into(),
            meaning,
            needs_comment: false,
        };
        use GateOptionMeaning::*;
        assert_eq!(
            decision_body(&option("Approve — I'll post it myself", Approve)),
            "Approved — I'll post it myself"
        );
        assert_eq!(decision_body(&option("Ship it", Approve)), "Approved");
        assert_eq!(
            decision_body(&option("Send back", Revise)),
            "Requested changes"
        );
        assert_eq!(decision_body(&option("Nope", Reject)), "Rejected");
        assert_eq!(decision_body(&option("Later", Defer)), "Deferred");
        assert_eq!(decision_body(&option("Blue", Choose)), "Chose — Blue");
    }
}
