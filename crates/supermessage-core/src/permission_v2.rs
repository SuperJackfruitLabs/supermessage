//! permission.v2 — a harness permission in AgentPod's own decision envelope.
//!
//! charter → `decisions/2026-11-07-strict-decisions-and-permissions-bind-their-subject.md`
//! §5–6. A harness asking permission (ACP `session/request_permission`)
//! reaches its station room as one prose `m.room.message`. The hub has always
//! carried the v1 card on it under `dev.agentpod.permission`
//! (`crate::embedded`); it now also marks the message
//! `suite_event_type: "dev.agentpod.permission.v2"` and carries options that
//! say what they **mean** and how far they reach, and a `harness-permission/v1`
//! subject whose preview shows what the agent would do — the tool and its
//! kind, the command or raw input, each diff, the locations. The namespace
//! follows the judge: AgentPod judges a harness permission, so the event is
//! AgentPod's, not superpipeline's. The wire is pinned by
//! `fixtures/ecosystem-identity/matrix_gate_events.json`.
//!
//! What this module does, and where each rule lives:
//!
//! - [`parse`] decides whether the message is drawn from v2. Anything less —
//!   no marker, an option with no meaning, a subject of another schema, no
//!   request id — is read as the v1 card exactly as before.
//! - [`PermissionV2Renderer`] draws it, and withholds every allowing option
//!   unless the preview is complete and drawable (gate.v2's rule,
//!   `crate::gate_v2::offerable`).
//! - [`answer_request`] builds `dev.agentpod.permission.answer.v2`: the
//!   request, the option, the binding `{id, digest}` copied from what the
//!   reader was shown, and an `m.reference` to the prose message.
//! - [`resolved_permissions`] reads the hub's `dev.agentpod.permission.outcome.v1`
//!   and closes the card it references — v2 or v1 — with a receipt.
//!
//! Everything here is room content, so it is hostile: read one level at a
//! time, never coerced, every drawn string bounded.

use std::collections::HashMap;

use serde_json::Value;

use crate::custom_events::{
    bound_text, safe_string_field, CustomEventDecision, CustomEventDecisionOption,
    CustomEventField, CustomEventOutcome, CustomEventRenderResult, CustomEventRenderer,
    PERMISSION_REQUEST_EVENT_TYPE,
};
use crate::dto::{TimelineItemDto, TimelineRow};
use crate::error::{CoreError, CoreResult};
use crate::gate_v2::{
    id_field, offerable, parse_bound_subject, parse_scope_requires, GateOptionMeaning,
    GateOptionV2, OptionScope, Subject, SubjectV2, HARNESS_PERMISSION_SCHEMA, OPTIONS_MAX,
    OPTION_LABEL_MAX_CHARS,
};

/// The `suite_event_type` marker on a permission.v2 prose message, and the
/// event type its card is registered under.
pub const PERMISSION_V2_EVENT_TYPE: &str = "dev.agentpod.permission.v2";

/// The `suite_event_type` of the answer to a permission.v2.
pub const PERMISSION_ANSWER_V2_SUITE_TYPE: &str = "dev.agentpod.permission.answer.v2";

/// The receipt that closes a permission card — the custom event's Matrix
/// type, repeated as `suite_event_type` in its content.
pub const PERMISSION_OUTCOME_EVENT_TYPE: &str = "dev.agentpod.permission.outcome.v1";

/// What the card says when it withholds every allowing option.
///
/// Not gate.v2's note: a permission has no board card to open. The hub's own
/// refusal says to answer in the console, so the card does too.
pub const PERMISSION_APPROVALS_WITHHELD_NOTE: &str =
    "This card can't show everything the agent would do. Answer it in the console to allow it.";

const TITLE_MAX_CHARS: usize = 300;
const TOOL_KIND_MAX_CHARS: usize = 60;
/// How long a `decided_by` may be on a receipt. A handle, not a paragraph.
const DECIDED_BY_MAX_CHARS: usize = 60;

/// A permission.v2 as a host draws and answers it. Rides on
/// `CustomEventDecision::permission_v2`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct PermissionV2Decision {
    /// `<session_id>:<request_seq>`, handed back verbatim in the answer.
    pub request_id: String,
    /// What the agent wants to do, bounded.
    pub title: String,
    /// ACP's tool kind (`execute`, `edit`, …), when the harness said.
    pub tool_kind: Option<String>,
    /// In the harness's order, by meaning and scope. Allowing options are
    /// already gone when the preview could not be drawn in full.
    pub options: Vec<GateOptionV2>,
    /// What the agent would do, drawn verbatim before the options, and the
    /// binding the answer copies back.
    pub subject: SubjectV2,
    /// [`PERMISSION_APPROVALS_WITHHELD_NOTE`] when allowing options were
    /// withheld.
    pub approvals_withheld: Option<String>,
}

/// A permission.v2 that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedPermissionV2 {
    request_id: String,
    title: String,
    tool_kind: Option<String>,
    options: Vec<GateOptionV2>,
    subject: Subject,
}

/// The harness's options, or `None` when they are not options a client can
/// honestly answer: none, too many, an option with no id, no meaning a client
/// knows, a scope this build does not know, or two sharing an id.
///
/// A permission option carries no `needs_comment` and no input: ACP answers
/// with an option id alone.
fn parse_options(content: &Value) -> Option<Vec<GateOptionV2>> {
    let raw = content.get("options")?.as_array()?;
    if raw.is_empty() || raw.len() > OPTIONS_MAX {
        return None;
    }
    let mut options: Vec<GateOptionV2> = Vec::with_capacity(raw.len());
    for entry in raw {
        let id = id_field(entry, "id")?;
        let meaning = GateOptionMeaning::parse(entry.get("meaning")?.as_str()?)?;
        if options.iter().any(|o| o.id == id) {
            return None;
        }
        let (scope, requires) = parse_scope_requires(entry)?;
        let label = safe_string_field(entry, "label", OPTION_LABEL_MAX_CHARS)
            .filter(|l| !l.trim().is_empty())
            .unwrap_or_else(|| bound_text(&id, OPTION_LABEL_MAX_CHARS));
        options.push(GateOptionV2 {
            id,
            label,
            meaning,
            needs_comment: false,
            input: None,
            scope,
            requires,
        });
    }
    Some(options)
}

/// Read a permission.v2 from a message's `content`, or `None` when it is not
/// one this client may draw from v2 — in which case the v1 card it carries
/// is read instead.
///
/// Refused, as the corpus refuses them: no body, no `request_id`, an option
/// with no meaning, and a subject that is missing, of another schema, has no
/// digest, or names another request than `request_id`.
pub(crate) fn parse(content: &Value, body: Option<&str>) -> Option<ParsedPermissionV2> {
    if content.get("suite_event_type")?.as_str()? != PERMISSION_V2_EVENT_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let request_id = id_field(content, "request_id")?;
    // What the card's identity is made of (`crate::embedded::decision_identity`).
    id_field(content, "session_id")?;
    content.get("request_seq")?.as_u64()?;
    let title =
        safe_string_field(content, "title", TITLE_MAX_CHARS).filter(|t| !t.trim().is_empty())?;
    let options = parse_options(content)?;
    let subject = parse_bound_subject(content, &[HARNESS_PERMISSION_SCHEMA]).ok()??;
    if subject.binding.id != request_id {
        return None;
    }
    Some(ParsedPermissionV2 {
        request_id,
        title,
        tool_kind: safe_string_field(content, "tool_kind", TOOL_KIND_MAX_CHARS)
            .filter(|k| !k.trim().is_empty()),
        options,
        subject,
    })
}

/// The payload a permission.v2 message rides on its timeline row, or `None`
/// when the message is not a valid permission.v2 (or too large to carry one).
/// The body (the row's own) and the v1 card are stripped first.
pub fn embedded_payload(content: &Value) -> Option<Value> {
    let body = content.get("body").and_then(Value::as_str);
    parse(content, body)?;
    let mut payload = content.as_object()?.clone();
    payload.remove("body");
    payload.remove(crate::embedded::EMBEDDED_PERMISSION_KEY);
    let payload = Value::Object(payload);
    let size = serde_json::to_string(&payload)
        .map(|s| s.len())
        .unwrap_or(usize::MAX);
    (size <= crate::gate_v2::GATE_V2_PAYLOAD_MAX_BYTES).then_some(payload)
}

impl ParsedPermissionV2 {
    fn decision(&self) -> PermissionV2Decision {
        let (options, approvals_withheld) = offerable(
            &self.options,
            self.subject.complete,
            PERMISSION_APPROVALS_WITHHELD_NOTE,
        );
        PermissionV2Decision {
            request_id: self.request_id.clone(),
            title: self.title.clone(),
            tool_kind: self.tool_kind.clone(),
            options,
            subject: self.subject.view(),
            approvals_withheld,
        }
    }

    /// What a host that knows nothing of v2 may offer: the options a plain
    /// reply sends, each id its name — what the v1 card offered and how that
    /// host answers it — and never one that allows, which needs the preview
    /// such a host does not draw.
    fn legacy_options(&self) -> Vec<CustomEventDecisionOption> {
        self.options
            .iter()
            .filter(|o| o.meaning != GateOptionMeaning::Approve)
            .map(|o| CustomEventDecisionOption {
                id: o.label.clone(),
                label: o.label.clone(),
            })
            .collect()
    }
}

/// Draws a permission.v2 — registered under [`PERMISSION_V2_EVENT_TYPE`].
pub struct PermissionV2Renderer;

impl CustomEventRenderer for PermissionV2Renderer {
    fn event_type(&self) -> &str {
        PERMISSION_V2_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Permission"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(permission) = parse(content, body) else {
            return CustomEventRenderResult::default();
        };
        CustomEventRenderResult {
            fields: vec![CustomEventField {
                label: "Wants to".into(),
                value: permission.title.clone(),
            }],
            reasoning: None,
            link: None,
            decision: Some(CustomEventDecision {
                prompt: format!("Allow {}?", permission.title),
                options: permission.legacy_options(),
                subject: None,
                gate_v2: None,
                question_v2: None,
                delivery_v2: None,
                permission_v2: Some(permission.decision()),
            }),
        }
    }
}

/// A permission.answer.v2's `content`, validated against the request the
/// reader saw — the whole of what goes on the wire.
///
/// Refuses, with a reason a host can show: an option the request did not
/// offer (or that was withheld), an allowing option on a request whose
/// preview was not shown in full — even one a host put back — and a
/// reference that is not an event id. The binding is copied from what the
/// reader was shown, never computed; the hub judges it.
pub(crate) fn answer_request(
    permission: &PermissionV2Decision,
    option_id: &str,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if permission.request_id.is_empty() {
        return Err(CoreError::Protocol("an answer needs a request".into()));
    }
    let option = permission
        .options
        .iter()
        .find(|o| o.id == option_id)
        .ok_or_else(|| {
            CoreError::Protocol(format!("not an option this request offers: {option_id}"))
        })?;
    if option.meaning == GateOptionMeaning::Approve && permission.approvals_withheld.is_some() {
        return Err(CoreError::Protocol(
            "this card could not show everything the agent would do".into(),
        ));
    }
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    Ok(serde_json::json!({
        "msgtype": "m.text",
        "body": option.label.trim(),
        "schema_version": 1,
        "suite_event_type": PERMISSION_ANSWER_V2_SUITE_TYPE,
        "request_id": permission.request_id,
        "option_id": option.id,
        "subject": {
            "id": permission.subject.binding.id,
            "digest": permission.subject.binding.digest,
        },
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    }))
}

// ---------------------------------------------------------------------------
// The outcome: the hub's receipt closes the card.
// ---------------------------------------------------------------------------

/// How a permission closed, as the hub says it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Closed {
    /// Answered from the room; `option_id` says which.
    Selected,
    /// Thirty minutes passed unanswered.
    Expired,
    /// Withdrawn — the turn moved on.
    Cancelled,
}

/// One outcome as read off a row.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Outcome {
    closed: Closed,
    session_id: String,
    request_seq: u64,
    option_id: Option<String>,
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

/// The outcome a row carries, or `None`. An outcome with no `m.reference`
/// (a client cannot tell which card it closes), an outcome word outside
/// `selected | expired | cancelled`, or no request is not the schema and
/// closes nothing.
fn outcome_of(item: &TimelineItemDto) -> Option<Outcome> {
    if item.kind != "customMessage" || item.detail.as_deref() != Some(PERMISSION_OUTCOME_EVENT_TYPE)
    {
        return None;
    }
    let content = &item.custom_payload.as_ref()?.0;
    let closed = match content.get("outcome")?.as_str()? {
        "selected" => Closed::Selected,
        "expired" => Closed::Expired,
        "cancelled" => Closed::Cancelled,
        _ => return None,
    };
    Some(Outcome {
        closed,
        session_id: id_field(content, "session_id")?,
        request_seq: content.get("request_seq")?.as_u64()?,
        option_id: id_field(content, "option_id"),
        decided_by: safe_string_field(content, "decided_by", DECIDED_BY_MAX_CHARS)
            .map(|who| who.trim().to_string())
            .filter(|who| !who.is_empty()),
        references: reference(content)?,
        sender: item.sender.clone()?,
    })
}

/// A permission card's carrier — the v2 or v1 prose message, or the legacy
/// separate `dev.agentpod.permission.v1` event — as the matching needs it.
struct Carrier<'a> {
    identity: String,
    session_id: &'a str,
    request_seq: u64,
    event_id: Option<&'a str>,
    sender: Option<&'a str>,
    payload: &'a Value,
}

fn carrier_of(item: &TimelineItemDto) -> Option<Carrier<'_>> {
    let event_type = item.detail.as_deref()?;
    if !matches!(item.kind.as_str(), "message" | "customMessage")
        || !matches!(
            event_type,
            PERMISSION_REQUEST_EVENT_TYPE | PERMISSION_V2_EVENT_TYPE
        )
    {
        return None;
    }
    let payload = &item.custom_payload.as_ref()?.0;
    Some(Carrier {
        identity: crate::embedded::decision_identity(event_type, payload)?,
        session_id: payload.get("session_id")?.as_str()?,
        request_seq: payload.get("request_seq")?.as_u64()?,
        event_id: item.event_id.as_deref(),
        sender: item.sender.as_deref(),
        payload,
    })
}

/// Whether `outcome` closes the card `carrier` draws: sent by the identity
/// that asked (anyone in a room can send an event shaped like this one, and
/// closing someone else's permission card is what a forged one would be
/// for), referencing exactly this carrier, about the same request.
fn closes(outcome: &Outcome, carrier: &Carrier<'_>) -> bool {
    carrier.sender == Some(outcome.sender.as_str())
        && carrier.event_id == Some(outcome.references.as_str())
        && carrier.session_id == outcome.session_id
        && carrier.request_seq == outcome.request_seq
}

/// What an option the card offered meant, read off the carrier's payload:
/// a v2 option says its meaning and scope, a v1 option only its name.
enum Said {
    Meant {
        meaning: GateOptionMeaning,
        scope: Option<OptionScope>,
        label: String,
    },
    Named(String),
}

fn said(payload: &Value, option_id: &str) -> Option<Said> {
    let options = payload.get("options")?.as_array()?;
    if let Some(entry) = options
        .iter()
        .find(|o| o.get("id").and_then(Value::as_str) == Some(option_id))
    {
        let label = safe_string_field(entry, "label", OPTION_LABEL_MAX_CHARS)
            .unwrap_or_else(|| bound_text(option_id, OPTION_LABEL_MAX_CHARS));
        return Some(
            match entry
                .get("meaning")
                .and_then(Value::as_str)
                .and_then(GateOptionMeaning::parse)
            {
                Some(meaning) => Said::Meant {
                    meaning,
                    scope: entry
                        .get("scope")
                        .and_then(Value::as_str)
                        .and_then(OptionScope::parse),
                    label,
                },
                None => Said::Named(label),
            },
        );
    }
    let entry = options
        .iter()
        .find(|o| o.get("option_id").and_then(Value::as_str) == Some(option_id))?;
    safe_string_field(entry, "name", OPTION_LABEL_MAX_CHARS).map(Said::Named)
}

/// The sentence a receipt says. Past tense, because it is over.
///
/// By meaning and scope when the card knew them ("Allowed once by rakesh",
/// "Rejected always by rakesh"); in the hub's own words otherwise — its
/// line beside the receipt reads "Allow once — answered by rakesh", and
/// "That permission request expired unanswered" / "was withdrawn"
/// (`permissionOutcomeText`, agentpod `permission-wire.ts`).
fn summary(outcome: &Outcome, said: Option<Said>) -> String {
    let by = |verb: String| match &outcome.decided_by {
        Some(who) => format!("{verb} by {who}"),
        None => verb,
    };
    match outcome.closed {
        Closed::Expired => by("Expired unanswered".into()),
        Closed::Cancelled => by("Withdrawn".into()),
        Closed::Selected => match said {
            Some(Said::Meant {
                meaning: GateOptionMeaning::Approve,
                scope,
                ..
            }) => by(match scope {
                Some(OptionScope::Once) => "Allowed once",
                Some(OptionScope::Run) => "Allowed for this run",
                Some(OptionScope::Always) => "Allowed always",
                None => "Allowed",
            }
            .into()),
            Some(Said::Meant {
                meaning: GateOptionMeaning::Reject,
                scope,
                ..
            }) => by(match scope {
                Some(OptionScope::Run) => "Rejected for this run",
                Some(OptionScope::Always) => "Rejected always",
                Some(OptionScope::Once) | None => "Rejected",
            }
            .into()),
            Some(Said::Meant { label, .. }) => by(format!("Chose \u{201C}{label}\u{201D}")),
            Some(Said::Named(label)) => match &outcome.decided_by {
                Some(who) => format!("{label} — answered by {who}"),
                None => format!("{label} — answered"),
            },
            None => by("Answered".into()),
        },
    }
}

/// Every permission the loaded timeline says is closed, keyed by the card's
/// decision identity (`crate::embedded::decision_identity`, the same for its
/// v2 prose message and a legacy v1 event), with the receipt to draw. Empty —
/// and cheap — for a timeline holding no permission outcome.
///
/// The receipt is worded from the carrier the outcome references, so a v2
/// card says what the option meant. A request with two outcomes takes the
/// later one in the timeline.
pub fn resolved_permissions(rows: &[TimelineRow]) -> HashMap<String, CustomEventOutcome> {
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
        let words = outcome
            .option_id
            .as_deref()
            .and_then(|id| said(carrier.payload, id));
        resolved.insert(
            carrier.identity.clone(),
            CustomEventOutcome {
                decision: match outcome.closed {
                    Closed::Selected => outcome
                        .option_id
                        .clone()
                        .unwrap_or_else(|| "selected".into()),
                    Closed::Expired => "expired".into(),
                    Closed::Cancelled => "cancelled".into(),
                },
                decided_by: outcome.decided_by.clone(),
                summary: summary(outcome, words),
                prompt: None,
            },
        );
    }
    resolved
}

/// The corpus, end to end: every permission.v2 `accept` draws from v2, every
/// `reject` falls back to the v1 card, what the client sends is exactly the
/// corpus's permission.answer.v2, and the corpus's outcomes close the card.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use crate::dto::CustomPayload;
    use crate::embedded::{embedded_suite_event, reconcile, EMBEDDED_PERMISSION_KEY};
    use crate::gate_v2::{GatePreviewBlock, GateSubjectBinding};
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

    /// The corpus's permission: a command, all four ACP option kinds.
    fn request() -> Value {
        cases(PERMISSION_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    fn decision_for(content: &Value) -> CustomEventDecision {
        let payload = embedded_payload(content).expect("a valid permission.v2");
        match resolve_custom_event(
            default_registry(),
            Some(PERMISSION_V2_EVENT_TYPE),
            Some(&payload),
            body(content),
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    fn v2(content: &Value) -> PermissionV2Decision {
        decision_for(content).permission_v2.expect("drawn from v2")
    }

    const PERMISSION_EVENT: &str = "$permissionEventId";

    #[test]
    fn every_accepted_permission_v2_is_drawn_from_v2() {
        for (why, content) in cases(PERMISSION_V2_EVENT_TYPE, "accept") {
            assert!(parse(&content, body(&content)).is_some(), "{why}");
            assert_eq!(
                embedded_suite_event(&content).map(|(t, _)| t),
                Some(PERMISSION_V2_EVENT_TYPE),
                "{why}"
            );
        }
    }

    #[test]
    fn every_rejected_permission_v2_falls_back_to_the_v1_card() {
        let rejects = cases(PERMISSION_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 3, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
            assert_eq!(
                embedded_suite_event(&content),
                Some((
                    PERMISSION_REQUEST_EVENT_TYPE,
                    content[EMBEDDED_PERMISSION_KEY].clone()
                )),
                "{why}"
            );
        }
    }

    #[test]
    fn a_permission_offers_its_options_by_meaning_and_scope() {
        let permission = v2(&request());
        assert_eq!(permission.request_id, "acps_3f0c:12");
        assert_eq!(permission.title, "Run the test suite");
        assert_eq!(permission.tool_kind.as_deref(), Some("execute"));
        let drawn: Vec<(&str, &str, GateOptionMeaning, Option<OptionScope>)> = permission
            .options
            .iter()
            .map(|o| (o.id.as_str(), o.label.as_str(), o.meaning, o.scope))
            .collect();
        use GateOptionMeaning::{Approve, Reject};
        assert_eq!(
            drawn,
            [
                ("allow_once", "Allow once", Approve, Some(OptionScope::Once)),
                (
                    "allow_always",
                    "Always allow",
                    Approve,
                    Some(OptionScope::Always)
                ),
                ("reject_once", "Reject", Reject, Some(OptionScope::Once)),
                (
                    "reject_always",
                    "Never allow",
                    Reject,
                    Some(OptionScope::Always)
                ),
            ]
        );
        assert_eq!(permission.approvals_withheld, None);
        assert_eq!(
            permission.subject.binding,
            GateSubjectBinding {
                id: "acps_3f0c:12".into(),
                digest: "bb03bfd8ee6d757e6957d029cd6a7b8e004f16386755ce672e3f97f445563878".into(),
            }
        );
        assert_eq!(permission.subject.schema, HARNESS_PERMISSION_SCHEMA);
        assert_eq!(
            permission.subject.expires_at.as_deref(),
            Some("2026-11-07T10:30:00.000Z")
        );
    }

    #[test]
    fn the_preview_is_drawn_verbatim() {
        assert_eq!(
            v2(&request()).subject.preview,
            [
                GatePreviewBlock::Field {
                    label: "Tool".into(),
                    value: "Run the test suite".into()
                },
                GatePreviewBlock::Field {
                    label: "Kind".into(),
                    value: "execute".into()
                },
                GatePreviewBlock::Text {
                    label: Some("Command".into()),
                    text: "bun test".into()
                },
                GatePreviewBlock::Field {
                    label: "Location".into(),
                    value: "apps/hub".into()
                },
            ]
        );
    }

    fn assert_withheld(content: &Value, why: &str) {
        let decision = decision_for(content);
        let permission = decision.permission_v2.as_ref().unwrap();
        let ids: Vec<&str> = permission.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(ids, ["reject_once", "reject_always"], "{why}");
        assert_eq!(
            permission.approvals_withheld.as_deref(),
            Some(PERMISSION_APPROVALS_WITHHELD_NOTE),
            "{why}"
        );
    }

    #[test]
    fn an_incomplete_preview_offers_no_allow() {
        let mut content = request();
        content["subject"]["preview_complete"] = json!(false);
        assert_withheld(&content, "preview_complete false");
        let mut unknown = request();
        unknown["subject"]["preview"]
            .as_array_mut()
            .unwrap()
            .push(json!({ "type": "terminal", "id": "t1" }));
        assert_withheld(&unknown, "a block this build cannot draw");
        let mut empty = request();
        empty["subject"]["preview"] = json!([]);
        assert_withheld(&empty, "an empty preview");
    }

    #[test]
    fn a_subject_without_its_flag_is_not_the_wire() {
        // gate.v2 reads a missing flag as incomplete; permission.v2 is held
        // to the whole Matrix shape, and falls back to the v1 card.
        let mut content = request();
        content["subject"]
            .as_object_mut()
            .unwrap()
            .remove("preview_complete");
        assert!(embedded_payload(&content).is_none());
        let mut other = request();
        other["subject"]["id"] = json!("acps_3f0c:13");
        assert!(
            embedded_payload(&other).is_none(),
            "another request's subject"
        );
        let mut scope = request();
        scope["options"][0]["scope"] = json!("forever");
        assert!(
            embedded_payload(&scope).is_none(),
            "a scope this build does not know"
        );
    }

    #[test]
    fn a_host_without_v2_never_allows() {
        let decision = decision_for(&request());
        let legacy: Vec<&str> = decision.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(legacy, ["Reject", "Never allow"]);
        assert_eq!(decision.subject, None);
        assert_eq!(decision.prompt, "Allow Run the test suite?");
    }

    #[test]
    fn the_v1_card_and_body_are_stripped_from_the_row() {
        let payload = embedded_payload(&request()).unwrap();
        assert!(payload.get("body").is_none());
        assert!(payload.get(EMBEDDED_PERMISSION_KEY).is_none());
        // The row keeps what its identity is made of.
        assert_eq!(
            crate::embedded::decision_identity(PERMISSION_V2_EVENT_TYPE, &payload).as_deref(),
            Some("permission:acps_3f0c:12")
        );
    }

    #[test]
    fn a_v1_only_permission_reads_exactly_as_before() {
        let mut content = request();
        let obj = content.as_object_mut().unwrap();
        obj.remove("suite_event_type");
        let v1 = obj[EMBEDDED_PERMISSION_KEY].clone();
        assert_eq!(
            embedded_suite_event(&content),
            Some((PERMISSION_REQUEST_EVENT_TYPE, v1.clone()))
        );
        match resolve_custom_event(
            default_registry(),
            Some(PERMISSION_REQUEST_EVENT_TYPE),
            Some(&v1),
            None,
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => {
                assert_eq!(d.permission_v2, None);
                let ids: Vec<&str> = d.options.iter().map(|o| o.id.as_str()).collect();
                assert_eq!(ids, ["Allow once", "Always allow", "Reject", "Never allow"]);
            }
            other => panic!("expected the v1 card, got {other:?}"),
        }
    }

    // ---- permission.answer.v2 ---------------------------------------------

    #[test]
    fn the_answers_are_exactly_the_corpus_answers() {
        let accepts = cases(PERMISSION_ANSWER_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 2, "the corpus grew; read the new case");
        let permission = v2(&request());
        assert_eq!(
            answer_request(&permission, "allow_once", PERMISSION_EVENT).unwrap(),
            accepts[0].1,
            "{}",
            accepts[0].0
        );
        assert_eq!(
            answer_request(&permission, "reject_once", PERMISSION_EVENT).unwrap(),
            accepts[1].1,
            "{}",
            accepts[1].0
        );
    }

    #[test]
    fn every_rejected_answer_is_never_what_the_client_sends() {
        let rejects = cases(PERMISSION_ANSWER_V2_SUITE_TYPE, "reject");
        assert_eq!(rejects.len(), 3, "the corpus grew; read the new case");
        let permission = v2(&request());
        for (why, content) in &rejects {
            let option = content["option_id"].as_str().unwrap();
            match answer_request(&permission, option, PERMISSION_EVENT) {
                // A wrong or missing binding is never built: it is copied.
                Ok(sent) => assert_ne!(&sent, content, "{why}"),
                // An option the request did not offer is refused.
                Err(_) => assert_eq!(option, "allow_everything", "{why}"),
            }
        }
        for option in ["allow_once", "allow_always", "reject_once", "reject_always"] {
            let sent = answer_request(&permission, option, PERMISSION_EVENT).unwrap();
            assert_eq!(
                sent["subject"],
                json!({
                    "id": "acps_3f0c:12",
                    "digest": "bb03bfd8ee6d757e6957d029cd6a7b8e004f16386755ce672e3f97f445563878"
                })
            );
        }
    }

    #[test]
    fn a_withheld_allow_cannot_be_sent_even_if_a_host_puts_it_back() {
        let mut content = request();
        content["subject"]["preview_complete"] = json!(false);
        let permission = v2(&content);
        assert!(answer_request(&permission, "allow_once", PERMISSION_EVENT).is_err());
        let mut forged = permission.clone();
        forged.options.insert(
            0,
            GateOptionV2 {
                id: "allow_once".into(),
                label: "Allow once".into(),
                meaning: GateOptionMeaning::Approve,
                needs_comment: false,
                input: None,
                scope: Some(OptionScope::Once),
                requires: None,
            },
        );
        assert!(answer_request(&forged, "allow_once", PERMISSION_EVENT).is_err());
        assert!(answer_request(&permission, "reject_once", PERMISSION_EVENT).is_ok());
    }

    #[test]
    fn an_answer_must_reference_the_permission_event() {
        let permission = v2(&request());
        assert!(answer_request(&permission, "reject_once", "not-an-event").is_err());
    }

    // ---- permission.outcome.v1 --------------------------------------------

    const AGENT: &str = "@station_a:hs";

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
            Some("Permission needed: Run the test suite"),
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

    /// The permission's prose message, drawn from v2. Its event id is
    /// `$permissionEventId`, the corpus outcome's reference.
    fn v2_card() -> TimelineRow {
        row(
            "permissionEventId",
            "message",
            Some("m.text"),
            AGENT,
            PERMISSION_V2_EVENT_TYPE,
            embedded_payload(&request()).unwrap(),
        )
    }

    /// The same prose message from a hub that sends only the v1 card.
    fn v1_card() -> TimelineRow {
        row(
            "permissionEventId",
            "message",
            Some("m.text"),
            AGENT,
            PERMISSION_REQUEST_EVENT_TYPE,
            request()[EMBEDDED_PERMISSION_KEY].clone(),
        )
    }

    fn outcome(sender: &str, content: Value) -> TimelineRow {
        row(
            "o",
            "customMessage",
            None,
            sender,
            PERMISSION_OUTCOME_EVENT_TYPE,
            content,
        )
    }

    fn corpus_outcome(index: usize) -> Value {
        cases(PERMISSION_OUTCOME_EVENT_TYPE, "accept")[index]
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
                assert!(!buttons, "a closed permission draws no buttons");
                Some(outcome.summary)
            }
            None => {
                assert!(buttons, "an open permission keeps its buttons");
                None
            }
        }
    }

    #[test]
    fn an_answer_closes_a_v2_card_saying_what_the_option_meant() {
        let mut rows = vec![v2_card(), outcome(AGENT, corpus_outcome(0))];
        assert_eq!(
            receipt(&mut rows).as_deref(),
            Some("Allowed once by fixture-person")
        );
        // The outcome itself is never drawn.
        assert_eq!(rows[1].view, ItemView::None);
    }

    #[test]
    fn the_receipt_says_scope_and_meaning() {
        for (option, said) in [
            ("allow_always", "Allowed always by fixture-person"),
            ("reject_once", "Rejected by fixture-person"),
            ("reject_always", "Rejected always by fixture-person"),
        ] {
            let mut content = corpus_outcome(0);
            content["option_id"] = json!(option);
            let mut rows = vec![v2_card(), outcome(AGENT, content)];
            assert_eq!(receipt(&mut rows).as_deref(), Some(said), "{option}");
        }
    }

    #[test]
    fn an_expiry_or_a_withdrawal_closes_the_card() {
        let mut rows = vec![v2_card(), outcome(AGENT, corpus_outcome(1))];
        assert_eq!(receipt(&mut rows).as_deref(), Some("Expired unanswered"));
        let mut cancelled = corpus_outcome(1);
        cancelled["outcome"] = json!("cancelled");
        let mut rows = vec![v2_card(), outcome(AGENT, cancelled)];
        assert_eq!(receipt(&mut rows).as_deref(), Some("Withdrawn"));
    }

    #[test]
    fn an_outcome_closes_a_v1_card_in_the_hubs_words() {
        let mut rows = vec![v1_card(), outcome(AGENT, corpus_outcome(0))];
        assert_eq!(
            receipt(&mut rows).as_deref(),
            Some("Allow once — answered by fixture-person")
        );
    }

    #[test]
    fn the_outcome_may_arrive_first() {
        let mut rows = vec![outcome(AGENT, corpus_outcome(0)), v2_card()];
        reconcile(&mut rows);
        let (buttons, outcome) = state(&rows[1]);
        assert!(!buttons);
        assert_eq!(
            outcome.map(|o| o.summary).as_deref(),
            Some("Allowed once by fixture-person")
        );
    }

    #[test]
    fn someone_elses_outcome_closes_nothing() {
        let mut rows = vec![v2_card(), outcome("@mallory:hs", corpus_outcome(0))];
        assert_eq!(receipt(&mut rows), None);
    }

    #[test]
    fn an_outcome_referencing_another_event_or_request_closes_nothing() {
        let mut elsewhere = corpus_outcome(0);
        elsewhere["m.relates_to"]["event_id"] = json!("$another");
        let mut rows = vec![v2_card(), outcome(AGENT, elsewhere)];
        assert_eq!(receipt(&mut rows), None);
        let mut next = corpus_outcome(0);
        next["request_seq"] = json!(13);
        let mut rows = vec![v2_card(), outcome(AGENT, next)];
        assert_eq!(receipt(&mut rows), None);
        let mut quoted = corpus_outcome(0);
        quoted["m.relates_to"]["rel_type"] = json!("m.in_reply_to");
        let mut rows = vec![v2_card(), outcome(AGENT, quoted)];
        assert_eq!(receipt(&mut rows), None);
    }

    #[test]
    fn every_corpus_outcome_is_read_and_every_reject_closes_nothing() {
        for (why, content) in cases(PERMISSION_OUTCOME_EVENT_TYPE, "accept") {
            assert!(outcome_of(&outcome(AGENT, content).item).is_some(), "{why}");
        }
        let rejects = cases(PERMISSION_OUTCOME_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 2, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(
                outcome_of(&outcome(AGENT, content.clone()).item).is_none(),
                "{why}"
            );
            let mut rows = vec![v2_card(), outcome(AGENT, content)];
            assert_eq!(receipt(&mut rows), None, "{why}");
        }
    }

    #[test]
    fn a_legacy_v1_event_beside_the_v2_card_is_one_card_and_closes_with_it() {
        let separate = row(
            "legacy",
            "customMessage",
            None,
            AGENT,
            PERMISSION_REQUEST_EVENT_TYPE,
            request()[EMBEDDED_PERMISSION_KEY].clone(),
        );
        let mut rows = vec![v2_card(), separate, outcome(AGENT, corpus_outcome(0))];
        assert_eq!(
            receipt(&mut rows).as_deref(),
            Some("Allowed once by fixture-person")
        );
        assert_eq!(
            rows[1].view,
            ItemView::None,
            "the legacy event stays hidden"
        );
    }

    #[test]
    fn a_gate_outcome_does_not_close_a_permission() {
        let gate_outcome = json!({
            "suite_event_type": crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            "decision": "approve",
            "decided_by": "fixture-person",
            "m.relates_to": { "rel_type": "m.reference", "event_id": PERMISSION_EVENT }
        });
        let mut rows = vec![
            v2_card(),
            row(
                "g",
                "customMessage",
                None,
                AGENT,
                crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
                gate_outcome,
            ),
        ];
        assert_eq!(receipt(&mut rows), None);
    }
}
