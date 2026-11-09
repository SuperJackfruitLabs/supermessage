//! A gate decided by machine checks.
//!
//! charter → `decisions/2026-11-09-a-gate-may-be-decided-by-checks.md`. A
//! stage may name checks — CI on the card's pull request, a SuperWitness
//! verdict — that run before any person is asked. superpipeline runs them and
//! decides; this client only shows them, and lets a workspace owner or admin
//! override one. Five things cross the wire (fixture version 11, entries
//! tagged `variant: "checks"`):
//!
//! - **gate.v2's `checks`** — `[{ name, provider, status, url? }]` — so a
//!   person sees which checks passed before approving ([`parse_checks`]). A
//!   list the hub would not carry (empty, more than five, a name the board
//!   would refuse, an unknown provider or status, two of one name) makes the
//!   whole gate.v2 unreadable and the v1 card is drawn, as a malformed
//!   `quorum` or `release` does: a card saying "build ✓" while a garbled
//!   second check failed would be saying something false.
//! - **[`CHECK_FAILED_TYPE`] and [`CHECK_OVERRIDDEN_TYPE`]** — the hub's
//!   structured notices of a failed and an overridden check. Each is posted
//!   beside a readable reply, so the notice itself is never a row and never a
//!   notification. When they reference a checks.waiting card they settle it
//!   ([`apply_to_view`]).
//! - **[`CHECKS_WAITING_TYPE`]** — the card an escalation of overdue checks
//!   posts, listing them ([`ChecksWaitingRenderer`]). It offers **Override**
//!   on each check pending or failed.
//! - **[`CHECK_OVERRIDE_SUITE_TYPE`]** — what a reader sends to override one,
//!   with a required comment, referencing that card ([`override_request`]).
//!
//! Who may override is the board's to judge: a workspace owner or admin, by
//! the Accounts role in the verified token at the time (§5), never the
//! producer of the work and never an agent. A client holds no workspace role,
//! so it shows Override with "Admins only", as it shows an option that
//! `requires: workspace-admin` (`crate::gate_v2::OptionRequires`), and the
//! hub's refusal comes back as a readable reply in the room ("Only a
//! workspace owner or admin can override a check. Nothing has changed.").
//!
//! Everything here is room content, so it is hostile: read one level at a
//! time, never coerced, every drawn string bounded.

use serde_json::Value;

use crate::custom_events::{
    bound_text, safe_link, safe_string_field, safe_url, CustomEventDecision, CustomEventField,
    CustomEventOutcome, CustomEventRenderResult, CustomEventRenderer, CustomEventView,
};
use crate::dto::TimelineItemDto;
use crate::error::{CoreError, CoreResult};
use crate::gate_v2::id_field;
use crate::item_view::ItemView;

/// The hub's notice that a machine check failed.
pub const CHECK_FAILED_TYPE: &str = "dev.agentpod.check.failed.v2";
/// The hub's notice that a check was overridden.
pub const CHECK_OVERRIDDEN_TYPE: &str = "dev.agentpod.check.overridden.v2";
/// The card an escalation of overdue checks posts: the `suite_event_type`
/// marker on its prose message, and the type its card is registered under.
pub const CHECKS_WAITING_TYPE: &str = "dev.agentpod.checks.waiting.v2";
/// What a reader sends to override a check.
pub const CHECK_OVERRIDE_SUITE_TYPE: &str = "dev.superpipeline.check.override.v2";

/// The board's bounds (charter §1): at most 5 checks, a name of 1–40
/// characters without surrounding spaces or "/".
const CHECKS_MAX: usize = 5;
const CHECK_NAME_MAX_CHARS: usize = 40;
/// The board's bound on an override's comment (`INVALID_COMMENT` past it).
const OVERRIDE_COMMENT_MAX_BYTES: usize = 8192;
const FIELD_VALUE_MAX_CHARS: usize = 300;
const FIELD_LABEL_MAX_CHARS: usize = 60;
const WHO_MAX_CHARS: usize = 60;

/// The receipt decision of a checks.waiting card whose card went back.
pub const CHECKS_RETURNED: &str = "returned";
/// The receipt decision of a checks.waiting card with nothing left to
/// override.
pub const CHECKS_SETTLED: &str = "settled";

/// What a waiting card says about who may override, beside its buttons.
pub const OVERRIDE_NOTE: &str =
    "Only a workspace owner or admin may override a check, with a comment.";

/// Which kind of decider a check is.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum CheckProvider {
    /// Every required CI status on the linked pull request's head commit.
    Ci,
    /// A SuperWitness verdict on the card's latest run.
    Verdict,
}

impl CheckProvider {
    fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "ci" => Self::Ci,
            "verdict" => Self::Verdict,
            _ => return None,
        })
    }

    /// "CI", "verdict": how a line names it.
    fn word(self) -> &'static str {
        match self {
            Self::Ci => "CI",
            Self::Verdict => "verdict",
        }
    }

    /// "CI check", "Verdict check": how a sentence opens with it.
    fn check_word(self) -> &'static str {
        match self {
            Self::Ci => "CI check",
            Self::Verdict => "Verdict check",
        }
    }
}

/// Where a check stands.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum CheckStatus {
    Pending,
    Passed,
    Failed,
    /// Passed by hand by a workspace owner or admin.
    Overridden,
}

impl CheckStatus {
    fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "pending" => Self::Pending,
            "passed" => Self::Passed,
            "failed" => Self::Failed,
            "overridden" => Self::Overridden,
            _ => return None,
        })
    }

    fn word(self) -> &'static str {
        match self {
            Self::Pending => "pending",
            Self::Passed => "passed",
            Self::Failed => "failed",
            Self::Overridden => "overridden",
        }
    }

    /// Whether an override can still act on it (charter §5: a pending or
    /// failed check).
    fn open(self) -> bool {
        matches!(self, Self::Pending | Self::Failed)
    }
}

/// One check, as a host draws it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateCheck {
    /// The check's name, as the board knows it; handed back verbatim in an
    /// override. 1–40 characters, so drawn whole.
    pub name: String,
    pub provider: CheckProvider,
    pub status: CheckStatus,
    /// A link to the result, when it is an `https://` address
    /// (`crate::custom_events::safe_url`); any other is dropped and the check
    /// kept, since a link decorates a check rather than deciding it.
    pub url: Option<String>,
    /// The plain line a host draws: "build (CI) — passed".
    pub line: String,
    /// Whether this card offers Override on it: only a checks.waiting card,
    /// and only for a check pending or failed. Never on a gate.v2, whose
    /// checks are over by the time it opens, and which an override could not
    /// reference.
    pub overridable: bool,
}

/// "build (CI) — passed", "quality (verdict) — overridden by rakesh".
fn check_line(
    name: &str,
    provider: CheckProvider,
    status: CheckStatus,
    by: Option<&str>,
) -> String {
    let base = format!("{name} ({}) \u{2014} {}", provider.word(), status.word());
    match by {
        Some(who) if status == CheckStatus::Overridden => format!("{base} by {who}"),
        _ => base,
    }
}

/// A check name the board would accept: 1–40 characters, no surrounding
/// spaces, no "/" (it is a path segment of the override route).
fn is_check_name(v: &str) -> bool {
    let n = v.chars().count();
    (1..=CHECK_NAME_MAX_CHARS).contains(&n) && v.trim() == v && !v.contains('/')
}

/// One check off the wire, or `None` when it is not one.
fn parse_check(raw: &Value, overridable_card: bool) -> Option<GateCheck> {
    let raw = raw.as_object()?;
    let name = raw.get("name")?.as_str().filter(|n| is_check_name(n))?;
    let provider = CheckProvider::parse(raw.get("provider")?.as_str()?)?;
    let status = CheckStatus::parse(raw.get("status")?.as_str()?)?;
    let url = raw.get("url").and_then(Value::as_str).and_then(safe_url);
    Some(GateCheck {
        name: name.to_string(),
        provider,
        status,
        url,
        line: check_line(name, provider, status, None),
        overridable: overridable_card && status.open(),
    })
}

/// A list of checks: `Err(())` unless it is the list the hub carries — an
/// array of 1 to 5 well-formed checks, names unique. Nothing in part.
fn parse_check_list(raw: &Value, overridable_card: bool) -> Result<Vec<GateCheck>, ()> {
    let raw = raw.as_array().ok_or(())?;
    if raw.is_empty() || raw.len() > CHECKS_MAX {
        return Err(());
    }
    let mut out: Vec<GateCheck> = Vec::with_capacity(raw.len());
    for item in raw {
        let check = parse_check(item, overridable_card).ok_or(())?;
        if out.iter().any(|c| c.name == check.name) {
            return Err(());
        }
        out.push(check);
    }
    Ok(out)
}

/// `content.checks` on a gate.v2: `Ok(None)` when absent (a gate whose
/// stage has no checks), `Err(())` when present and not the list the hub
/// carries. Like a malformed quorum, an `Err` makes the whole gate.v2
/// unreadable and the v1 card is drawn.
pub(crate) fn parse_checks(content: &Value) -> Result<Option<Vec<GateCheck>>, ()> {
    match content.get("checks") {
        None => Ok(None),
        Some(raw) => parse_check_list(raw, false).map(Some),
    }
}

// ---------------------------------------------------------------------------
// The checks.waiting card.
// ---------------------------------------------------------------------------

/// A checks.waiting card as a host draws and answers it. Rides on
/// `CustomEventDecision::checks_waiting`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct ChecksWaitingDecision {
    /// Handed back verbatim in an override.
    pub board_id: String,
    /// Handed back verbatim in an override.
    pub card_id: String,
    /// Display text, bounded.
    pub card_title: String,
    /// The stage the checks wait at, as the board keys it. Display text,
    /// bounded.
    pub stage_key: String,
    /// In the board's order. Override is offered on each one
    /// [`GateCheck::overridable`].
    pub checks: Vec<GateCheck>,
    /// [`OVERRIDE_NOTE`]: who the board lets override.
    pub override_note: String,
}

/// A checks.waiting card that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedChecksWaiting {
    decision: ChecksWaitingDecision,
    deep_link: Option<String>,
}

/// Read a checks.waiting card, or `None` when it is not one this client may
/// draw — the message then stays the prose it is (there is no v1 form).
pub(crate) fn parse_waiting(content: &Value, body: Option<&str>) -> Option<ParsedChecksWaiting> {
    if content.get("suite_event_type")?.as_str()? != CHECKS_WAITING_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let board_id = id_field(content, "board_id")?;
    let card_id = id_field(content, "card_id")?;
    let stage_key = id_field(content, "stage_key")?;
    let card_title = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)
        .filter(|t| !t.trim().is_empty())?;
    let checks = parse_check_list(content.get("checks")?, true).ok()?;
    Some(ParsedChecksWaiting {
        decision: ChecksWaitingDecision {
            board_id,
            card_id,
            card_title,
            stage_key: bound_text(&stage_key, FIELD_LABEL_MAX_CHARS),
            checks,
            override_note: OVERRIDE_NOTE.to_string(),
        },
        deep_link: safe_link(content, "deep_link"),
    })
}

/// The payload a checks.waiting message rides on its timeline row, or `None`
/// when the message is not a valid one or too large to carry.
pub fn embedded_payload(content: &Value) -> Option<Value> {
    let body = content.get("body").and_then(Value::as_str);
    parse_waiting(content, body)?;
    let mut payload = content.as_object()?.clone();
    payload.remove("body");
    let payload = Value::Object(payload);
    let size = serde_json::to_string(&payload)
        .map(|s| s.len())
        .unwrap_or(usize::MAX);
    (size <= crate::gate_v2::GATE_V2_PAYLOAD_MAX_BYTES).then_some(payload)
}

/// Draws a checks.waiting card — registered under [`CHECKS_WAITING_TYPE`].
pub struct ChecksWaitingRenderer;

impl CustomEventRenderer for ChecksWaitingRenderer {
    fn event_type(&self) -> &str {
        CHECKS_WAITING_TYPE
    }

    fn label(&self) -> &str {
        "Checks overdue"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(parsed) = parse_waiting(content, body) else {
            return CustomEventRenderResult::default();
        };
        let waiting = parsed.decision;
        let prompt = format!("Checks overdue on \u{201C}{}\u{201D}", waiting.card_title);
        let fields = vec![
            CustomEventField {
                label: "Card".into(),
                value: waiting.card_title.clone(),
            },
            CustomEventField {
                label: "Stage".into(),
                value: waiting.stage_key.clone(),
            },
        ];
        CustomEventRenderResult {
            fields,
            reasoning: None,
            link: parsed.deep_link,
            decision: Some(CustomEventDecision {
                prompt,
                // No plain reply overrides a check: it needs a comment, and
                // the hub reads no typed reply here.
                options: Vec::new(),
                subject: None,
                gate_v2: None,
                question_v2: None,
                delivery_v2: None,
                permission_v2: None,
                attention_v2: None,
                checks_waiting: Some(waiting),
                deadline: None,
            }),
        }
    }
}

/// A check.override.v2's `content`, validated against the card the reader
/// saw — the whole of what goes on the wire, exactly the corpus's shape.
///
/// Refuses, with a reason a host can show: a check the card does not offer
/// Override on (passed, overridden, or not on it), no comment (the board
/// needs one saying why), one over the board's limit, and a reference that is
/// not an event id. The board and card are copied from the card, never
/// computed. Who may override is the board's to judge.
pub(crate) fn override_request(
    waiting: &ChecksWaitingDecision,
    check_name: &str,
    comment: &str,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if waiting.board_id.is_empty() || waiting.card_id.is_empty() {
        return Err(CoreError::Protocol("an override needs a card".into()));
    }
    let check = waiting
        .checks
        .iter()
        .find(|c| c.name == check_name)
        .ok_or_else(|| CoreError::Protocol(format!("no check named {check_name} on this card")))?;
    if !check.overridable {
        return Err(CoreError::Protocol(format!(
            "{} is {} and cannot be overridden",
            check.name,
            check.status.word()
        )));
    }
    let comment = comment.trim();
    if comment.is_empty() {
        return Err(CoreError::Protocol(
            "overriding a check needs a comment saying why".into(),
        ));
    }
    if comment.len() > OVERRIDE_COMMENT_MAX_BYTES {
        return Err(CoreError::Protocol(format!(
            "a comment is at most {OVERRIDE_COMMENT_MAX_BYTES} bytes"
        )));
    }
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    Ok(serde_json::json!({
        "msgtype": "m.text",
        "body": format!("Override {}: {comment}", check.name),
        "schema_version": 1,
        "suite_event_type": CHECK_OVERRIDE_SUITE_TYPE,
        "board_id": waiting.board_id,
        "card_id": waiting.card_id,
        "check_name": check.name,
        "comment": comment,
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    }))
}

// ---------------------------------------------------------------------------
// The notices: a failed or overridden check.
// ---------------------------------------------------------------------------

/// Whether a row is a failed or overridden check's notice, which is never a
/// row of its own: the readable reply beside it says the same thing.
pub fn is_notice_type(event_type: Option<&str>) -> bool {
    matches!(event_type, Some(CHECK_FAILED_TYPE | CHECK_OVERRIDDEN_TYPE))
}

/// What a notice says happened to a check.
#[derive(Debug, Clone, PartialEq, Eq)]
enum Happened {
    /// Failed; `returned_to` the stage the card went back to, or `None` when
    /// it could not go back and waits for a person.
    Failed {
        returned_to: Option<String>,
    },
    Overridden {
        by: Option<String>,
    },
}

/// A notice as read off a row.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Notice {
    board_id: String,
    card_id: String,
    stage_key: String,
    check_name: String,
    provider: CheckProvider,
    happened: Happened,
    /// The checks.waiting card it references, when one was open.
    references: Option<String>,
    sender: String,
}

/// The event an `m.reference` points at.
fn reference(content: &Value) -> Option<String> {
    let relates = content.get("m.relates_to")?;
    if relates.get("rel_type")?.as_str()? != "m.reference" {
        return None;
    }
    id_field(relates, "event_id")
}

/// The notice a row carries, or `None`. Only the custom-event form: the hub
/// sends the readable line as a separate reply.
fn notice_of(item: &TimelineItemDto) -> Option<Notice> {
    if item.kind != "customMessage" {
        return None;
    }
    let event_type = item.detail.as_deref()?;
    let content = &item.custom_payload.as_ref()?.0;
    if content.get("suite_event_type").and_then(Value::as_str) != Some(event_type) {
        return None;
    }
    let check = content.get("check")?;
    let check_name = check.get("name")?.as_str().filter(|n| is_check_name(n))?;
    let provider = CheckProvider::parse(check.get("provider")?.as_str()?)?;
    let status = check.get("status")?.as_str()?;
    let happened = match (event_type, status) {
        (CHECK_FAILED_TYPE, "failed") => Happened::Failed {
            returned_to: match content.get("returned_to")? {
                Value::Null => None,
                Value::String(s) if !s.is_empty() => Some(s.clone()),
                _ => return None,
            },
        },
        (CHECK_OVERRIDDEN_TYPE, "overridden") => Happened::Overridden {
            by: safe_string_field(content, "overridden_by", WHO_MAX_CHARS)
                .map(|who| who.split_whitespace().collect::<Vec<_>>().join(" "))
                .filter(|who| !who.is_empty()),
        },
        _ => return None,
    };
    Some(Notice {
        board_id: id_field(content, "board_id")?,
        card_id: id_field(content, "card_id")?,
        stage_key: id_field(content, "stage_key")?,
        check_name: check_name.to_string(),
        provider,
        happened,
        references: reference(content),
        sender: item.sender.clone()?,
    })
}

/// The check notices a loaded timeline holds, in timeline order.
pub(crate) struct CheckNotices(Vec<Notice>);

impl CheckNotices {
    pub(crate) fn read<'a>(items: impl Iterator<Item = &'a TimelineItemDto>) -> Self {
        Self(items.filter_map(notice_of).collect())
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

/// A checks.waiting card's view once the notices about it are applied.
///
/// A notice settles the card only when it was sent by the identity that
/// posted the card (anyone in a room can send an event shaped like this one),
/// references exactly this card's event, and names the same board, card and
/// stage. Then, in timeline order:
///
/// - a failure that sent the card back closes it: a receipt, "Returned for
///   changes — CI check “build” failed". Its checks are over; the next
///   arrival's are new.
/// - a failure that could not send it back marks the check failed, and
///   Override stays on it.
/// - an override marks the check overridden, by whom when the hub named
///   them, and withdraws its Override.
///
/// A card with no check left to override is a receipt too. Any other view is
/// returned unchanged.
pub(crate) fn apply_to_view(
    notices: &CheckNotices,
    mut view: ItemView,
    event_id: Option<&str>,
    sender: Option<&str>,
) -> ItemView {
    let (Some(event_id), Some(sender)) = (event_id, sender) else {
        return view;
    };
    let ItemView::CustomEvent {
        view:
            CustomEventView::Rendered {
                decision: Some(decision),
                ..
            },
        ..
    } = &mut view
    else {
        return view;
    };
    let Some(waiting) = decision.checks_waiting.as_mut() else {
        return view;
    };
    let about: Vec<&Notice> = notices
        .0
        .iter()
        .filter(|n| {
            n.sender == sender
                && n.references.as_deref() == Some(event_id)
                && n.board_id == waiting.board_id
                && n.card_id == waiting.card_id
                && n.stage_key == waiting.stage_key
        })
        .collect();
    if about.is_empty() {
        return view;
    }
    let mut returned: Option<&Notice> = None;
    for notice in &about {
        let Some(check) = waiting
            .checks
            .iter_mut()
            .find(|c| c.name == notice.check_name)
        else {
            continue;
        };
        match &notice.happened {
            Happened::Failed {
                returned_to: Some(_),
            } => {
                returned = Some(notice);
                break;
            }
            Happened::Failed { returned_to: None } => {
                check.status = CheckStatus::Failed;
                check.overridable = true;
                check.line = check_line(&check.name, check.provider, check.status, None);
            }
            Happened::Overridden { by } => {
                check.status = CheckStatus::Overridden;
                check.overridable = false;
                check.line = check_line(&check.name, check.provider, check.status, by.as_deref());
            }
        }
    }
    let outcome = if let Some(notice) = returned {
        Some(CustomEventOutcome {
            decision: CHECKS_RETURNED.to_string(),
            decided_by: None,
            summary: format!(
                "Returned for changes \u{2014} {} \u{201C}{}\u{201D} failed",
                notice.provider.check_word(),
                notice.check_name
            ),
            prompt: None,
            held: None,
        })
    } else if waiting.checks.iter().all(|c| !c.overridable) {
        let overridden_by = about.iter().rev().find_map(|n| match &n.happened {
            Happened::Overridden { by } => by.clone(),
            Happened::Failed { .. } => None,
        });
        Some(CustomEventOutcome {
            decision: CHECKS_SETTLED.to_string(),
            decided_by: overridden_by,
            summary: format!(
                "No check left to override: {}",
                waiting
                    .checks
                    .iter()
                    .map(|c| c.line.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            ),
            prompt: None,
            held: None,
        })
    } else {
        None
    };
    match outcome {
        Some(outcome) => crate::gate_outcome::as_receipt(view, &outcome),
        None => view,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event};
    use crate::dto::{CustomPayload, TimelineRow};
    use crate::embedded::{embedded_suite_event, reconcile};
    use serde_json::json;

    const SPEAKER: &str = "@board_checks:hs";
    const WAITING_EVENT: &str = "$checksWaitingEventId";

    fn corpus() -> Value {
        serde_json::from_str(include_str!(
            "../../../fixtures/ecosystem-identity/matrix_gate_events.json"
        ))
        .unwrap()
    }

    fn entry(suite_type: &str) -> Value {
        corpus()["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type && e["variant"] == "checks")
            .unwrap_or_else(|| panic!("the corpus pins a checks {suite_type}"))
            .clone()
    }

    fn accept(suite_type: &str, i: usize) -> Value {
        entry(suite_type)["accept"][i]["content"].clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    /// The corpus's gate with checks, read as a card.
    fn checked_gate(content: &Value) -> Option<crate::gate_v2::GateV2Decision> {
        crate::gate_v2::parse(content, body(content)).map(|p| p.decision_for_test())
    }

    #[test]
    fn a_gates_checks_are_carried_as_plain_lines() {
        let gate = checked_gate(&accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0))
            .expect("the corpus's gate with checks draws");
        let lines: Vec<&str> = gate.checks.iter().map(|c| c.line.as_str()).collect();
        assert_eq!(
            lines,
            [
                "build (CI) \u{2014} passed",
                "quality (verdict) \u{2014} passed",
                "claims (verdict) \u{2014} overridden",
            ]
        );
        assert_eq!(
            gate.checks[0].url.as_deref(),
            Some("https://forge.example/acme/app/actions/runs/412")
        );
        assert_eq!(gate.checks[1].url, None);
        assert!(
            gate.checks.iter().all(|c| !c.overridable),
            "a gate.v2 offers no override"
        );
    }

    #[test]
    fn a_gate_offers_no_override_even_on_a_check_still_open() {
        // The board opens a gate only once every check is over; a gate that
        // says otherwise still offers nothing to override: an override must
        // reference a card of overdue checks.
        let mut content = accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0);
        content["checks"][0]["status"] = json!("pending");
        content["checks"][1]["status"] = json!("failed");
        let gate = checked_gate(&content).expect("still drawn");
        assert!(gate.checks.iter().all(|c| !c.overridable));
    }

    #[test]
    fn a_gate_without_checks_carries_none() {
        let mut content = accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0);
        content.as_object_mut().unwrap().remove("checks");
        assert_eq!(checked_gate(&content).unwrap().checks, Vec::new());
    }

    #[test]
    fn every_checks_list_the_corpus_refuses_makes_the_v2_card_unreadable() {
        let rejects = entry(crate::gate_v2::GATE_V2_EVENT_TYPE)["reject"]
            .as_array()
            .unwrap()
            .clone();
        assert_eq!(rejects.len(), 8, "the corpus grew; read the new case");
        for case in rejects {
            let content = &case["content"];
            assert!(
                checked_gate(content).is_none(),
                "drew a gate the corpus refuses: {}",
                case["why"]
            );
            // The v1 card is drawn in its place: the gate.v2 is not chosen.
            assert_ne!(
                embedded_suite_event(content).map(|(t, _)| t),
                Some(crate::gate_v2::GATE_V2_EVENT_TYPE),
                "{}",
                case["why"]
            );
        }
    }

    #[test]
    fn a_link_that_is_not_https_is_dropped_and_the_check_kept() {
        let mut content = accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0);
        content["checks"][0]["url"] = json!("http://forge.example/run/412");
        content["checks"][1]["url"] = json!("javascript:alert(1)");
        let gate = checked_gate(&content).expect("still drawn");
        assert_eq!(gate.checks.len(), 3);
        assert_eq!(gate.checks[0].url, None);
        assert_eq!(gate.checks[1].url, None);
    }

    // ---- the checks.waiting card

    fn waiting_content() -> Value {
        accept(CHECKS_WAITING_TYPE, 0)
    }

    fn rendered(content: &Value) -> CustomEventView {
        let payload = embedded_payload(content).expect("a valid checks.waiting");
        resolve_custom_event(
            default_registry(),
            Some(CHECKS_WAITING_TYPE),
            Some(&payload),
            body(content),
        )
    }

    fn waiting_decision(content: &Value) -> CustomEventDecision {
        match rendered(content) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    #[test]
    fn the_corpus_checks_waiting_card_offers_override_on_the_pending_check_only() {
        let content = waiting_content();
        assert_eq!(
            embedded_suite_event(&content).map(|(t, _)| t),
            Some(CHECKS_WAITING_TYPE)
        );
        let decision = waiting_decision(&content);
        assert_eq!(
            decision.prompt,
            "Checks overdue on \u{201C}Ship the release\u{201D}"
        );
        assert!(decision.options.is_empty(), "no plain reply overrides");
        let waiting = decision.checks_waiting.unwrap();
        assert_eq!(waiting.board_id, "brd_7c1f");
        assert_eq!(waiting.card_id, "crd_8a40");
        assert_eq!(waiting.stage_key, "review");
        assert_eq!(waiting.override_note, OVERRIDE_NOTE);
        let shown: Vec<(&str, bool)> = waiting
            .checks
            .iter()
            .map(|c| (c.line.as_str(), c.overridable))
            .collect();
        assert_eq!(
            shown,
            [
                ("build (CI) \u{2014} passed", false),
                ("quality (verdict) \u{2014} pending", true),
            ]
        );
    }

    #[test]
    fn a_checks_waiting_card_the_hub_would_not_post_stays_prose() {
        let mut cases = Vec::new();
        for key in ["board_id", "card_id", "stage_key", "card_title", "checks"] {
            let mut c = waiting_content();
            c.as_object_mut().unwrap().remove(key);
            cases.push((key.to_string(), c));
        }
        let mut c = waiting_content();
        c["checks"] = json!([]);
        cases.push(("no checks".into(), c));
        let mut c = waiting_content();
        c["checks"][1]["name"] = json!("build");
        cases.push(("two of one name".into(), c));
        let mut c = waiting_content();
        c["checks"][1]["status"] = json!("skipped");
        cases.push(("an unknown status".into(), c));
        let mut c = waiting_content();
        c["body"] = json!("  ");
        cases.push(("no body".into(), c));
        for (why, content) in cases {
            assert!(parse_waiting(&content, body(&content)).is_none(), "{why}");
            assert_eq!(embedded_suite_event(&content), None, "{why}");
        }
    }

    #[test]
    fn an_override_is_the_corpus_accept_and_needs_a_comment() {
        let waiting = waiting_decision(&waiting_content()).checks_waiting.unwrap();
        let sent = override_request(
            &waiting,
            "quality",
            " the grader is down; read it myself ",
            WAITING_EVENT,
        )
        .unwrap();
        assert_eq!(sent, accept(CHECK_OVERRIDE_SUITE_TYPE, 0));
        for case in entry(CHECK_OVERRIDE_SUITE_TYPE)["reject"]
            .as_array()
            .unwrap()
        {
            let comment = case["content"]["comment"].as_str().unwrap_or("");
            assert!(
                override_request(&waiting, "quality", comment, WAITING_EVENT).is_err(),
                "sent an override the corpus refuses: {}",
                case["why"]
            );
        }
        assert!(override_request(&waiting, "quality", &"x".repeat(8193), WAITING_EVENT).is_err());
        assert!(override_request(&waiting, "quality", "why", "not an event").is_err());
    }

    #[test]
    fn only_a_check_the_card_offers_override_on_is_sent() {
        let waiting = waiting_decision(&waiting_content()).checks_waiting.unwrap();
        // Passed: nothing to override.
        assert!(override_request(&waiting, "build", "why", WAITING_EVENT).is_err());
        // Not on this card.
        assert!(override_request(&waiting, "coverage", "why", WAITING_EVENT).is_err());
    }

    /// A message projected the way the timeline projects one: from its raw
    /// event, through `timeline::row_from_parts`.
    fn projected(content: Value) -> TimelineRow {
        use matrix_sdk::ruma::events::AnySyncTimelineEvent;
        use matrix_sdk::ruma::serde::Raw;
        let raw: Raw<AnySyncTimelineEvent> = Raw::from_json(
            serde_json::value::to_raw_value(&json!({
                "type": "m.room.message",
                "event_id": WAITING_EVENT,
                "sender": SPEAKER,
                "origin_server_ts": 1,
                "content": content,
            }))
            .unwrap(),
        );
        let dto = item(WAITING_EVENT, "message", Some("m.text"), SPEAKER);
        crate::timeline::row_from_parts(dto, Some(&raw), matrix_sdk::ruma::user_id!("@me:hs"))
    }

    #[test]
    fn a_checks_waiting_card_is_recognised_from_its_raw_event() {
        let row = projected(waiting_content());
        assert_eq!(row.item.detail.as_deref(), Some(CHECKS_WAITING_TYPE));
        assert!(pending(&row).checks[1].overridable);
    }

    #[test]
    fn five_checks_with_long_links_still_draw() {
        // Five links near the 2,048-character bound are more than an ordinary
        // custom event's 8 KiB, and well inside the envelope's own bound.
        let mut content = waiting_content();
        let long = format!("https://forge.example/{}", "a".repeat(2_000));
        content["checks"] = json!((0..5)
            .map(|i| json!({ "name": format!("c{i}"), "provider": "ci", "status": "pending", "url": long }))
            .collect::<Vec<_>>());
        let row = projected(content);
        assert_eq!(row.item.detail.as_deref(), Some(CHECKS_WAITING_TYPE));
        let waiting = pending(&row);
        assert_eq!(waiting.checks.len(), 5);
        assert_eq!(waiting.checks[4].url.as_deref(), Some(long.as_str()));
    }

    #[test]
    fn a_card_posted_again_below_retires_the_copy_above() {
        // An admin the escalation invited joins; the hub posts the card again
        // from a fresh read and moves its record to the new event.
        let mut below = waiting_row();
        below.item.id = "$below".into();
        below.item.event_id = Some("$below".into());
        let mut rows = vec![waiting_row(), below];
        reconcile(&mut rows);
        assert_eq!(
            receipt(&rows[0]).summary,
            crate::embedded::MOVED_BELOW_SUMMARY
        );
        assert!(pending(&rows[1]).checks[1].overridable);
    }

    // ---- notices settle the card

    fn item(id: &str, kind: &str, msgtype: Option<&str>, sender: &str) -> TimelineItemDto {
        crate::timeline::project_item_parts(
            id,
            Some(id),
            kind,
            msgtype,
            None,
            Some(sender),
            None,
            None,
            false,
            Some("Checks overdue"),
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

    fn waiting_row() -> TimelineRow {
        let (event_type, payload) = embedded_suite_event(&waiting_content()).unwrap();
        let mut it = item(WAITING_EVENT, "message", Some("m.text"), SPEAKER);
        it.detail = Some(event_type.into());
        it.custom_payload = Some(CustomPayload(payload));
        TimelineRow::new(it)
    }

    fn notice_row(id: &str, sender: &str, event_type: &str, content: Value) -> TimelineRow {
        let mut it = item(id, "customMessage", None, sender);
        it.detail = Some(event_type.into());
        it.custom_payload = Some(CustomPayload(content));
        TimelineRow::new(it)
    }

    fn settle(extra: Vec<TimelineRow>) -> Vec<TimelineRow> {
        let mut rows = vec![waiting_row()];
        rows.extend(extra);
        reconcile(&mut rows);
        rows
    }

    fn pending(row: &TimelineRow) -> ChecksWaitingDecision {
        match &row.view {
            ItemView::CustomEvent {
                view:
                    CustomEventView::Rendered {
                        decision: Some(d),
                        outcome: None,
                        ..
                    },
                ..
            } => d.checks_waiting.clone().expect("a checks card"),
            other => panic!("expected a pending checks card, got {other:?}"),
        }
    }

    fn receipt(row: &TimelineRow) -> CustomEventOutcome {
        match &row.view {
            ItemView::CustomEvent {
                view:
                    CustomEventView::Rendered {
                        decision: None,
                        outcome: Some(o),
                        ..
                    },
                ..
            } => o.clone(),
            other => panic!("expected a receipt, got {other:?}"),
        }
    }

    /// The corpus's overridden notice, replying to the waiting card.
    fn overridden() -> Value {
        accept(CHECK_OVERRIDDEN_TYPE, 0)
    }

    /// The corpus's failed notice that could not send the card back.
    fn failed_held() -> Value {
        accept(CHECK_FAILED_TYPE, 1)
    }

    /// A failure that sent the card back, referencing the waiting card.
    fn failed_returned() -> Value {
        let mut c = accept(CHECK_FAILED_TYPE, 0);
        c["m.relates_to"] = json!({ "rel_type": "m.reference", "event_id": WAITING_EVENT });
        c
    }

    #[test]
    fn notices_are_never_rows() {
        let rows = settle(vec![
            notice_row("$o", SPEAKER, CHECK_OVERRIDDEN_TYPE, overridden()),
            notice_row(
                "$f",
                SPEAKER,
                CHECK_FAILED_TYPE,
                accept(CHECK_FAILED_TYPE, 0),
            ),
        ]);
        assert_eq!(rows[1].view, ItemView::None);
        assert_eq!(rows[2].view, ItemView::None);
    }

    #[test]
    fn an_override_of_the_last_open_check_settles_the_card() {
        let rows = settle(vec![notice_row(
            "$o",
            SPEAKER,
            CHECK_OVERRIDDEN_TYPE,
            overridden(),
        )]);
        let outcome = receipt(&rows[0]);
        assert_eq!(outcome.decision, CHECKS_SETTLED);
        assert_eq!(outcome.decided_by.as_deref(), Some("rakesh"));
        assert_eq!(
            outcome.summary,
            "No check left to override: build (CI) \u{2014} passed, quality (verdict) \u{2014} overridden by rakesh"
        );
    }

    #[test]
    fn a_failure_that_could_not_go_back_keeps_override_on_the_check() {
        let rows = settle(vec![notice_row(
            "$f",
            SPEAKER,
            CHECK_FAILED_TYPE,
            failed_held(),
        )]);
        let waiting = pending(&rows[0]);
        assert_eq!(waiting.checks[1].status, CheckStatus::Failed);
        assert!(waiting.checks[1].overridable);
        assert_eq!(waiting.checks[1].line, "quality (verdict) \u{2014} failed");
    }

    #[test]
    fn a_failure_that_sent_the_card_back_closes_it() {
        let rows = settle(vec![notice_row(
            "$f",
            SPEAKER,
            CHECK_FAILED_TYPE,
            failed_returned(),
        )]);
        let outcome = receipt(&rows[0]);
        assert_eq!(outcome.decision, CHECKS_RETURNED);
        assert_eq!(
            outcome.summary,
            "Returned for changes \u{2014} CI check \u{201C}build\u{201D} failed"
        );
    }

    #[test]
    fn a_notice_from_anyone_else_settles_nothing() {
        let rows = settle(vec![
            notice_row("$o", "@mallory:hs", CHECK_OVERRIDDEN_TYPE, overridden()),
            notice_row("$f", "@mallory:hs", CHECK_FAILED_TYPE, failed_returned()),
        ]);
        let waiting = pending(&rows[0]);
        assert_eq!(waiting.checks[1].status, CheckStatus::Pending);
        assert!(waiting.checks[1].overridable);
    }

    #[test]
    fn a_notice_about_another_card_or_with_no_reference_settles_nothing() {
        let mut other_card = overridden();
        other_card["card_id"] = json!("crd_other");
        let mut unreferenced = overridden();
        unreferenced.as_object_mut().unwrap().remove("m.relates_to");
        let mut other_stage = failed_returned();
        other_stage["stage_key"] = json!("ship");
        let rows = settle(vec![
            notice_row("$a", SPEAKER, CHECK_OVERRIDDEN_TYPE, other_card),
            notice_row("$b", SPEAKER, CHECK_OVERRIDDEN_TYPE, unreferenced),
            notice_row("$c", SPEAKER, CHECK_FAILED_TYPE, other_stage),
        ]);
        assert_eq!(pending(&rows[0]).checks[1].status, CheckStatus::Pending);
    }
}
