//! A suite decision carried *inside* the prose message that asks it.
//!
//! AgentPod first sent a permission request (and superpipeline a gate) as two
//! events: an ordinary `m.room.message` with the question in prose, for every
//! client, and beside it a separate `dev.agentpod.permission.v1` /
//! `dev.superpipeline.gate.v1` event for a client that draws buttons. For push
//! that is one event too many — the gateway would notify for the prose and the
//! card is what the reader has to act on — so the hub is moving the structured
//! content **onto the prose message**, under one namespaced key, the way it
//! already carries `dev.agentpod.turn_error` and
//! `dev.agentpod.voice_transcript` (agentpod `packages/contract/src/matrix-events.ts`).
//!
//! The keys below are this client's reading of that contract. When this was
//! written the hub's branch (`feat/push-gateway`) had not published its
//! constant, so both forms are read: the separate event, as today, and the
//! embedded key, whose value is **the same object** the separate event's
//! `content` carries (`PermissionRequestEvent`, the gate schema). Only the key
//! name is an assumption, and it is the event type without its `.vN` suffix —
//! the major version already lives inside the object as `schema_version`.
//!
//! ## One card, however it arrives
//!
//! A hub mid-migration may send both: the prose message carrying the key *and*
//! the separate event. Each would render as a card, and the reader would see
//! the same question twice with two sets of buttons. [`reconcile`] is the rule
//! that prevents it, applied to the materialised timeline in the core so every
//! host inherits it: **the prose message wins**, and a separate event for the
//! same decision is not drawn. The prose message is the one to keep because
//! it is the one Element shows too — hiding it would hide the question from
//! the transcript's readable half.
//!
//! "The same decision" is [`decision_identity`]: a permission request's
//! `session_id` and `request_seq` (how the hub's `answerPermission` addresses
//! it), a gate's `gate_id` (what superpipeline resolves on).

use serde_json::Value;

use crate::custom_events::{GATE_EVENT_TYPE, PERMISSION_REQUEST_EVENT_TYPE};
use crate::dto::TimelineRow;
use crate::item_view::ItemView;

/// The key an AgentPod permission request rides under on its prose message.
pub const EMBEDDED_PERMISSION_KEY: &str = "dev.agentpod.permission";

/// The key a superpipeline gate rides under on its prose message.
pub const EMBEDDED_GATE_KEY: &str = "dev.superpipeline.gate";

/// Each embedded key, and the event type whose renderer draws its value.
///
/// Permission first: a message carrying both is malformed, and a permission
/// request is the smaller claim — it answers with a plain message, where a
/// gate resolves a card on another system.
pub const EMBEDDED_SUITE_KEYS: [(&str, &str); 2] = [
    (EMBEDDED_PERMISSION_KEY, PERMISSION_REQUEST_EVENT_TYPE),
    (EMBEDDED_GATE_KEY, GATE_EVENT_TYPE),
];

/// The suite event a message's `content` carries under an embedded key, as
/// `(event type, payload)` — the pair a separate event would have produced.
///
/// `None` unless the value is a JSON object: anything else is not the schema,
/// and the message stays the prose it also is.
pub fn embedded_suite_event(content: &Value) -> Option<(&'static str, Value)> {
    EMBEDDED_SUITE_KEYS.iter().find_map(|(key, event_type)| {
        content
            .get(*key)
            .filter(|value| value.is_object())
            .map(|value| (*event_type, value.clone()))
    })
}

/// Whether `raw_json` could carry an embedded key at all — a text search, so
/// the message that does not (nearly all of them) never pays for a parse.
pub fn may_carry_embedded(raw_json: &str) -> bool {
    EMBEDDED_SUITE_KEYS
        .iter()
        .any(|(key, _)| raw_json.contains(key))
}

/// What makes two carriers of a decision the same decision.
///
/// `None` when the payload does not say — a request with no sequence number
/// cannot be told apart from another, and guessing would hide a real card.
pub fn decision_identity(event_type: &str, payload: &Value) -> Option<String> {
    match event_type {
        PERMISSION_REQUEST_EVENT_TYPE => {
            let session = payload.get("session_id")?.as_str()?;
            let seq = payload.get("request_seq")?.as_u64()?;
            Some(format!("permission:{session}:{seq}"))
        }
        GATE_EVENT_TYPE => {
            let gate = payload.get("gate_id")?.as_str()?;
            (!gate.is_empty()).then(|| format!("gate:{gate}"))
        }
        _ => None,
    }
}

/// The decision a row carries, and whether it carries it embedded in prose.
fn carrier(row: &TimelineRow) -> Option<(String, bool)> {
    let item = &row.item;
    let embedded = match item.kind.as_str() {
        "message" => true,
        "customMessage" => false,
        _ => return None,
    };
    let event_type = item.detail.as_deref()?;
    let payload = &item.custom_payload.as_ref()?.0;
    decision_identity(event_type, payload).map(|identity| (identity, embedded))
}

/// Settle which carrier of each decision is drawn, in place, returning the
/// indices of the rows whose view changed.
///
/// A separate event is hidden (`ItemView::None`) exactly while a prose
/// message carrying the same decision is in `rows`, and drawn again — its
/// view recomputed from its own item — once that message is not (redacted,
/// or paginated out). Every other row is untouched, so a timeline with no
/// embedded decision costs one pass and changes nothing.
pub fn reconcile(rows: &mut [TimelineRow]) -> Vec<usize> {
    let embedded: std::collections::HashSet<String> = rows
        .iter()
        .filter_map(carrier)
        .filter_map(|(identity, embedded)| embedded.then_some(identity))
        .collect();

    let mut changed = Vec::new();
    for (index, row) in rows.iter_mut().enumerate() {
        let Some((identity, false)) = carrier(row) else {
            continue;
        };
        let wanted = if embedded.contains(&identity) {
            ItemView::None
        } else {
            crate::item_view::view_for(&row.item)
        };
        if row.view != wanted {
            row.view = wanted;
            changed.push(index);
        }
    }
    changed
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::{CustomPayload, TimelineItemDto};
    use serde_json::json;

    fn permission() -> Value {
        json!({
            "schema_version": 1,
            "session_id": "s1",
            "request_seq": 7,
            "title": "Run rm -rf build",
            "options": [
                { "option_id": "allow_once", "name": "Allow once" },
                { "option_id": "reject", "name": "Reject" }
            ]
        })
    }

    fn item(id: &str, kind: &str, msgtype: Option<&str>) -> TimelineItemDto {
        crate::timeline::project_item_parts(
            id,
            Some(&format!("${id}")),
            kind,
            msgtype,
            None,
            Some("@agent_a:hs"),
            Some("Agent A"),
            None,
            false,
            Some("Allow Run rm -rf build? Reply 1 to allow once, 2 to reject."),
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

    /// The prose message, carrying the request under its key.
    fn prose(id: &str, payload: Value) -> TimelineRow {
        let mut it = item(id, "message", Some("m.text"));
        it.detail = Some(PERMISSION_REQUEST_EVENT_TYPE.into());
        it.custom_payload = Some(CustomPayload(payload));
        TimelineRow::new(it)
    }

    /// The separate `dev.agentpod.permission.v1` event.
    fn separate(id: &str, payload: Value) -> TimelineRow {
        let mut it = item(id, "customMessage", None);
        it.detail = Some(PERMISSION_REQUEST_EVENT_TYPE.into());
        it.custom_payload = Some(CustomPayload(payload));
        TimelineRow::new(it)
    }

    fn is_card(row: &TimelineRow) -> bool {
        matches!(row.view, ItemView::CustomEvent { .. })
    }

    #[test]
    fn the_embedded_key_is_read_as_the_separate_events_content() {
        let content =
            json!({ "msgtype": "m.text", "body": "…", EMBEDDED_PERMISSION_KEY: permission() });
        assert_eq!(
            embedded_suite_event(&content),
            Some((PERMISSION_REQUEST_EVENT_TYPE, permission()))
        );
        let gate = json!({ "body": "…", EMBEDDED_GATE_KEY: { "gate_id": "g1" } });
        assert_eq!(
            embedded_suite_event(&gate).map(|(t, _)| t),
            Some(GATE_EVENT_TYPE)
        );
    }

    #[test]
    fn a_key_whose_value_is_not_an_object_is_not_a_decision() {
        let content = json!({ "body": "…", EMBEDDED_PERMISSION_KEY: "yes" });
        assert_eq!(embedded_suite_event(&content), None);
        assert_eq!(embedded_suite_event(&json!({ "body": "plain" })), None);
    }

    #[test]
    fn identity_is_the_request_for_a_permission_and_the_gate_for_a_gate() {
        assert_eq!(
            decision_identity(PERMISSION_REQUEST_EVENT_TYPE, &permission()).as_deref(),
            Some("permission:s1:7")
        );
        // Another request in the same session is another decision.
        let mut next = permission();
        next["request_seq"] = json!(8);
        assert_ne!(
            decision_identity(PERMISSION_REQUEST_EVENT_TYPE, &next),
            decision_identity(PERMISSION_REQUEST_EVENT_TYPE, &permission())
        );
        assert_eq!(
            decision_identity(GATE_EVENT_TYPE, &json!({ "gate_id": "g1" })).as_deref(),
            Some("gate:g1")
        );
        assert_eq!(
            decision_identity(GATE_EVENT_TYPE, &json!({ "gate_id": "" })),
            None
        );
        assert_eq!(
            decision_identity(
                PERMISSION_REQUEST_EVENT_TYPE,
                &json!({ "session_id": "s1" })
            ),
            None
        );
    }

    #[test]
    fn a_prose_message_carrying_the_key_is_drawn_as_the_card() {
        let row = prose("a", permission());
        let ItemView::CustomEvent { view, label, .. } = &row.view else {
            panic!("expected a card, got {:?}", row.view);
        };
        assert_eq!(label, "Permission");
        let crate::custom_events::CustomEventView::Rendered { decision, .. } = view else {
            panic!("expected a rendered card, got {view:?}");
        };
        let decision = decision.as_ref().expect("the request offers answers");
        assert_eq!(decision.prompt, "Allow Run rm -rf build?");
        assert_eq!(decision.options.len(), 2);
    }

    #[test]
    fn a_prose_message_with_a_broken_key_stays_prose() {
        let row = prose("a", json!({ "schema_version": 1 }));
        assert!(
            matches!(row.view, ItemView::Bubble { .. }),
            "a key the renderer cannot read must leave the sentence, not a placeholder: {:?}",
            row.view
        );
    }

    #[test]
    fn both_forms_of_one_request_draw_one_card_and_it_is_the_prose() {
        let mut rows = vec![separate("sep", permission()), prose("msg", permission())];
        assert!(
            is_card(&rows[0]) && is_card(&rows[1]),
            "both start as cards"
        );

        let changed = reconcile(&mut rows);
        assert_eq!(changed, vec![0]);
        assert_eq!(rows[0].view, ItemView::None);
        assert!(is_card(&rows[1]));
        // Settled: a second pass changes nothing, so a batch that touches
        // neither row emits no `Set` for them.
        assert!(reconcile(&mut rows).is_empty());
    }

    #[test]
    fn the_separate_event_returns_when_the_prose_message_goes() {
        let mut rows = vec![separate("sep", permission()), prose("msg", permission())];
        reconcile(&mut rows);
        rows.remove(1);
        assert_eq!(reconcile(&mut rows), vec![0]);
        assert!(is_card(&rows[0]));
    }

    #[test]
    fn a_different_request_is_not_hidden() {
        let mut other = permission();
        other["request_seq"] = json!(8);
        let mut rows = vec![separate("sep", other), prose("msg", permission())];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_card(&rows[0]) && is_card(&rows[1]));
    }

    #[test]
    fn a_hub_that_sends_only_the_separate_event_is_unchanged() {
        let mut rows = vec![
            TimelineRow::new(item("plain", "message", Some("m.text"))),
            separate("sep", permission()),
        ];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_card(&rows[1]));
    }

    #[test]
    fn the_text_search_finds_either_key() {
        assert!(may_carry_embedded(
            r#"{"content":{"dev.agentpod.permission":{}}}"#
        ));
        assert!(may_carry_embedded(
            r#"{"content":{"dev.superpipeline.gate":{}}}"#
        ));
        assert!(!may_carry_embedded(r#"{"content":{"body":"hello"}}"#));
    }
}
