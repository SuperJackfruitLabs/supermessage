//! A gate the room says is over.
//!
//! When superpipeline accepts an answer to a gate, the AgentPod hub says so in
//! the gate's room (agentpod#614/#615): a readable line for every client, and
//! a structured receipt beside it —
//!
//! ```json
//! {
//!   "suite_event_type": "dev.superpipeline.gate.outcome.v1",
//!   "gate_id": "gate_4e8b",
//!   "board_id": "brd_…",
//!   "decision": "approve",
//!   "decided_by": "rakesh",
//!   "m.relates_to": { "rel_type": "m.reference", "event_id": "$theGateEvent" }
//! }
//! ```
//!
//! — as a custom event of type [`GATE_OUTCOME_EVENT_TYPE`], or as an
//! `m.room.message` carrying the same keys. Both are read.
//!
//! ## Why the card closes on this and not on the decision
//!
//! The reader's own `dev.superpipeline.gate.decision.v1` references the gate
//! too, and closing the card on it would need no hub at all. It would also
//! have lied: on 2026-09-28 decision events landed in the room for hours while
//! every resolve was refused (`HTTP_401`, agentpod#613). **Answered** (a
//! person tapped, it was delivered) and **resolved** (superpipeline accepted)
//! are different facts, and only the hub knows the second. The first stays a
//! host's per-device state; this module is the second.
//!
//! ## Which card an outcome closes
//!
//! A gate may be on screen as the prose message carrying
//! `dev.superpipeline.gate` (`crate::embedded`) while its legacy
//! `dev.superpipeline.gate.v1` companion is hidden — and the hub's reference
//! points at whichever event it recorded as the gate's (`matrix_gate_events.
//! event_id`, the companion while legacy events are on). So the reference
//! alone cannot find the visible card. The rule, applied in
//! [`resolved_gates`]:
//!
//! 1. **Same sender.** An outcome counts only from the identity that posted
//!    the gate. Anyone in a room can send an event shaped like this one, and
//!    closing someone else's approval card is exactly what a forged one would
//!    be for. The hub speaks both as the board's speaker.
//! 2. **`gate_id` first.** An outcome naming a gate closes every carrier of
//!    that gate — prose and companion alike — unless both carry a `board_id`
//!    and they differ.
//! 3. **The reference second**, only for an outcome with no `gate_id`: it
//!    closes the gate whose carrier has that event id, and through its
//!    identity every other carrier of the same gate.
//!
//! ## Order does not matter
//!
//! A cold sync can deliver the outcome before the gate; back-pagination
//! delivers the gate after its outcome is already on screen. Neither is
//! special: [`crate::embedded::reconcile`] runs this over the whole
//! materialised timeline after every batch, so a gate is a receipt whenever
//! both are loaded, whichever came first — and stays one when its row is
//! re-projected (a reaction, a read receipt) or the room is re-entered,
//! because the state is recomputed from the room rather than remembered by a
//! view.

use std::collections::HashMap;

use serde_json::Value;

use crate::custom_events::{
    safe_string_field, CustomEventOutcome, CustomEventView, GATE_EVENT_TYPE,
};
use crate::dto::{TimelineItemDto, TimelineRow};
use crate::item_view::ItemView;

/// The receipt's type — the custom event's Matrix type, and the
/// `suite_event_type` an `m.room.message` carrying it declares.
pub const GATE_OUTCOME_EVENT_TYPE: &str = "dev.superpipeline.gate.outcome.v1";

/// How long a `decided_by` may be on a card. A handle, not a paragraph.
const DECIDED_BY_MAX_CHARS: usize = 60;

/// Whether `raw_json` could be an outcome at all — a text search, so the
/// message that is not (nearly all of them) never pays for a parse.
pub fn may_carry_outcome(raw_json: &str) -> bool {
    raw_json.contains(GATE_OUTCOME_EVENT_TYPE)
}

/// Whether an `m.room.message`'s `content` is the prose form of an outcome.
///
/// The declared `suite_event_type`, exactly: a message that merely mentions
/// the type in its body is prose about it, not a receipt.
pub fn is_outcome_message(content: &Value) -> bool {
    content.get("suite_event_type").and_then(Value::as_str) == Some(GATE_OUTCOME_EVENT_TYPE)
}

/// One outcome as read off a row.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Outcome {
    gate_id: Option<String>,
    board_id: Option<String>,
    references: Option<String>,
    sender: String,
    decision: String,
    decided_by: Option<String>,
}

/// A non-empty string at `content[key]`, unbounded — for identifiers, which
/// are compared, never shown. The payload itself is already bounded
/// (`timeline::CUSTOM_PAYLOAD_MAX_BYTES`).
fn id_field(content: &Value, key: &str) -> Option<String> {
    content
        .get(key)?
        .as_str()
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

/// The event an outcome's `m.reference` points at.
fn reference(content: &Value) -> Option<String> {
    let relates = content.get("m.relates_to")?;
    if relates.get("rel_type")?.as_str()? != "m.reference" {
        return None;
    }
    id_field(relates, "event_id")
}

/// The outcome a row carries, in either form, or `None`.
///
/// Either form arrives on the item the same way: `detail` is the outcome
/// type and `custom_payload` the event's `content` (the timeline sets both
/// for a message declaring it — `timeline::row_from_parts`). An outcome that
/// names neither a gate nor an event, or no decision, is not the schema and
/// closes nothing.
fn outcome_of(item: &TimelineItemDto) -> Option<Outcome> {
    if !matches!(item.kind.as_str(), "message" | "customMessage")
        || item.detail.as_deref() != Some(GATE_OUTCOME_EVENT_TYPE)
    {
        return None;
    }
    let content = &item.custom_payload.as_ref()?.0;
    let decision = safe_string_field(content, "decision", DECIDED_BY_MAX_CHARS)
        .filter(|d| !d.trim().is_empty())?;
    let gate_id = id_field(content, "gate_id");
    let references = reference(content);
    if gate_id.is_none() && references.is_none() {
        return None;
    }
    Some(Outcome {
        gate_id,
        board_id: id_field(content, "board_id"),
        references,
        sender: item.sender.clone()?,
        decision,
        decided_by: safe_string_field(content, "decided_by", DECIDED_BY_MAX_CHARS)
            .map(|who| who.trim().to_string())
            .filter(|who| !who.is_empty()),
    })
}

/// A gate carrier as the matching needs it.
struct Gate<'a> {
    identity: String,
    gate_id: &'a str,
    board_id: Option<&'a str>,
    event_id: Option<&'a str>,
    sender: Option<&'a str>,
}

fn gate_of(item: &TimelineItemDto) -> Option<Gate<'_>> {
    if !matches!(item.kind.as_str(), "message" | "customMessage")
        || item.detail.as_deref() != Some(GATE_EVENT_TYPE)
    {
        return None;
    }
    let payload = &item.custom_payload.as_ref()?.0;
    let identity = crate::embedded::decision_identity(GATE_EVENT_TYPE, payload)?;
    Some(Gate {
        identity,
        gate_id: payload.get("gate_id")?.as_str()?,
        board_id: payload
            .get("board_id")
            .and_then(Value::as_str)
            .filter(|b| !b.is_empty()),
        event_id: item.event_id.as_deref(),
        sender: item.sender.as_deref(),
    })
}

/// The rule in this module's header, for one outcome and one gate carrier.
fn closes(outcome: &Outcome, gate: &Gate<'_>) -> bool {
    if gate.sender != Some(outcome.sender.as_str()) {
        return false;
    }
    if let (Some(ours), Some(theirs)) = (outcome.board_id.as_deref(), gate.board_id) {
        if ours != theirs {
            return false;
        }
    }
    match outcome.gate_id.as_deref() {
        Some(gate_id) => gate_id == gate.gate_id,
        None => outcome.references.is_some() && outcome.references.as_deref() == gate.event_id,
    }
}

/// The sentence a receipt says. Past tense, because it is over.
fn summary(decision: &str, decided_by: Option<&str>) -> String {
    let verb = match decision {
        "approve" => "Approved",
        "request_changes" => "Changes requested",
        "reject" => "Rejected",
        // A decision this build has no word for. Said without guessing at a
        // past tense for a verb nothing here knows.
        _ => "Decided",
    };
    match decided_by {
        Some(who) => format!("{verb} by {who}"),
        None => verb.to_string(),
    }
}

/// Every gate the loaded timeline says is resolved, keyed by the gate's
/// decision identity (`crate::embedded::decision_identity`), with the receipt
/// to draw. Empty — and cheap — for a timeline holding no outcome.
///
/// A gate with two outcomes (a hub that posted twice) takes the later one in
/// the timeline.
pub fn resolved_gates(rows: &[TimelineRow]) -> HashMap<String, CustomEventOutcome> {
    let outcomes: Vec<Outcome> = rows
        .iter()
        .filter_map(|row| outcome_of(&row.item))
        .collect();
    if outcomes.is_empty() {
        return HashMap::new();
    }
    let gates: Vec<Gate<'_>> = rows.iter().filter_map(|row| gate_of(&row.item)).collect();

    let mut resolved = HashMap::new();
    for outcome in &outcomes {
        for gate in gates.iter().filter(|gate| closes(outcome, gate)) {
            resolved.insert(
                gate.identity.clone(),
                CustomEventOutcome {
                    decision: outcome.decision.clone(),
                    decided_by: outcome.decided_by.clone(),
                    summary: summary(&outcome.decision, outcome.decided_by.as_deref()),
                    prompt: None,
                },
            );
        }
    }
    resolved
}

/// `view` drawn as a receipt: its buttons gone, the outcome in their place,
/// and the question it asked kept so the receipt says what was decided.
///
/// Anything that is not a rendered card is returned as it was.
pub fn as_receipt(view: ItemView, outcome: &CustomEventOutcome) -> ItemView {
    match view {
        ItemView::CustomEvent {
            view:
                CustomEventView::Rendered {
                    fields,
                    reasoning,
                    newer_version,
                    decision,
                    link,
                    outcome: _,
                },
            label,
            event_type,
        } => ItemView::CustomEvent {
            view: CustomEventView::Rendered {
                fields,
                reasoning,
                newer_version,
                decision: None,
                link,
                outcome: Some(CustomEventOutcome {
                    prompt: decision.map(|d| d.prompt),
                    ..outcome.clone()
                }),
            },
            label,
            event_type,
        },
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::CustomPayload;
    use crate::embedded::reconcile;
    use serde_json::json;

    const HUB: &str = "@board_press:hs";

    fn gate(gate_id: &str) -> Value {
        json!({
            "schema_version": 2,
            "board_id": "brd_1",
            "gate_id": gate_id,
            "card_title": "Launch post",
            "stage_key": "verify",
            "prompt": "Approve \"Launch post\"?",
            "options": [
                { "id": "approve", "label": "Approve" },
                { "id": "request_changes", "label": "Request changes" },
                { "id": "reject", "label": "Reject" }
            ]
        })
    }

    fn outcome(gate_id: Option<&str>, references: &str) -> Value {
        let mut content = json!({
            "suite_event_type": GATE_OUTCOME_EVENT_TYPE,
            "board_id": "brd_1",
            "decision": "approve",
            "decided_by": "rakesh",
            "m.relates_to": { "rel_type": "m.reference", "event_id": references }
        });
        if let Some(id) = gate_id {
            content["gate_id"] = json!(id);
        }
        content
    }

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
            Some("Approve \"Launch post\"? Reply in the app."),
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

    /// The gate as the hub now sends it: prose carrying the key.
    fn prose_gate(id: &str, gate_id: &str) -> TimelineRow {
        row(
            id,
            "message",
            Some("m.text"),
            HUB,
            GATE_EVENT_TYPE,
            gate(gate_id),
        )
    }

    /// The legacy `dev.superpipeline.gate.v1` companion.
    fn companion_gate(id: &str, gate_id: &str) -> TimelineRow {
        row(
            id,
            "customMessage",
            None,
            HUB,
            GATE_EVENT_TYPE,
            gate(gate_id),
        )
    }

    /// The receipt as a custom event.
    fn outcome_event(id: &str, sender: &str, content: Value) -> TimelineRow {
        row(
            id,
            "customMessage",
            None,
            sender,
            GATE_OUTCOME_EVENT_TYPE,
            content,
        )
    }

    /// The receipt as prose declaring the type.
    fn outcome_prose(id: &str, sender: &str, content: Value) -> TimelineRow {
        row(
            id,
            "message",
            Some("m.text"),
            sender,
            GATE_OUTCOME_EVENT_TYPE,
            content,
        )
    }

    /// `(buttons, receipt)` for a row.
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

    fn is_pending(row: &TimelineRow) -> bool {
        state(row) == (true, None)
    }

    fn is_receipt(row: &TimelineRow) -> bool {
        let (buttons, outcome) = state(row);
        !buttons && outcome.is_some()
    }

    #[test]
    fn an_outcome_after_the_gate_turns_its_card_into_a_receipt() {
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, outcome(Some("gate_1"), "$g")),
        ];
        assert!(is_pending(&rows[0]), "a gate starts pending");
        assert_eq!(reconcile(&mut rows), vec![0]);
        let (buttons, receipt) = state(&rows[0]);
        assert!(!buttons, "a resolved gate draws no buttons");
        let receipt = receipt.expect("a receipt");
        assert_eq!(receipt.decision, "approve");
        assert_eq!(receipt.decided_by.as_deref(), Some("rakesh"));
        assert_eq!(receipt.summary, "Approved by rakesh");
        // The question survives as the receipt's headline.
        assert_eq!(receipt.prompt.as_deref(), Some("Approve \"Launch post\"?"));
        // Settled: a second pass changes nothing.
        assert!(reconcile(&mut rows).is_empty());
    }

    #[test]
    fn an_outcome_before_the_gate_closes_it_all_the_same() {
        // A cold sync, or back-pagination bringing the gate in under an
        // outcome that is already on screen.
        let mut rows = vec![outcome_event("o", HUB, outcome(Some("gate_1"), "$g"))];
        assert!(reconcile(&mut rows).is_empty());
        rows.insert(0, prose_gate("g", "gate_1"));
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));

        // And in the "wrong" order within the list itself.
        let mut rows = vec![
            outcome_event("o", HUB, outcome(Some("gate_1"), "$g")),
            prose_gate("g", "gate_1"),
        ];
        reconcile(&mut rows);
        assert!(is_receipt(&rows[1]));
    }

    #[test]
    fn a_re_projected_row_is_a_receipt_again_in_the_same_pass() {
        // A reaction or read receipt re-projects the gate from its item, which
        // on its own says pending. The room still says otherwise.
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, outcome(Some("gate_1"), "$g")),
        ];
        reconcile(&mut rows);
        rows[0] = prose_gate("g", "gate_1");
        assert!(is_pending(&rows[0]));
        assert_eq!(reconcile(&mut rows), vec![0]);
        assert!(is_receipt(&rows[0]));
    }

    #[test]
    fn the_prose_form_closes_the_card_too() {
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_prose("o", HUB, outcome(Some("gate_1"), "$g")),
        ];
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));
        // And the prose itself stays the sentence it is.
        assert!(
            matches!(rows[1].view, ItemView::Bubble { .. }),
            "{:?}",
            rows[1].view
        );
    }

    #[test]
    fn a_reference_to_the_hidden_companion_closes_the_visible_prose_card() {
        // The hub references the event it recorded as the gate's — the legacy
        // companion — which this client hides behind the prose. With no
        // gate_id to go on, the reference finds the companion and the identity
        // finds the card on screen.
        let mut rows = vec![
            prose_gate("prose", "gate_1"),
            companion_gate("companion", "gate_1"),
            outcome_event("o", HUB, outcome(None, "$companion")),
        ];
        reconcile(&mut rows);
        assert_eq!(rows[1].view, ItemView::None, "the companion stays hidden");
        assert!(is_receipt(&rows[0]));
    }

    #[test]
    fn gate_id_is_matched_even_when_the_reference_names_the_other_carrier() {
        let mut rows = vec![
            prose_gate("prose", "gate_1"),
            companion_gate("companion", "gate_1"),
            outcome_event("o", HUB, outcome(Some("gate_1"), "$companion")),
        ];
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));
    }

    #[test]
    fn the_companion_drawn_alone_is_closed_as_well() {
        // A hub that sends only the legacy event.
        let mut rows = vec![
            companion_gate("companion", "gate_1"),
            outcome_event("o", HUB, outcome(Some("gate_1"), "$companion")),
        ];
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));
    }

    #[test]
    fn another_gate_stays_pending() {
        let mut rows = vec![
            prose_gate("g1", "gate_1"),
            prose_gate("g2", "gate_2"),
            outcome_event("o", HUB, outcome(Some("gate_1"), "$g1")),
        ];
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));
        assert!(is_pending(&rows[1]));
    }

    #[test]
    fn a_gate_id_naming_another_gate_beats_a_reference_to_this_one() {
        // gate_id is the primary key. A reference pointing here while the
        // outcome names a different gate is contradictory, and closing a card
        // on a contradiction is the wrong way to fail.
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, outcome(Some("gate_2"), "$g")),
        ];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_pending(&rows[0]));
    }

    #[test]
    fn another_board_does_not_close_this_one() {
        let mut other = outcome(Some("gate_1"), "$g");
        other["board_id"] = json!("brd_2");
        let mut rows = vec![prose_gate("g", "gate_1"), outcome_event("o", HUB, other)];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_pending(&rows[0]));

        // An outcome that does not say which board still matches on gate_id.
        let mut unboarded = outcome(Some("gate_1"), "$g");
        unboarded.as_object_mut().unwrap().remove("board_id");
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, unboarded),
        ];
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));
    }

    #[test]
    fn an_outcome_from_anyone_but_the_gates_sender_closes_nothing() {
        // Anyone in the room can send this shape. A forged receipt hiding
        // someone's approval buttons is the attack; the hub posts both as the
        // board's speaker.
        for form in [outcome_event, outcome_prose] {
            let mut rows = vec![
                prose_gate("g", "gate_1"),
                form("o", "@mallory:hs", outcome(Some("gate_1"), "$g")),
            ];
            assert!(reconcile(&mut rows).is_empty());
            assert!(is_pending(&rows[0]));
        }
    }

    #[test]
    fn the_readers_own_decision_does_not_close_the_card() {
        // Answered is not resolved (agentpod#613): the decision event landed
        // for hours while every resolve was refused.
        let decision = json!({
            "msgtype": "m.text",
            "body": "Approved — Approve \"Launch post\"?",
            "suite_event_type": crate::timeline::GATE_DECISION_SUITE_TYPE,
            "gate_id": "gate_1",
            "option_id": "approve",
            "m.relates_to": { "rel_type": "m.reference", "event_id": "$g" }
        });
        let mut decided = item("d", "message", Some("m.text"), "@rakesh:hs");
        decided.custom_payload = Some(CustomPayload(decision));
        let mut rows = vec![prose_gate("g", "gate_1"), TimelineRow::new(decided)];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_pending(&rows[0]));
    }

    #[test]
    fn the_card_opens_again_if_the_outcome_goes() {
        // Redacted, or paginated out: the room no longer says it.
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, outcome(Some("gate_1"), "$g")),
        ];
        reconcile(&mut rows);
        rows.remove(1);
        assert_eq!(reconcile(&mut rows), vec![0]);
        assert!(is_pending(&rows[0]));
    }

    #[test]
    fn someone_elses_answer_is_named() {
        let mut other = outcome(Some("gate_1"), "$g");
        other["decided_by"] = json!("priya");
        other["decision"] = json!("request_changes");
        let mut rows = vec![prose_gate("g", "gate_1"), outcome_event("o", HUB, other)];
        reconcile(&mut rows);
        let receipt = state(&rows[0]).1.unwrap();
        assert_eq!(receipt.summary, "Changes requested by priya");
        assert_eq!(receipt.decided_by.as_deref(), Some("priya"));
    }

    #[test]
    fn a_receipt_that_does_not_say_who_says_what() {
        let mut anonymous = outcome(Some("gate_1"), "$g");
        anonymous["decided_by"] = Value::Null;
        anonymous["decision"] = json!("reject");
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, anonymous),
        ];
        reconcile(&mut rows);
        let receipt = state(&rows[0]).1.unwrap();
        assert_eq!(receipt.summary, "Rejected");
        assert_eq!(receipt.decided_by, None);
    }

    #[test]
    fn a_decision_this_build_has_no_word_for_is_decided() {
        assert_eq!(summary("defer", Some("rakesh")), "Decided by rakesh");
        assert_eq!(summary("approve", None), "Approved");
    }

    #[test]
    fn an_outcome_without_a_decision_or_a_target_is_not_one() {
        let mut undecided = outcome(Some("gate_1"), "$g");
        undecided.as_object_mut().unwrap().remove("decision");
        let mut rows = vec![
            prose_gate("g", "gate_1"),
            outcome_event("o", HUB, undecided),
        ];
        assert!(reconcile(&mut rows).is_empty());

        let mut aimless = outcome(None, "$g");
        aimless.as_object_mut().unwrap().remove("m.relates_to");
        let mut rows = vec![prose_gate("g", "gate_1"), outcome_event("o", HUB, aimless)];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_pending(&rows[0]));
    }

    #[test]
    fn the_structured_receipt_is_not_drawn_beside_its_prose() {
        let row = outcome_event("o", HUB, outcome(Some("gate_1"), "$g"));
        assert_eq!(row.view, ItemView::None);
    }

    #[test]
    fn only_a_declared_suite_type_is_the_prose_form() {
        assert!(is_outcome_message(&outcome(Some("g"), "$g")));
        assert!(!is_outcome_message(
            &json!({ "body": "dev.superpipeline.gate.outcome.v1 is the type" })
        ));
        assert!(may_carry_outcome(
            r#"{"suite_event_type":"dev.superpipeline.gate.outcome.v1"}"#
        ));
        assert!(!may_carry_outcome(r#"{"body":"hi"}"#));
    }

    /// A message projected the way the timeline projects one: from its raw
    /// event, through `timeline::row_from_parts`.
    fn projected(id: &str, sender: &str, content: Value) -> TimelineRow {
        use matrix_sdk::ruma::events::AnySyncTimelineEvent;
        use matrix_sdk::ruma::serde::Raw;
        let body = content["body"].as_str().map(str::to_string);
        let raw: Raw<AnySyncTimelineEvent> = Raw::from_json(
            serde_json::value::to_raw_value(&json!({
                "type": "m.room.message",
                "event_id": format!("${id}"),
                "sender": sender,
                "origin_server_ts": 1,
                "content": content,
            }))
            .unwrap(),
        );
        let mut dto = item(id, "message", Some("m.text"), sender);
        dto.body = body;
        crate::timeline::row_from_parts(dto, Some(&raw), matrix_sdk::ruma::user_id!("@me:hs"))
    }

    #[test]
    fn both_prose_forms_are_read_off_the_raw_event_and_meet_in_reconcile() {
        let mut gate_content = json!({
            "msgtype": "m.text",
            "body": "Approve \"Launch post\"?",
        });
        gate_content[crate::embedded::EMBEDDED_GATE_KEY] = gate("gate_1");
        let mut outcome_content = outcome(Some("gate_1"), "$g");
        outcome_content["msgtype"] = json!("m.text");
        outcome_content["body"] = json!("Approved by rakesh — the board has it.");

        let mut rows = vec![
            projected("g", HUB, gate_content),
            projected("o", HUB, outcome_content),
        ];
        assert_eq!(
            rows[1].item.detail.as_deref(),
            Some(GATE_OUTCOME_EVENT_TYPE),
            "the prose receipt is recognised from its raw content"
        );
        assert!(matches!(rows[1].view, ItemView::Bubble { .. }));
        assert!(is_pending(&rows[0]));
        reconcile(&mut rows);
        assert!(is_receipt(&rows[0]));
    }

    #[test]
    fn a_message_that_only_mentions_the_type_is_not_a_receipt() {
        let row = projected(
            "o",
            HUB,
            json!({ "msgtype": "m.text", "body": "dev.superpipeline.gate.outcome.v1 is live" }),
        );
        assert_eq!(row.item.detail, None);
    }
}
