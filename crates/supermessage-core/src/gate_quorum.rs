//! A gate that needs more than one approver.
//!
//! charter → `decisions/2026-11-09-a-gate-may-need-more-than-one-approver.md`.
//! A board stage may require a quorum: two to five approvals before its gate
//! passes. superpipeline counts them; the AgentPod hub carries the count, and
//! this client draws it. Three things cross the wire:
//!
//! - **The gate.v2's `quorum`** — `{ required, from, approvals: [{
//!   principal_id, at, variant_id? }] }` — the count when the card was posted.
//! - **A progress event** ([`GATE_PROGRESS_TYPE`]) the hub sends for each
//!   approval short of the quorum, beside a readable reply ("Approved by
//!   rakesh — 1 of 2. Waiting for 1 more."). It carries the quorum again, as
//!   the board now has it, and references the gate's event.
//! - **The reader's own decision.v2**, which says this reader has approved.
//!
//! The approval that reaches the quorum resolves the gate, and the gate's
//! outcome closes the card as any outcome does (`crate::gate_outcome`). This
//! module only says how far a gate still open has got.
//!
//! ## The count is the board's, never walked back
//!
//! The card shows the quorum with the most approvals among the gate.v2's own
//! and every progress event for it — a redelivered or reordered progress event
//! carries fewer, and a count that went down would be a lie. A progress event
//! counts only from the identity that posted the gate, as an outcome does:
//! anyone in a room can send one, and a forged count is exactly what a
//! forger would send.
//!
//! ## "You approved" is read from the room, not from an id
//!
//! A quorum's approvals name Accounts principals, which this client does not
//! know its reader by. What it does know is its own decision.v2 in the room:
//! a decision the reader sent (`is_own`), naming this gate, with an option the
//! gate offers as an approval. On a quorum gate still open, that reader is
//! not offered approval again — superpipeline would refuse it
//! (`QUORUM_ALREADY_APPROVED`) — and the card says why. Request changes and
//! reject stay: one of either still closes the gate (charter §4).
//!
//! The one case this reads wrong is an approval superpipeline refused for
//! another reason (a decider not allowed, a stale subject). The hub says so in
//! the room, and the card then sends the reader to the board, which is where
//! such a gate is answered anyway.

use std::collections::{HashMap, HashSet};

use serde_json::Value;

use crate::dto::TimelineItemDto;
use crate::gate_v2::{GateOptionMeaning, GateV2Decision, GATE_DECISION_V2_SUITE_TYPE};

/// The hub's structured progress event (agentpod `gate-quorum-wire.ts`).
pub const GATE_PROGRESS_TYPE: &str = "dev.agentpod.gate.progress.v2";

/// The board's bounds on `required` (charter §1).
const QUORUM_MIN: u32 = 2;
const QUORUM_MAX: u32 = 5;
/// The hub's bound on recorded approvals (`QUORUM_APPROVALS_MAX`).
const QUORUM_APPROVALS_MAX: usize = 32;
const PRINCIPAL_ID_MAX_CHARS: usize = 128;
const VARIANT_ID_MAX_CHARS: usize = 64;

/// Who may approve a quorum gate (charter §1).
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum QuorumFrom {
    /// The stage's named deciders.
    Deciders,
    /// The workspace's owners and admins.
    Admins,
}

/// One approval the board has recorded.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateQuorumApproval {
    /// Compared, never drawn: an Accounts principal, not a name.
    pub principal_id: String,
    /// When, as the board said it (an ISO timestamp).
    pub at: String,
    /// The version it approved, on a variant gate.
    pub variant_id: Option<String>,
}

/// How far a quorum gate has got, as a host draws it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateQuorum {
    /// Approvals the gate needs, 2 to 5.
    pub required: u32,
    pub from: QuorumFrom,
    /// Recorded so far, each person once.
    pub approvals: Vec<GateQuorumApproval>,
    /// The line a card draws under its question: "1 of 2 approvals. Waiting
    /// for 1 more." Worded here so no host words it differently.
    pub progress: String,
    /// The reader has approved this gate already, so approving options are
    /// gone and `GateV2Decision::approvals_withheld` says why.
    pub approved_by_you: bool,
}

fn bounded_id(v: Option<&Value>, max_chars: usize) -> Option<String> {
    v?.as_str()
        .filter(|s| !s.is_empty() && s.chars().count() <= max_chars)
        .map(str::to_string)
}

fn approval_of(raw: &Value) -> Option<GateQuorumApproval> {
    let principal_id = bounded_id(raw.get("principal_id"), PRINCIPAL_ID_MAX_CHARS)?;
    let at = raw.get("at")?.as_str()?;
    crate::decision_timeout::iso_ms(at)?;
    let variant_id = match raw.get("variant_id") {
        None | Some(Value::Null) => None,
        Some(v) => Some(bounded_id(Some(v), VARIANT_ID_MAX_CHARS)?),
    };
    Some(GateQuorumApproval {
        principal_id,
        at: at.to_string(),
        variant_id,
    })
}

/// `content.quorum`: `Ok(None)` when absent, `Err(())` when present and not
/// one the hub would carry — `required` not a whole number from 2 to 5,
/// `from` neither `deciders` nor `admins`, approvals not a list, too many,
/// any one malformed, or one person twice. Any of these makes the whole
/// gate.v2 unreadable, so the v1 card is drawn: a card that hid a quorum would
/// tell its reader one approval closes it.
pub(crate) fn parse_quorum(
    content: &Value,
) -> Result<Option<(u32, QuorumFrom, Vec<GateQuorumApproval>)>, ()> {
    let Some(raw) = content.get("quorum") else {
        return Ok(None);
    };
    read_quorum(raw).map(Some).ok_or(())
}

fn read_quorum(raw: &Value) -> Option<(u32, QuorumFrom, Vec<GateQuorumApproval>)> {
    let required = u32::try_from(raw.get("required")?.as_u64()?).ok()?;
    if !(QUORUM_MIN..=QUORUM_MAX).contains(&required) {
        return None;
    }
    let from = match raw.get("from")?.as_str()? {
        "deciders" => QuorumFrom::Deciders,
        "admins" => QuorumFrom::Admins,
        _ => return None,
    };
    let list = raw.get("approvals")?.as_array()?;
    if list.len() > QUORUM_APPROVALS_MAX {
        return None;
    }
    let mut approvals: Vec<GateQuorumApproval> = Vec::with_capacity(list.len());
    for entry in list {
        let approval = approval_of(entry)?;
        if approvals
            .iter()
            .any(|a| a.principal_id == approval.principal_id)
        {
            return None;
        }
        approvals.push(approval);
    }
    Some((required, from, approvals))
}

/// The progress line. On a variant gate approvals count per version (charter
/// §5), so each version with any is named with its own count.
pub(crate) fn progress_line(
    required: u32,
    approvals: &[GateQuorumApproval],
    variants: &[(String, String)],
) -> String {
    let count = |variant: Option<&str>| {
        approvals
            .iter()
            .filter(|a| a.variant_id.as_deref() == variant)
            .count() as u32
    };
    let line = |n: u32| {
        let more = required.saturating_sub(n).max(1);
        format!("{n} of {required} approvals. Waiting for {more} more.")
    };
    if variants.is_empty() {
        let n = count(None);
        return if n == 0 {
            format!("Needs {required} approvals.")
        } else {
            line(n)
        };
    }
    let counted: Vec<String> = variants
        .iter()
        .filter_map(|(id, label)| {
            let n = count(Some(id));
            (n > 0).then(|| format!("\u{201C}{label}\u{201D}: {}", line(n)))
        })
        .collect();
    if counted.is_empty() {
        format!("Needs {required} approvals of the same version.")
    } else {
        counted.join(" ")
    }
}

/// What a card says in place of approving options once its reader approved.
/// No count: the reader's own approval may not be in the board's count the
/// card has yet, and the progress line beside it says how far it has got.
pub const YOU_APPROVED_NOTE: &str = "You approved. It passes once enough others approve too.";

/// A progress event as read off a row.
struct Progress {
    gate_id: String,
    board_id: Option<String>,
    sender: String,
    required: u32,
    approvals: Vec<GateQuorumApproval>,
}

fn progress_of(item: &TimelineItemDto) -> Option<Progress> {
    if item.kind != "customMessage" || item.detail.as_deref() != Some(GATE_PROGRESS_TYPE) {
        return None;
    }
    let content = &item.custom_payload.as_ref()?.0;
    let (required, _, approvals) = read_quorum(content.get("quorum")?)?;
    Some(Progress {
        gate_id: crate::gate_v2::id_field(content, "gate_id")?,
        board_id: crate::gate_v2::id_field(content, "board_id"),
        sender: item.sender.clone()?,
        required,
        approvals,
    })
}

/// The gate and option of a decision.v2 the reader sent, or `None`.
fn own_decision_of(item: &TimelineItemDto) -> Option<(String, String)> {
    if !item.is_own || item.detail.as_deref() != Some(GATE_DECISION_V2_SUITE_TYPE) {
        return None;
    }
    let content = &item.custom_payload.as_ref()?.0;
    Some((
        crate::gate_v2::id_field(content, "gate_id")?,
        crate::gate_v2::id_field(content, "option_id")?,
    ))
}

/// Every progress event and own decision on the loaded timeline, read once
/// per reconcile and applied to each open gate card by [`QuorumState::apply`].
#[derive(Default)]
pub(crate) struct QuorumState {
    progress: Vec<Progress>,
    /// gate id → option ids the reader answered it with.
    own: HashMap<String, HashSet<String>>,
}

impl QuorumState {
    pub(crate) fn read(
        items: impl Iterator<Item = impl std::ops::Deref<Target = TimelineItemDto>>,
    ) -> Self {
        let mut state = Self::default();
        for item in items {
            if let Some(p) = progress_of(&item) {
                state.progress.push(p);
            } else if let Some((gate, option)) = own_decision_of(&item) {
                state.own.entry(gate).or_default().insert(option);
            }
        }
        state
    }

    pub(crate) fn is_empty(&self) -> bool {
        self.progress.is_empty() && self.own.is_empty()
    }

    /// `gate` as it stands now: the furthest count the board has said, from
    /// the gate's own sender, and approvals withheld if the reader approved.
    /// A gate with no quorum is returned as it was.
    pub(crate) fn apply(
        &self,
        gate: &mut GateV2Decision,
        sender: Option<&str>,
        board_id: Option<&str>,
    ) {
        let Some(quorum) = gate.quorum.as_mut() else {
            return;
        };
        let newer = self
            .progress
            .iter()
            .filter(|p| p.gate_id == gate.gate_id && Some(p.sender.as_str()) == sender)
            .filter(|p| match (p.board_id.as_deref(), board_id) {
                (Some(ours), Some(theirs)) => ours == theirs,
                _ => true,
            })
            .filter(|p| p.required == quorum.required)
            .filter(|p| p.approvals.len() > quorum.approvals.len())
            .max_by_key(|p| p.approvals.len());
        if let Some(p) = newer {
            quorum.approvals = p.approvals.clone();
        }
        let variants: Vec<(String, String)> = gate
            .variants
            .iter()
            .map(|v| (v.id.clone(), v.label.clone()))
            .collect();
        quorum.progress = progress_line(quorum.required, &quorum.approvals, &variants);

        let approved = self.own.get(&gate.gate_id).is_some_and(|answered| {
            gate.options
                .iter()
                .any(|o| o.meaning == GateOptionMeaning::Approve && answered.contains(&o.id))
        });
        if approved {
            quorum.approved_by_you = true;
            gate.options
                .retain(|o| o.meaning != GateOptionMeaning::Approve);
            gate.approvals_withheld = Some(YOU_APPROVED_NOTE.to_string());
        }
    }
}

/// `view` with its gate's quorum brought up to date ([`QuorumState::apply`]),
/// when it is a gate.v2 card with one; anything else as it was.
pub(crate) fn apply_to_view(
    state: &QuorumState,
    mut view: crate::item_view::ItemView,
    sender: Option<&str>,
    board_id: Option<&str>,
) -> crate::item_view::ItemView {
    if let crate::item_view::ItemView::CustomEvent {
        view:
            crate::custom_events::CustomEventView::Rendered {
                decision: Some(decision),
                ..
            },
        ..
    } = &mut view
    {
        if let Some(gate) = decision.gate_v2.as_mut() {
            let approving: Vec<String> = gate
                .options
                .iter()
                .filter(|o| o.meaning == GateOptionMeaning::Approve)
                .map(|o| o.id.clone())
                .collect();
            state.apply(gate, sender, board_id);
            if gate.quorum.as_ref().is_some_and(|q| q.approved_by_you) {
                // The options a host without v2 answers with, too: the
                // desktop's buttons are these.
                decision.options.retain(|o| !approving.contains(&o.id));
            }
        }
    }
    view
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::CustomEventView;
    use crate::dto::{CustomPayload, TimelineRow};
    use crate::item_view::ItemView;
    use serde_json::json;

    const SPEAKER: &str = "@board_release:hs";
    const ME: &str = "@me:hs";

    fn corpus() -> Value {
        serde_json::from_str(include_str!(
            "../../../fixtures/ecosystem-identity/matrix_gate_events.json"
        ))
        .unwrap()
    }

    /// The corpus's quorum entry for `suite_type`.
    fn quorum_entry(suite_type: &str) -> Value {
        corpus()["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type && e["variant"] == "quorum")
            .unwrap_or_else(|| panic!("the corpus pins a quorum {suite_type}"))
            .clone()
    }

    fn quorum_gate() -> Value {
        quorum_entry(crate::gate_v2::GATE_V2_EVENT_TYPE)["accept"][0]["content"].clone()
    }

    fn progress_content() -> Value {
        quorum_entry(GATE_PROGRESS_TYPE)["accept"][0]["content"].clone()
    }

    fn item(id: &str, kind: &str, msgtype: Option<&str>, sender: &str) -> TimelineItemDto {
        let mut it = crate::timeline::project_item_parts(
            id,
            Some(&format!("${id}")),
            kind,
            msgtype,
            None,
            Some(sender),
            None,
            None,
            false,
            Some("Approval needed"),
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
        );
        it.is_own = sender == ME;
        it
    }

    /// The gate's prose message, as the timeline carries a gate.v2.
    fn gate_row(content: &Value) -> TimelineRow {
        let (event_type, payload) = crate::embedded::embedded_suite_event(content).unwrap();
        let mut it = item("gate", "message", Some("m.text"), SPEAKER);
        it.detail = Some(event_type.into());
        it.custom_payload = Some(CustomPayload(payload));
        TimelineRow::new(it)
    }

    fn progress_row(id: &str, sender: &str, content: Value) -> TimelineRow {
        let mut it = item(id, "customMessage", None, sender);
        it.detail = Some(GATE_PROGRESS_TYPE.into());
        it.custom_payload = Some(CustomPayload(content));
        TimelineRow::new(it)
    }

    fn decision_row(id: &str, sender: &str, option_id: &str) -> TimelineRow {
        let mut it = item(id, "message", Some("m.text"), sender);
        it.detail = Some(GATE_DECISION_V2_SUITE_TYPE.into());
        it.custom_payload = Some(CustomPayload(json!({
            "msgtype": "m.text",
            "body": "Approved",
            "schema_version": 1,
            "suite_event_type": GATE_DECISION_V2_SUITE_TYPE,
            "gate_id": "gate_6e19",
            "option_id": option_id,
            "m.relates_to": { "rel_type": "m.reference", "event_id": "$gate" }
        })));
        TimelineRow::new(it)
    }

    fn gate_of(row: &TimelineRow) -> GateV2Decision {
        match &row.view {
            ItemView::CustomEvent {
                view:
                    CustomEventView::Rendered {
                        decision: Some(d), ..
                    },
                ..
            } => d.gate_v2.clone().expect("a gate.v2 card"),
            other => panic!("expected a gate card, got {other:?}"),
        }
    }

    fn option_ids(gate: &GateV2Decision) -> Vec<&str> {
        gate.options.iter().map(|o| o.id.as_str()).collect()
    }

    fn approval(principal: &str) -> Value {
        json!({ "principal_id": principal, "at": "2026-11-09T10:05:00.000Z" })
    }

    #[test]
    fn the_corpus_quorum_gate_draws_its_count() {
        let rows = [gate_row(&quorum_gate())];
        let gate = gate_of(&rows[0]);
        let quorum = gate.quorum.clone().expect("the quorum is carried");
        assert_eq!(quorum.required, 2);
        assert_eq!(quorum.from, QuorumFrom::Admins);
        assert_eq!(quorum.approvals.len(), 1);
        assert_eq!(quorum.approvals[0].principal_id, "prn_first");
        assert_eq!(quorum.progress, "1 of 2 approvals. Waiting for 1 more.");
        assert!(!quorum.approved_by_you);
        // Nobody has said this reader approved: approval is offered.
        assert_eq!(option_ids(&gate), ["approve", "request_changes", "reject"]);
        assert_eq!(gate.approvals_withheld, None);
    }

    #[test]
    fn a_gate_without_a_quorum_carries_none() {
        let mut content = quorum_gate();
        content.as_object_mut().unwrap().remove("quorum");
        assert_eq!(gate_of(&gate_row(&content)).quorum, None);
    }

    #[test]
    fn every_quorum_the_corpus_refuses_makes_the_v2_card_unreadable() {
        let entry = quorum_entry(crate::gate_v2::GATE_V2_EVENT_TYPE);
        let rejects = entry["reject"].as_array().unwrap();
        assert!(!rejects.is_empty());
        for case in rejects {
            let why = case["why"].as_str().unwrap();
            let content = &case["content"];
            let body = content["body"].as_str();
            assert!(
                crate::gate_v2::parse(content, body).is_none(),
                "drew a gate the corpus refuses: {why}"
            );
        }
    }

    #[test]
    fn the_progress_line_counts_toward_what_is_required() {
        let one = vec![GateQuorumApproval {
            principal_id: "a".into(),
            at: "2026-11-09T10:00:00Z".into(),
            variant_id: None,
        }];
        assert_eq!(progress_line(2, &[], &[]), "Needs 2 approvals.");
        assert_eq!(
            progress_line(3, &one, &[]),
            "1 of 3 approvals. Waiting for 2 more."
        );
        // Per version on a choice (charter §5): only versions with any.
        let mut short = one.clone();
        short[0].variant_id = Some("v_short".into());
        let variants = vec![
            ("v_short".to_string(), "Short".to_string()),
            ("v_long".to_string(), "Long".to_string()),
        ];
        assert_eq!(
            progress_line(2, &short, &variants),
            "\u{201C}Short\u{201D}: 1 of 2 approvals. Waiting for 1 more."
        );
        assert_eq!(
            progress_line(2, &[], &variants),
            "Needs 2 approvals of the same version."
        );
    }

    #[test]
    fn a_progress_event_from_the_gates_sender_moves_the_count() {
        let mut gate = quorum_gate();
        gate["quorum"]["required"] = 3.into();
        let mut progress = progress_content();
        progress["quorum"]["required"] = 3.into();
        progress["quorum"]["approvals"] = json!([approval("prn_first"), approval("prn_second")]);
        let mut rows = vec![gate_row(&gate), progress_row("p", SPEAKER, progress)];
        assert_eq!(crate::embedded::reconcile(&mut rows), vec![0]);
        let quorum = gate_of(&rows[0]).quorum.unwrap();
        assert_eq!(quorum.approvals.len(), 2);
        assert_eq!(quorum.progress, "2 of 3 approvals. Waiting for 1 more.");
        // Settled: a second pass changes nothing.
        assert!(crate::embedded::reconcile(&mut rows).is_empty());
        // The progress event itself is never a row of its own.
        assert_eq!(rows[1].view, ItemView::None);
    }

    #[test]
    fn a_progress_event_from_anyone_else_counts_nothing() {
        let mut progress = progress_content();
        progress["quorum"]["required"] = 2.into();
        progress["quorum"]["approvals"] = json!([approval("prn_first"), approval("prn_x")]);
        let mut rows = vec![
            gate_row(&quorum_gate()),
            progress_row("forged", "@mallory:hs", progress),
        ];
        crate::embedded::reconcile(&mut rows);
        assert_eq!(gate_of(&rows[0]).quorum.unwrap().approvals.len(), 1);
    }

    #[test]
    fn a_late_progress_event_never_walks_the_count_back() {
        let mut gate = quorum_gate();
        gate["quorum"]["required"] = 4.into();
        gate["quorum"]["approvals"] = json!([approval("a"), approval("b"), approval("c")]);
        let mut earlier = progress_content();
        earlier["quorum"]["required"] = 4.into();
        earlier["quorum"]["approvals"] = json!([approval("a")]);
        let mut rows = vec![gate_row(&gate), progress_row("late", SPEAKER, earlier)];
        crate::embedded::reconcile(&mut rows);
        assert_eq!(gate_of(&rows[0]).quorum.unwrap().approvals.len(), 3);
    }

    #[test]
    fn a_reader_who_approved_is_not_offered_approval_again() {
        let mut rows = vec![
            gate_row(&quorum_gate()),
            decision_row("mine", ME, "approve"),
        ];
        assert_eq!(crate::embedded::reconcile(&mut rows), vec![0]);
        let gate = gate_of(&rows[0]);
        let quorum = gate.quorum.as_ref().unwrap();
        assert!(quorum.approved_by_you);
        // Request changes and reject stay: either still closes the gate.
        assert_eq!(option_ids(&gate), ["request_changes", "reject"]);
        assert_eq!(gate.approvals_withheld.as_deref(), Some(YOU_APPROVED_NOTE));
        // Nor from a host that answers with the legacy options.
        let ItemView::CustomEvent {
            view: CustomEventView::Rendered {
                decision: Some(d), ..
            },
            ..
        } = &rows[0].view
        else {
            unreachable!()
        };
        assert!(d.options.iter().all(|o| o.id != "approve"));
        assert!(d.options.iter().any(|o| o.id == "reject"));
        // And an approval cannot be sent from it: the core refuses it.
        assert!(crate::gate_v2::decision_request(&gate, "approve", None, None, "$gate").is_err());
        assert!(crate::gate_v2::decision_request(&gate, "reject", None, None, "$gate").is_ok());
    }

    #[test]
    fn someone_elses_approval_or_the_readers_rejection_leaves_approval_offered() {
        for rows in [
            vec![
                gate_row(&quorum_gate()),
                decision_row("theirs", "@other:hs", "approve"),
            ],
            vec![
                gate_row(&quorum_gate()),
                decision_row("mine", ME, "request_changes"),
            ],
        ] {
            let mut rows = rows;
            crate::embedded::reconcile(&mut rows);
            let gate = gate_of(&rows[0]);
            assert!(!gate.quorum.as_ref().unwrap().approved_by_you);
            assert!(option_ids(&gate).contains(&"approve"));
        }
    }

    #[test]
    fn a_gate_that_passes_on_one_approval_is_untouched_by_the_readers_answer() {
        // Answered and resolved are different facts (`crate::gate_outcome`):
        // a single-approval gate closes on its outcome, never on the answer.
        let mut content = quorum_gate();
        content.as_object_mut().unwrap().remove("quorum");
        let mut rows = vec![gate_row(&content), decision_row("mine", ME, "approve")];
        assert!(crate::embedded::reconcile(&mut rows).is_empty());
        assert!(option_ids(&gate_of(&rows[0])).contains(&"approve"));
    }

    #[test]
    fn the_outcome_still_closes_a_quorum_gate() {
        let mut outcome = item("o", "customMessage", None, SPEAKER);
        outcome.detail = Some(crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE.into());
        outcome.custom_payload = Some(CustomPayload(json!({
            "suite_event_type": crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
            "gate_id": "gate_6e19",
            "board_id": "brd_7c1f",
            "decision": "approve",
            "decided_by": "rakesh",
            "m.relates_to": { "rel_type": "m.reference", "event_id": "$gate" }
        })));
        let mut rows = vec![
            gate_row(&quorum_gate()),
            decision_row("mine", ME, "approve"),
            TimelineRow::new(outcome),
        ];
        crate::embedded::reconcile(&mut rows);
        let ItemView::CustomEvent {
            view: CustomEventView::Rendered {
                decision, outcome, ..
            },
            ..
        } = &rows[0].view
        else {
            panic!("expected a receipt");
        };
        assert!(decision.is_none(), "a receipt offers nothing");
        assert_eq!(outcome.as_ref().unwrap().summary, "Approved by rakesh");
    }

    #[test]
    fn the_corpus_progress_event_is_read() {
        let mut rows = vec![
            gate_row(&{
                let mut g = quorum_gate();
                g["quorum"]["approvals"] = json!([]);
                g
            }),
            progress_row("p", SPEAKER, progress_content()),
        ];
        crate::embedded::reconcile(&mut rows);
        assert_eq!(
            gate_of(&rows[0]).quorum.unwrap().progress,
            "1 of 2 approvals. Waiting for 1 more."
        );
    }
}
