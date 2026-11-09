//! An approval that waits for its release time.
//!
//! charter → `decisions/2026-11-09-an-approval-may-wait-for-its-release-time.md`.
//! An approved gate acts at its release time: the later of its subject's own
//! not-before time and the approval's time plus the stage's `holdFor`. Until
//! then the approval is recorded but has not acted, and anyone who may decide
//! the gate may pull it back, which sends the card back for changes.
//! superpipeline keeps the time; this client only shows it. Four things cross
//! the wire (fixture version 10, entries tagged `variant: "release"`):
//!
//! - **gate.v2's `release`** — `{ hold_for_minutes?, not_before? }` — says
//!   before anyone approves that an approval would wait ([`release_line`]).
//! - **The outcome's `held_until`** — the receipt of a held approval
//!   (`crate::gate_outcome`), which keeps a Pull back on the card
//!   ([`held_line`]).
//! - **[`RELEASED_TYPE`] and [`PULLED_BACK_TYPE`]** — the hub's structured
//!   notices that the approval went ahead (or was blocked at its release
//!   time), or was pulled back. They settle the held receipt
//!   ([`settle`]); each is posted beside a readable reply, so the notice
//!   itself is never a row and never a notification.
//! - **[`PULL_BACK_SUITE_TYPE`]** — what the reader sends to pull an approval
//!   back ([`pull_back_request`]). superpipeline judges who may.
//!
//! Times are said against the device's clock, as a deadline is
//! (`crate::decision_timeout::deadline_line`): "Goes ahead in 25 min unless
//! pulled back". The host passes its clock in, so the core never reads one.

use serde_json::Value;

use crate::custom_events::{bound_text, safe_string_field, CustomEventOutcome};
use crate::decision_timeout::iso_ms;
use crate::error::{CoreError, CoreResult};

/// The hub's notice that a held approval reached its release time.
pub const RELEASED_TYPE: &str = "dev.agentpod.decision.released.v2";
/// The hub's notice that a held approval was pulled back.
pub const PULLED_BACK_TYPE: &str = "dev.agentpod.decision.pulled_back.v2";
/// What a reader sends to pull a held approval back.
pub const PULL_BACK_SUITE_TYPE: &str = "dev.superpipeline.gate.pull_back.v2";

/// The board's bounds on a stage's `holdFor`, in minutes (charter §2).
const HOLD_FOR_MIN: u64 = 5;
const HOLD_FOR_MAX: u64 = 7 * 24 * 60;
/// The board's bound on a pull-back's comment (`COMMENT_MAX_BYTES`).
const PULL_BACK_COMMENT_MAX_BYTES: usize = 8192;
/// How much of a comment or reason a receipt line repeats.
const RECEIPT_DETAIL_MAX_CHARS: usize = 120;
const WHO_MAX_CHARS: usize = 60;

/// What would hold an approval of this gate, as the gate said it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateRelease {
    /// The stage's cooling-off period, 5 minutes to 7 days.
    pub hold_for_minutes: Option<u32>,
    /// The subject's own not-before time, an ISO timestamp.
    pub not_before: Option<String>,
}

/// A held approval on a receipt: what a host needs to say until when, and to
/// offer Pull back.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateHold {
    /// Handed back verbatim in a pull-back; never drawn.
    pub gate_id: String,
    /// The release time, an ISO timestamp ([`held_line`]).
    pub held_until: String,
}

/// `content.release`: `Ok(None)` when absent, `Err(())` when present and not
/// what the hub carries — a hold outside 5 minutes to 7 days or not a whole
/// number, a not-before that is not a time, or neither half. Like a malformed
/// quorum, it makes the whole gate.v2 unreadable and the v1 card is drawn.
pub(crate) fn parse_release(content: &Value) -> Result<Option<GateRelease>, ()> {
    let Some(raw) = content.get("release") else {
        return Ok(None);
    };
    let raw = raw.as_object().ok_or(())?;
    let hold_for_minutes = match raw.get("hold_for_minutes") {
        None => None,
        Some(v) => {
            let n = v.as_u64().ok_or(())?;
            if !(HOLD_FOR_MIN..=HOLD_FOR_MAX).contains(&n) {
                return Err(());
            }
            Some(u32::try_from(n).map_err(|_| ())?)
        }
    };
    let not_before = match raw.get("not_before") {
        None => None,
        Some(v) => {
            let s = v.as_str().ok_or(())?;
            iso_ms(s).ok_or(())?;
            Some(s.to_string())
        }
    };
    if hold_for_minutes.is_none() && not_before.is_none() {
        return Err(());
    }
    Ok(Some(GateRelease {
        hold_for_minutes,
        not_before,
    }))
}

/// "25 min", "3 h", "6 d" — the deadline's own words.
fn span(ms: i64) -> String {
    let minutes = ms / 60_000;
    if minutes < 60 {
        return format!("{} min", minutes.max(1));
    }
    let hours = minutes / 60;
    if hours < 48 {
        format!("{hours} h")
    } else {
        format!("{} d", hours / 24)
    }
}

/// The line an open gate shows under its question when an approval would
/// wait, at `now_ms`, or `None` when nothing would hold it any more (a
/// not-before time already past, and no hold).
pub fn release_line(release: &GateRelease, now_ms: i64) -> Option<String> {
    let hold = release
        .hold_for_minutes
        .map(|m| span(i64::from(m) * 60_000));
    let not_before = release
        .not_before
        .as_deref()
        .and_then(iso_ms)
        .filter(|at| *at > now_ms)
        .map(|at| span(at - now_ms));
    match (not_before, hold) {
        (Some(at), Some(hold)) => Some(format!(
            "If approved, it goes ahead in {at} at the earliest, and no sooner than {hold} after the approval."
        )),
        (Some(at), None) => Some(format!(
            "If approved, it goes ahead in {at} at the earliest."
        )),
        (None, Some(hold)) => Some(format!(
            "If approved, it waits {hold} before it goes ahead."
        )),
        (None, None) => None,
    }
}

/// The line a held receipt shows at `now_ms`: "Goes ahead in 25 min unless
/// pulled back.", then "Going ahead now." once the time has passed and the
/// board's five-minute sweep has yet to release it.
pub fn held_line(held_until: &str, now_ms: i64) -> String {
    match iso_ms(held_until) {
        Some(at) if at > now_ms => {
            format!("Goes ahead in {} unless pulled back.", span(at - now_ms))
        }
        _ => "Going ahead now.".to_string(),
    }
}

/// `content.held_until` on an outcome, when it is a time.
pub(crate) fn held_until(content: &Value) -> Option<String> {
    let s = content.get("held_until")?.as_str()?;
    iso_ms(s).map(|_| s.to_string())
}

/// What a notice did to a held approval.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Settled {
    Released,
    Blocked {
        reason: Option<String>,
    },
    PulledBack {
        by: Option<String>,
        comment: Option<String>,
    },
}

/// A release or pull-back notice as read off a row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Notice {
    pub(crate) gate_id: String,
    pub(crate) board_id: Option<String>,
    pub(crate) sender: String,
    pub(crate) settled: Settled,
}

fn one_line(content: &Value, key: &str, max: usize) -> Option<String> {
    safe_string_field(content, key, usize::MAX)
        .map(|s| s.split_whitespace().collect::<Vec<_>>().join(" "))
        .filter(|s| !s.is_empty())
        .map(|s| bound_text(&s, max))
}

/// The notice a row carries, or `None`. Only the custom-event form: the hub
/// sends the readable line as a separate reply.
pub(crate) fn notice_of(item: &crate::dto::TimelineItemDto) -> Option<Notice> {
    if item.kind != "customMessage" {
        return None;
    }
    let event_type = item.detail.as_deref()?;
    let content = &item.custom_payload.as_ref()?.0;
    let settled = match event_type {
        RELEASED_TYPE => match content.get("outcome")?.as_str()? {
            "released" => Settled::Released,
            "blocked" => Settled::Blocked {
                reason: one_line(content, "reason", RECEIPT_DETAIL_MAX_CHARS),
            },
            _ => return None,
        },
        PULLED_BACK_TYPE => Settled::PulledBack {
            by: one_line(content, "pulled_back_by", WHO_MAX_CHARS),
            comment: one_line(content, "comment", RECEIPT_DETAIL_MAX_CHARS),
        },
        _ => return None,
    };
    Some(Notice {
        gate_id: crate::gate_v2::id_field(content, "gate_id")?,
        board_id: crate::gate_v2::id_field(content, "board_id"),
        sender: item.sender.clone()?,
        settled,
    })
}

/// Whether a row is a release or pull-back notice, which is never a row of
/// its own: the readable reply beside it says the same thing.
pub fn is_notice_type(event_type: Option<&str>) -> bool {
    matches!(event_type, Some(RELEASED_TYPE | PULLED_BACK_TYPE))
}

/// `outcome` once a notice has settled its held approval: no Pull back any
/// more, and the receipt says what happened. A pull-back reads as the request
/// for changes it is.
pub(crate) fn settle(outcome: &mut CustomEventOutcome, settled: &Settled) {
    outcome.held = None;
    match settled {
        Settled::Released => {
            outcome.summary = format!("{} \u{2014} it went ahead", outcome.summary);
        }
        Settled::Blocked { reason } => {
            outcome.summary = match reason {
                Some(reason) => {
                    format!("{} \u{2014} it did not go ahead: {reason}", outcome.summary)
                }
                None => format!("{} \u{2014} it did not go ahead", outcome.summary),
            };
        }
        Settled::PulledBack { by, comment } => {
            outcome.decision = "request_changes".to_string();
            outcome.decided_by = by.clone();
            let who = by
                .as_deref()
                .map(|b| format!(" by {b}"))
                .unwrap_or_default();
            outcome.summary = match comment {
                Some(c) => format!("Pulled back{who} \u{2014} returned for changes: {c}"),
                None => format!("Pulled back{who} \u{2014} returned for changes"),
            };
        }
    }
}

/// A gate.pull_back.v2's `content` — the whole of what goes on the wire.
///
/// Refuses, with a reason a host can show, a pull-back with no comment (the
/// board needs one saying why), one over the board's limit, no gate, and a
/// reference that is not an event id. Who may pull back is the board's to
/// judge.
pub(crate) fn pull_back_request(
    gate_id: &str,
    comment: &str,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if gate_id.is_empty() {
        return Err(CoreError::Protocol("a pull-back needs a gate".into()));
    }
    let comment = comment.trim();
    if comment.is_empty() {
        return Err(CoreError::Protocol(
            "pulling back an approval needs a comment saying why".into(),
        ));
    }
    if comment.len() > PULL_BACK_COMMENT_MAX_BYTES {
        return Err(CoreError::Protocol(format!(
            "a comment is at most {PULL_BACK_COMMENT_MAX_BYTES} bytes"
        )));
    }
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    Ok(serde_json::json!({
        "msgtype": "m.text",
        "body": format!("Pulled back: {comment}"),
        "schema_version": 1,
        "suite_event_type": PULL_BACK_SUITE_TYPE,
        "gate_id": gate_id,
        "comment": comment,
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::CustomEventView;
    use crate::dto::{CustomPayload, TimelineItemDto, TimelineRow};
    use crate::item_view::ItemView;
    use serde_json::json;

    const SPEAKER: &str = "@board_release:hs";
    /// 2026-11-16T09:00:00Z, the corpus's not-before time.
    const MONDAY_9: i64 = 1_794_819_600_000;
    const MIN: i64 = 60_000;

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
            .find(|e| e["suiteEventType"] == suite_type && e["variant"] == "release")
            .unwrap_or_else(|| panic!("the corpus pins a release {suite_type}"))
            .clone()
    }

    fn accept(suite_type: &str, i: usize) -> Value {
        entry(suite_type)["accept"][i]["content"].clone()
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
        )
    }

    fn gate_row(content: &Value) -> TimelineRow {
        let (event_type, payload) = crate::embedded::embedded_suite_event(content).unwrap();
        let mut it = item("gateEventId", "message", Some("m.text"), SPEAKER);
        it.detail = Some(event_type.into());
        it.custom_payload = Some(CustomPayload(payload));
        TimelineRow::new(it)
    }

    fn custom_row(id: &str, sender: &str, event_type: &str, content: Value) -> TimelineRow {
        let mut it = item(id, "customMessage", None, sender);
        it.detail = Some(event_type.into());
        it.custom_payload = Some(CustomPayload(content));
        TimelineRow::new(it)
    }

    /// The corpus's release gate, read as a card.
    fn release_gate(i: usize) -> crate::gate_v2::GateV2Decision {
        let content = accept(crate::gate_v2::GATE_V2_EVENT_TYPE, i);
        let body = content["body"].as_str();
        crate::gate_v2::parse(&content, body)
            .map(|p| p.decision_for_test())
            .expect("the corpus's release gate draws")
    }

    fn outcome_of(row: &TimelineRow) -> crate::custom_events::CustomEventOutcome {
        match &row.view {
            ItemView::CustomEvent {
                view:
                    CustomEventView::Rendered {
                        outcome: Some(o),
                        decision,
                        ..
                    },
                ..
            } => {
                assert!(decision.is_none(), "a receipt offers no buttons");
                o.clone()
            }
            other => panic!("expected a receipt, got {other:?}"),
        }
    }

    #[test]
    fn every_release_the_corpus_accepts_is_carried() {
        let both = release_gate(0).release.unwrap();
        assert_eq!(both.hold_for_minutes, Some(30));
        assert_eq!(both.not_before.as_deref(), Some("2026-11-16T09:00:00.000Z"));
        assert_eq!(release_gate(1).release.unwrap().not_before, None);
        assert_eq!(release_gate(2).release.unwrap().hold_for_minutes, None);
    }

    #[test]
    fn every_release_the_corpus_refuses_makes_the_v2_card_unreadable() {
        let rejects = entry(crate::gate_v2::GATE_V2_EVENT_TYPE)["reject"]
            .as_array()
            .unwrap()
            .clone();
        assert!(!rejects.is_empty());
        for case in rejects {
            let content = &case["content"];
            assert!(
                crate::gate_v2::parse(content, content["body"].as_str()).is_none(),
                "drew a gate the corpus refuses: {}",
                case["why"]
            );
        }
    }

    #[test]
    fn a_gate_nothing_holds_carries_no_release() {
        let mut content = accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0);
        content.as_object_mut().unwrap().remove("release");
        let gate = crate::gate_v2::parse(&content, content["body"].as_str()).unwrap();
        assert_eq!(gate.decision_for_test().release, None);
    }

    #[test]
    fn the_release_line_is_said_at_the_readers_clock() {
        let hold = GateRelease {
            hold_for_minutes: Some(30),
            not_before: None,
        };
        assert_eq!(
            release_line(&hold, 0).as_deref(),
            Some("If approved, it waits 30 min before it goes ahead.")
        );
        let embargo = GateRelease {
            hold_for_minutes: None,
            not_before: Some("2026-11-16T09:00:00.000Z".into()),
        };
        assert_eq!(
            release_line(&embargo, MONDAY_9 - 3 * 24 * 60 * MIN).as_deref(),
            Some("If approved, it goes ahead in 3 d at the earliest.")
        );
        // Once the time has passed, nothing holds it any more.
        assert_eq!(release_line(&embargo, MONDAY_9 + MIN), None);
        let both = GateRelease {
            hold_for_minutes: Some(30),
            ..embargo
        };
        assert_eq!(
            release_line(&both, MONDAY_9 - 5 * 60 * MIN).as_deref(),
            Some("If approved, it goes ahead in 5 h at the earliest, and no sooner than 30 min after the approval.")
        );
        assert_eq!(
            release_line(&both, MONDAY_9 + MIN).as_deref(),
            Some("If approved, it waits 30 min before it goes ahead.")
        );
    }

    #[test]
    fn the_held_line_counts_down_then_says_it_is_going() {
        let at = "2026-11-16T09:00:00.000Z";
        assert_eq!(
            held_line(at, MONDAY_9 - 25 * MIN),
            "Goes ahead in 25 min unless pulled back."
        );
        assert_eq!(held_line(at, MONDAY_9 + MIN), "Going ahead now.");
    }

    fn held_outcome() -> Value {
        accept(crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE, 0)
    }

    fn timeline(extra: Vec<TimelineRow>) -> Vec<TimelineRow> {
        let mut rows = vec![
            gate_row(&accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0)),
            custom_row(
                "outcome",
                SPEAKER,
                crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
                held_outcome(),
            ),
        ];
        rows.extend(extra);
        crate::embedded::reconcile(&mut rows);
        rows
    }

    #[test]
    fn a_held_receipt_keeps_its_gate_and_release_time() {
        let rows = timeline(vec![]);
        let outcome = outcome_of(&rows[0]);
        assert_eq!(outcome.summary, "Approved by rakesh");
        assert_eq!(
            outcome.held,
            Some(GateHold {
                gate_id: "gate_6e19".into(),
                held_until: "2026-11-16T09:00:00.000Z".into()
            })
        );
    }

    #[test]
    fn only_an_approval_is_held() {
        let mut outcome = held_outcome();
        outcome["decision"] = "reject".into();
        let mut rows = vec![
            gate_row(&accept(crate::gate_v2::GATE_V2_EVENT_TYPE, 0)),
            custom_row(
                "outcome",
                SPEAKER,
                crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
                outcome,
            ),
            // A notice about a receipt that was never held changes nothing.
            custom_row("rel", SPEAKER, RELEASED_TYPE, accept(RELEASED_TYPE, 0)),
        ];
        crate::embedded::reconcile(&mut rows);
        let receipt = outcome_of(&rows[0]);
        assert_eq!(receipt.held, None);
        assert_eq!(receipt.summary, "Rejected by rakesh");
    }

    #[test]
    fn released_it_went_ahead_and_pull_back_is_withdrawn() {
        let released = accept(RELEASED_TYPE, 0);
        let rows = timeline(vec![custom_row("rel", SPEAKER, RELEASED_TYPE, released)]);
        let outcome = outcome_of(&rows[0]);
        assert_eq!(outcome.held, None);
        assert_eq!(outcome.summary, "Approved by rakesh \u{2014} it went ahead");
        // The notice is never a row of its own.
        assert_eq!(rows[2].view, ItemView::None);
    }

    #[test]
    fn blocked_at_its_release_time_says_why() {
        let blocked = accept(RELEASED_TYPE, 1);
        let rows = timeline(vec![custom_row("rel", SPEAKER, RELEASED_TYPE, blocked)]);
        let outcome = outcome_of(&rows[0]);
        assert_eq!(outcome.held, None);
        assert_eq!(
            outcome.summary,
            "Approved by rakesh \u{2014} it did not go ahead: the approved subject changed before the release time"
        );
    }

    #[test]
    fn pulled_back_reads_as_returned_for_changes() {
        let pulled = accept(PULLED_BACK_TYPE, 0);
        let rows = timeline(vec![custom_row("pb", SPEAKER, PULLED_BACK_TYPE, pulled)]);
        let outcome = outcome_of(&rows[0]);
        assert_eq!(outcome.held, None);
        assert_eq!(outcome.decision, "request_changes");
        assert_eq!(
            outcome.summary,
            "Pulled back by rakesh \u{2014} returned for changes: wrong image"
        );
        assert_eq!(rows[2].view, ItemView::None);
    }

    #[test]
    fn a_notice_from_anyone_else_settles_nothing() {
        let rows = timeline(vec![
            custom_row(
                "forged",
                "@mallory:hs",
                PULLED_BACK_TYPE,
                accept(PULLED_BACK_TYPE, 0),
            ),
            custom_row(
                "forged2",
                "@mallory:hs",
                RELEASED_TYPE,
                accept(RELEASED_TYPE, 0),
            ),
        ]);
        let outcome = outcome_of(&rows[0]);
        assert!(
            outcome.held.is_some(),
            "still held: a forged notice withdraws nothing"
        );
        assert_eq!(outcome.summary, "Approved by rakesh");
    }

    #[test]
    fn a_pull_back_is_the_corpus_accept_and_needs_a_comment() {
        let sent = pull_back_request("gate_6e19", " wrong image ", "$gateEventId").unwrap();
        let mut expected = accept(PULL_BACK_SUITE_TYPE, 0);
        expected["body"] = json!("Pulled back: wrong image");
        assert_eq!(sent, expected);
        for case in entry(PULL_BACK_SUITE_TYPE)["reject"].as_array().unwrap() {
            let comment = case["content"]["comment"].as_str().unwrap_or("");
            assert!(
                pull_back_request("gate_6e19", comment, "$gateEventId").is_err(),
                "sent a pull-back the corpus refuses: {}",
                case["why"]
            );
        }
        assert!(pull_back_request("", "why", "$gateEventId").is_err());
        assert!(pull_back_request("gate_6e19", &"x".repeat(8193), "$gateEventId").is_err());
        assert!(pull_back_request("gate_6e19", "why", "not an event").is_err());
    }
}
