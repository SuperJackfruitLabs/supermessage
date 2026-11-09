//! When nobody answers a decision: its deadline, and the notices the hub
//! posts when that deadline passes.
//!
//! charter → `decisions/2026-11-08-unanswered-decisions-remind-then-escalate.md`.
//! superpipeline owns the clock; this client only shows it. Three things
//! cross the wire, all pinned by
//! `fixtures/ecosystem-identity/matrix_gate_events.json` (version 7):
//!
//! - **The deadline** (§5). gate.v2, question.v2, delivery.v2 and attention.v2
//!   may carry `deadline { remind_at?, escalate_at? }`, ISO 8601 times. Read
//!   by [`parse_deadline`] into [`DecisionDeadline`], which rides on
//!   `CustomEventDecision::deadline`. A half that is not a time is dropped and
//!   the other kept, as the hub drops it (`decision-timeout-wire.ts`); an
//!   empty or unusable deadline is no deadline. It is separate from
//!   `subject.expires_at`, and nothing here resolves on it.
//! - **The reminder and the escalation** (§2-3). An `m.text` reply to the
//!   card, declared as [`DECISION_REMINDED_EVENT_TYPE`] or
//!   [`DECISION_ESCALATED_EVENT_TYPE`]. Drawn as a quiet system line
//!   ([`notice_view`]) — never a card of its own, though a room whose newest
//!   message is one still owes its decision ([`notice_card_type`]). A reminder is
//!   not news; an escalation notifies as a decision that only opens
//!   (`crate::notification`), because for an admin it invited it may be the
//!   first sight of the card.
//! - **The expired outcome** (§7) is a gate outcome, read by
//!   `crate::gate_outcome`.
//!
//! The words a host shows for a deadline come from [`deadline_line`], given
//! the host's clock: the row is projected once, and a relative time computed
//! then would go stale on screen.

use serde_json::Value;

use crate::custom_events::bound_text;
use crate::dto::TimelineItemDto;
use crate::item_view::{ItemView, SystemKind};

/// The `suite_event_type` of a reminder notice.
pub const DECISION_REMINDED_EVENT_TYPE: &str = "dev.superpipeline.decision.reminded.v1";

/// The `suite_event_type` of an escalation notice.
pub const DECISION_ESCALATED_EVENT_TYPE: &str = "dev.superpipeline.decision.escalated.v1";

/// The decision kinds a notice may name (`decision_kind`).
const DECISION_KINDS: [&str; 5] = ["gate", "question", "delivery", "attention", "checks"];

/// The hub's own bound on a time (`TIME_MAX`, `decision-timeout-wire.ts`).
const TIME_MAX_CHARS: usize = 40;

/// How long a notice's line may be. One line, not a paragraph.
const NOTICE_MAX_CHARS: usize = 300;

/// When a waiting decision is reminded and escalated, as the board said it.
///
/// Each half is an ISO 8601 time exactly as sent, and at least one is
/// present. A host passes it to [`deadline_line`] with its own clock.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct DecisionDeadline {
    pub remind_at: Option<String>,
    pub escalate_at: Option<String>,
}

/// `content.deadline`, or `None` when absent, not an object, or with no half
/// that is a time. Only the snake_case wire names are read: `remindAt` is the
/// board's spelling and not the envelope's.
pub fn parse_deadline(content: &Value) -> Option<DecisionDeadline> {
    let raw = content.get("deadline")?.as_object()?;
    let half = |key: &str| {
        raw.get(key)
            .and_then(Value::as_str)
            .filter(|s| iso_ms(s).is_some())
            .map(str::to_string)
    };
    let deadline = DecisionDeadline {
        remind_at: half("remind_at"),
        escalate_at: half("escalate_at"),
    };
    let any = deadline.remind_at.is_some() || deadline.escalate_at.is_some();
    any.then_some(deadline)
}

/// An ISO 8601 time as milliseconds since the epoch, or `None` when it is
/// not one the hub would send: `YYYY-MM-DDTHH:MM`, optional `:SS` and up to
/// nine fraction digits, then `Z` or `±HH:MM` — the hub's `ISO_TIME` — naming
/// a real instant (no 13th month, no 30 February).
pub fn iso_ms(s: &str) -> Option<i64> {
    if s.len() > TIME_MAX_CHARS || !s.is_ascii() {
        return None;
    }
    let b = s.as_bytes();
    let num = |from: usize, len: usize| -> Option<i64> {
        let part = s.get(from..from + len)?;
        if part.bytes().all(|c| c.is_ascii_digit()) {
            part.parse().ok()
        } else {
            None
        }
    };
    let at = |i: usize, c: u8| b.get(i) == Some(&c);
    let year = num(0, 4)?;
    let month = num(5, 2)?;
    let day = num(8, 2)?;
    let hour = num(11, 2)?;
    let minute = num(14, 2)?;
    if !(at(4, b'-') && at(7, b'-') && at(10, b'T') && at(13, b':')) {
        return None;
    }
    let mut i = 16;
    let mut second = 0;
    let mut millis = 0;
    if at(i, b':') {
        second = num(i + 1, 2)?;
        i += 3;
        if at(i, b'.') {
            let start = i + 1;
            let mut end = start;
            while b.get(end).is_some_and(u8::is_ascii_digit) {
                end += 1;
            }
            let digits = end - start;
            if digits == 0 || digits > 9 {
                return None;
            }
            let head = &s[start..start + digits.min(3)];
            millis = head.parse::<i64>().ok()? * 10_i64.pow(3 - head.len() as u32);
            i = end;
        }
    }
    let offset_minutes = match *b.get(i)? {
        b'Z' => {
            i += 1;
            0
        }
        sign @ (b'+' | b'-') => {
            let hours = num(i + 1, 2)?;
            let minutes = num(i + 4, 2)?;
            if !at(i + 3, b':') || hours > 23 || minutes > 59 {
                return None;
            }
            i += 6;
            let total = hours * 60 + minutes;
            if sign == b'-' {
                -total
            } else {
                total
            }
        }
        _ => return None,
    };
    if i != b.len()
        || !(1..=12).contains(&month)
        || !(1..=days_in_month(year, month)).contains(&day)
        || hour > 23
        || minute > 59
        || second > 59
    {
        return None;
    }
    let days = days_from_civil(year, month, day);
    let seconds = days * 86_400 + hour * 3_600 + minute * 60 + second - offset_minutes * 60;
    Some(seconds * 1_000 + millis)
}

fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        2 if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's
/// `days_from_civil`).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// How long until something, as a person says it — "45 min", "3 h", "3 d" —
/// in the hub's units (`waitedFor`), whole units rounded down, and never
/// "0 min".
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

/// The line a pending decision card shows for its deadline at `now_ms`, or
/// `None` when there is nothing to say.
///
/// The escalation is what a reader needs to know: "Escalates in 3 h", then
/// "Escalated" once it has passed. A deadline that only reminds says
/// "Reminder in 45 min" until then, and nothing after — the reminder notice
/// in the room says it.
pub fn deadline_line(deadline: &DecisionDeadline, now_ms: i64) -> Option<String> {
    if let Some(at) = deadline.escalate_at.as_deref().and_then(iso_ms) {
        let line = if now_ms < at {
            format!("Escalates in {}", span(at - now_ms))
        } else {
            "Escalated".to_string()
        };
        return Some(line);
    }
    let at = deadline.remind_at.as_deref().and_then(iso_ms)?;
    if now_ms < at {
        Some(format!("Reminder in {}", span(at - now_ms)))
    } else {
        None
    }
}

/// Whether `raw_json` could be a notice at all — a text search, so the
/// message that is not never pays for a parse.
pub fn may_carry_notice(raw_json: &str) -> bool {
    raw_json.contains("dev.superpipeline.decision.")
}

/// The notice type a message's `content` declares, when it is one the corpus
/// accepts: the declared type, a body, `schema_version`, the board, card and
/// decision ids, a known `decision_kind`, `waiting_since` and the notice's
/// own time as ISO times, and an `m.in_reply_to` naming the card's event —
/// except for overdue checks, which may stand alone.
/// An `m.reference` is an answer or a receipt, not a notice.
pub fn notice_type(content: &Value) -> Option<&'static str> {
    let (event_type, at_key) = match content.get("suite_event_type")?.as_str()? {
        DECISION_REMINDED_EVENT_TYPE => (DECISION_REMINDED_EVENT_TYPE, "reminded_at"),
        DECISION_ESCALATED_EVENT_TYPE => (DECISION_ESCALATED_EVENT_TYPE, "escalated_at"),
        _ => return None,
    };
    let text = |key: &str| {
        content
            .get(key)
            .and_then(Value::as_str)
            .filter(|s| !s.trim().is_empty())
    };
    let time = |key: &str| content.get(key).and_then(Value::as_str).and_then(iso_ms);
    let replies_to = content
        .get("m.relates_to")
        .and_then(|r| r.get("m.in_reply_to"))
        .and_then(|r| r.get("event_id"))
        .and_then(Value::as_str)
        .is_some_and(|id| !id.is_empty());
    let kind_known = content
        .get("decision_kind")
        .and_then(Value::as_str)
        .is_some_and(|kind| DECISION_KINDS.contains(&kind));
    let valid = content.get("msgtype").is_some_and(Value::is_string)
        && text("body").is_some()
        && content.get("schema_version").is_some_and(Value::is_number)
        && text("board_id").is_some()
        && text("card_id").is_some()
        && text("decision_id").is_some()
        && kind_known
        && time("waiting_since").is_some()
        && time(at_key).is_some()
        // Checks wait before any card is posted: their reminder has nothing to
        // reply to, and their escalation replies to the card of overdue
        // checks it posts, or stands alone when none could be.
        && (replies_to || content.get("decision_kind").and_then(Value::as_str) == Some("checks"));
    valid.then_some(event_type)
}

/// The card type a notice is about, when `content` is a notice the corpus
/// accepts: gate.v2, question.v2, delivery.v2 or attention.v2 by its
/// `decision_kind`. `crate::room_preview` reads a room whose newest message
/// is one as still owing that decision — the hub posts a notice only while
/// the decision is open.
pub fn notice_card_type(content: &Value) -> Option<&'static str> {
    notice_type(content)?;
    Some(match content.get("decision_kind")?.as_str()? {
        "gate" => crate::gate_v2::GATE_V2_EVENT_TYPE,
        "question" => crate::question_v2::QUESTION_V2_EVENT_TYPE,
        "delivery" => crate::delivery_v2::DELIVERY_V2_EVENT_TYPE,
        "attention" => crate::attention_v2::ATTENTION_V2_EVENT_TYPE,
        "checks" => crate::gate_checks::CHECKS_WAITING_TYPE,
        _ => return None,
    })
}

/// A notice's body as one quiet line: the hub's sentence without the
/// markdown link it ends with (`[Open the card](…)`), which a system line
/// cannot draw and which would otherwise show as raw brackets. The card it
/// replies to carries the same link.
pub fn notice_text(body: &str) -> String {
    let body = body.trim();
    let text = match body.rfind(" [") {
        Some(i) if body.ends_with(')') && body[i..].contains("](") => body[..i].trim_end(),
        _ => body,
    };
    let one_line = text.split_whitespace().collect::<Vec<_>>().join(" ");
    bound_text(&one_line, NOTICE_MAX_CHARS)
}

/// Whether an item is a notice (`crate::timeline` set its `detail` only for
/// one that validated).
pub fn is_notice(item: &TimelineItemDto) -> bool {
    matches!(
        item.detail.as_deref(),
        Some(DECISION_REMINDED_EVENT_TYPE | DECISION_ESCALATED_EVENT_TYPE)
    )
}

/// Whether an item is an escalation notice.
pub fn is_escalation(item: &TimelineItemDto) -> bool {
    item.detail.as_deref() == Some(DECISION_ESCALATED_EVENT_TYPE)
}

/// A notice drawn as the room speaking: a system line, never a card and
/// never a bubble in the board speaker's voice. `None` for anything else.
pub fn notice_view(item: &TimelineItemDto) -> Option<ItemView> {
    if item.kind != "message"
        || !matches!(item.msgtype.as_deref(), Some("m.text") | Some("m.notice"))
        || !is_notice(item)
    {
        return None;
    }
    let text = notice_text(item.body.as_deref()?);
    if text.is_empty() {
        return None;
    }
    Some(ItemView::System {
        kind: SystemKind::Notice {
            who: item.sender.clone().unwrap_or_default(),
        },
        text,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use crate::dto::{CustomPayload, TimelineRow};
    use serde_json::json;

    const CORPUS: &str =
        include_str!("../../../fixtures/ecosystem-identity/matrix_gate_events.json");

    /// Whether a corpus entry is `variant`, or the entry without one.
    fn is_variant(entry: &Value, variant: Option<&str>) -> bool {
        match variant {
            Some(v) => entry["variant"] == v,
            None => entry.get("variant").is_none(),
        }
    }

    /// The corpus entry for `suite_type` with `variant` (or none).
    fn entry(suite_type: &str, variant: Option<&str>) -> Value {
        let corpus: Value = serde_json::from_str(CORPUS).expect("the corpus is JSON");
        corpus["events"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|e| e["suiteEventType"] == suite_type)
            .find(|e| is_variant(e, variant))
            .unwrap_or_else(|| panic!("the corpus pins {suite_type} {variant:?}"))
            .clone()
    }

    fn cases(suite_type: &str, variant: Option<&str>, which: &str) -> Vec<(String, Value)> {
        entry(suite_type, variant)[which]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| (c["why"].as_str().unwrap().to_string(), c["content"].clone()))
            .collect()
    }

    const ENVELOPES: [&str; 4] = [
        crate::gate_v2::GATE_V2_EVENT_TYPE,
        crate::question_v2::QUESTION_V2_EVENT_TYPE,
        crate::delivery_v2::DELIVERY_V2_EVENT_TYPE,
        crate::attention_v2::ATTENTION_V2_EVENT_TYPE,
    ];

    /// The card a host gets for an envelope's message, through the
    /// production path: `embedded_suite_event`, then the registry.
    fn decision_for(content: &Value) -> crate::custom_events::CustomEventDecision {
        let (event_type, payload) =
            crate::embedded::embedded_suite_event(content).expect("an envelope");
        match resolve_custom_event(
            default_registry(),
            Some(event_type),
            Some(&payload),
            content.get("body").and_then(Value::as_str),
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    // ---- the deadline --------------------------------------------------------

    #[test]
    fn every_accepted_envelope_with_a_deadline_carries_it_to_the_card() {
        for suite_type in ENVELOPES {
            for (why, content) in cases(suite_type, Some("deadline"), "accept") {
                let wire = content["deadline"].clone();
                let deadline = decision_for(&content)
                    .deadline
                    .unwrap_or_else(|| panic!("{suite_type}: {why}: no deadline"));
                assert_eq!(
                    deadline.remind_at.as_deref(),
                    wire.get("remind_at").and_then(Value::as_str),
                    "{suite_type}: {why}"
                );
                assert_eq!(
                    deadline.escalate_at.as_deref(),
                    wire.get("escalate_at").and_then(Value::as_str),
                    "{suite_type}: {why}"
                );
            }
        }
    }

    #[test]
    fn a_rejected_deadline_is_dropped_and_the_card_still_draws() {
        let rejects = cases(
            crate::gate_v2::GATE_V2_EVENT_TYPE,
            Some("deadline"),
            "reject",
        );
        assert_eq!(rejects.len(), 3, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert_eq!(parse_deadline(&content), None, "{why}");
            let decision = decision_for(&content);
            assert!(decision.gate_v2.is_some(), "{why}: still a gate.v2");
            assert_eq!(decision.deadline, None, "{why}");
        }
    }

    #[test]
    fn an_envelope_without_a_deadline_is_unchanged() {
        for suite_type in ENVELOPES {
            for (why, content) in cases(suite_type, None, "accept") {
                assert!(content.get("deadline").is_none(), "{why}");
                let deadline = decision_for(&content).deadline;
                assert_eq!(deadline, None, "{suite_type}: {why}");
            }
        }
    }

    #[test]
    fn a_bad_half_is_dropped_and_the_other_kept() {
        let content = json!({ "deadline": {
            "remind_at": "2026-02-30T10:00:00Z",
            "escalate_at": "2026-11-08T18:00:00.000Z"
        }});
        assert_eq!(
            parse_deadline(&content),
            Some(DecisionDeadline {
                remind_at: None,
                escalate_at: Some("2026-11-08T18:00:00.000Z".into()),
            })
        );
        for bad in [
            json!(null),
            json!("soon"),
            json!([]),
            json!({ "remind_at": 5 }),
        ] {
            assert_eq!(parse_deadline(&json!({ "deadline": bad })), None);
        }
    }

    #[test]
    fn iso_times_are_read_as_the_hub_reads_them() {
        assert_eq!(iso_ms("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(iso_ms("2000-03-01T00:00:00Z"), Some(951_868_800_000));
        assert_eq!(iso_ms("2000-03-01T00:00Z"), Some(951_868_800_000));
        assert_eq!(iso_ms("2000-03-01T00:00:00.250Z"), Some(951_868_800_250));
        assert_eq!(iso_ms("2000-03-01T00:00:00.5Z"), Some(951_868_800_500));
        assert_eq!(
            iso_ms("2000-03-01T00:00:00.123456789Z"),
            Some(951_868_800_123)
        );
        assert_eq!(
            iso_ms("2000-03-01T05:30:00+05:30"),
            Some(951_868_800_000),
            "an offset names the same instant"
        );
        assert_eq!(iso_ms("2000-02-29T23:00:00-01:00"), Some(951_868_800_000));
        for bad in [
            "in three hours",
            "",
            "2026-11-08",
            "2026-11-08T18:00:00",
            "2026-11-08 18:00:00Z",
            "2026-13-01T00:00:00Z",
            "2026-02-29T00:00:00Z",
            "2026-11-08T24:00:00Z",
            "2026-11-08T18:60:00Z",
            "2026-11-08T18:00:60Z",
            "2026-11-08T18:00:00.Z",
            "2026-11-08T18:00:00.1234567890Z",
            "2026-11-08T18:00:00+0530",
            "2026-11-08T18:00:00Zjunk",
            "２０26-11-08T18:00:00Z",
        ] {
            assert_eq!(iso_ms(bad), None, "{bad}");
        }
    }

    const NOW: &str = "2026-11-08T12:00:00.000Z";

    fn now() -> i64 {
        iso_ms(NOW).unwrap()
    }

    fn escalates(at: &str) -> DecisionDeadline {
        DecisionDeadline {
            remind_at: None,
            escalate_at: Some(at.into()),
        }
    }

    fn line_in(ms: i64) -> Option<String> {
        deadline_line(&escalates("2026-11-08T12:00:00Z"), now() - ms)
    }

    #[test]
    fn the_line_counts_down_to_the_escalation() {
        assert_eq!(
            deadline_line(&escalates("2026-11-08T15:00:00Z"), now()).as_deref(),
            Some("Escalates in 3 h")
        );
        const MIN: i64 = 60_000;
        const HOUR: i64 = 60 * MIN;
        // Minutes: never "0 min", whole minutes rounded down.
        assert_eq!(line_in(1).as_deref(), Some("Escalates in 1 min"));
        assert_eq!(line_in(MIN + 59_999).as_deref(), Some("Escalates in 1 min"));
        assert_eq!(line_in(59 * MIN).as_deref(), Some("Escalates in 59 min"));
        // Hours from the hour, up to two days.
        assert_eq!(line_in(HOUR).as_deref(), Some("Escalates in 1 h"));
        assert_eq!(line_in(2 * HOUR - 1).as_deref(), Some("Escalates in 1 h"));
        assert_eq!(line_in(47 * HOUR).as_deref(), Some("Escalates in 47 h"));
        // Days from 48 hours.
        assert_eq!(line_in(48 * HOUR).as_deref(), Some("Escalates in 2 d"));
        assert_eq!(line_in(80 * HOUR).as_deref(), Some("Escalates in 3 d"));
    }

    #[test]
    fn a_passed_escalation_says_so() {
        assert_eq!(line_in(0).as_deref(), Some("Escalated"));
        assert_eq!(line_in(-1).as_deref(), Some("Escalated"));
        assert_eq!(
            deadline_line(&escalates("2026-11-01T00:00:00Z"), now()).as_deref(),
            Some("Escalated")
        );
    }

    #[test]
    fn a_reminder_only_deadline_counts_down_and_then_is_quiet() {
        let reminds = |at: &str| DecisionDeadline {
            remind_at: Some(at.into()),
            escalate_at: None,
        };
        assert_eq!(
            deadline_line(&reminds("2026-11-08T12:45:00Z"), now()).as_deref(),
            Some("Reminder in 45 min")
        );
        let passed = reminds("2026-11-08T11:00:00Z");
        assert_eq!(deadline_line(&passed, now()), None);
        // Both: the escalation is the one shown.
        let both = DecisionDeadline {
            remind_at: Some("2026-11-08T13:00:00Z".into()),
            escalate_at: Some("2026-11-08T18:00:00Z".into()),
        };
        assert_eq!(
            deadline_line(&both, now()).as_deref(),
            Some("Escalates in 6 h")
        );
    }

    // ---- the notices ---------------------------------------------------------

    const NOTICES: [&str; 2] = [DECISION_REMINDED_EVENT_TYPE, DECISION_ESCALATED_EVENT_TYPE];

    #[test]
    fn every_accepted_notice_is_read_and_every_rejected_one_is_not() {
        for suite_type in NOTICES {
            for (why, content) in cases(suite_type, None, "accept") {
                assert_eq!(notice_type(&content), Some(suite_type), "{why}");
            }
            let rejects = cases(suite_type, None, "reject");
            assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
            for (why, content) in rejects {
                assert_eq!(notice_type(&content), None, "{suite_type}: {why}");
            }
        }
    }

    #[test]
    fn overdue_checks_are_reminded_alone_and_escalated_as_quiet_lines() {
        // A reminder of checks stands alone: nothing is posted for checks
        // before an escalation (fixture v11).
        for suite_type in NOTICES {
            for (why, content) in cases(suite_type, Some("checks"), "accept") {
                assert_eq!(notice_type(&content), Some(suite_type), "{why}");
                assert_eq!(
                    notice_card_type(&content),
                    Some(crate::gate_checks::CHECKS_WAITING_TYPE),
                    "{why}"
                );
                let row = projected(content);
                assert!(
                    matches!(
                        row.view,
                        ItemView::System {
                            kind: SystemKind::Notice { .. },
                            ..
                        }
                    ),
                    "{why}: {:?}",
                    row.view
                );
            }
        }
        // Any other kind still needs its card to reply to.
        let (_, mut gate) = cases(DECISION_REMINDED_EVENT_TYPE, None, "accept").remove(0);
        gate.as_object_mut().unwrap().remove("m.relates_to");
        assert_eq!(notice_type(&gate), None);
    }

    #[test]
    fn a_notice_is_not_a_decision_card() {
        for suite_type in NOTICES {
            let (_, content) = cases(suite_type, None, "accept").remove(0);
            assert_eq!(crate::embedded::embedded_suite_event(&content), None);
            assert_eq!(
                notice_card_type(&content),
                Some(crate::gate_v2::GATE_V2_EVENT_TYPE)
            );
            assert!(may_carry_notice(&content.to_string()));
        }
        assert!(!may_carry_notice(r#"{"body":"hello"}"#));
    }

    #[test]
    fn the_line_drops_the_markdown_link() {
        let sentence = "Escalated to workspace admins after 6 h: \"Launch post\" is still \
                        waiting for an approval at Review.";
        let link = " [Open the card](https://superpipeline.dev/b/brd_7c1f/c/crd_6b40)";
        assert_eq!(notice_text(&format!("{sentence}{link}")), sentence);
        assert_eq!(
            notice_text("Still waiting: \"Launch post\" has waited 3 h for an approval."),
            "Still waiting: \"Launch post\" has waited 3 h for an approval."
        );
        assert_eq!(notice_text("a\n b"), "a b");
    }

    /// A notice's row as `crate::timeline::row_from_parts` leaves it.
    fn notice_row(suite_type: &str, content: Value) -> TimelineRow {
        let mut it = crate::timeline::project_item_parts(
            "n",
            Some("$n"),
            "message",
            Some("m.text"),
            None,
            Some("@board_press:hs"),
            None,
            None,
            false,
            content.get("body").and_then(Value::as_str),
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
        it.detail = Some(suite_type.to_string());
        it.custom_payload = Some(CustomPayload(content));
        TimelineRow::new(it)
    }

    #[test]
    fn a_notice_is_drawn_as_a_quiet_system_line() {
        for suite_type in NOTICES {
            let (_, content) = cases(suite_type, None, "accept").remove(0);
            let row = notice_row(suite_type, content.clone());
            let ItemView::System {
                kind: SystemKind::Notice { who },
                text,
            } = &row.view
            else {
                panic!("{suite_type}: expected a system line, got {:?}", row.view);
            };
            assert_eq!(who, "@board_press:hs");
            assert_eq!(
                text,
                &notice_text(content["body"].as_str().unwrap()),
                "{suite_type}"
            );
            assert!(!text.contains("]("), "{suite_type}: no raw markdown");
        }
    }

    /// A message projected the way the timeline projects one: from its raw
    /// event, through `timeline::row_from_parts`.
    fn projected(content: Value) -> TimelineRow {
        use matrix_sdk::ruma::events::AnySyncTimelineEvent;
        use matrix_sdk::ruma::serde::Raw;
        let body = content
            .get("body")
            .and_then(Value::as_str)
            .map(str::to_string);
        let raw: Raw<AnySyncTimelineEvent> = Raw::from_json(
            serde_json::value::to_raw_value(&json!({
                "type": "m.room.message",
                "event_id": "$n",
                "sender": "@board_press:hs",
                "origin_server_ts": 1,
                "content": content,
            }))
            .unwrap(),
        );
        let dto = crate::timeline::project_item_parts(
            "n",
            Some("$n"),
            "message",
            Some("m.text"),
            None,
            Some("@board_press:hs"),
            None,
            None,
            false,
            body.as_deref(),
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
        crate::timeline::row_from_parts(dto, Some(&raw), matrix_sdk::ruma::user_id!("@me:hs"))
    }

    #[test]
    fn a_notice_is_recognised_from_its_raw_event() {
        for suite_type in NOTICES {
            let (_, content) = cases(suite_type, None, "accept").remove(0);
            let row = projected(content);
            assert_eq!(row.item.detail.as_deref(), Some(suite_type));
            assert!(
                matches!(
                    row.view,
                    ItemView::System {
                        kind: SystemKind::Notice { .. },
                        ..
                    }
                ),
                "{suite_type}: {:?}",
                row.view
            );
        }
    }

    #[test]
    fn a_notice_the_corpus_refuses_stays_the_sentence_it_is() {
        for suite_type in NOTICES {
            for (why, content) in cases(suite_type, None, "reject") {
                let row = projected(content);
                assert_eq!(row.item.detail, None, "{suite_type}: {why}");
                assert!(
                    matches!(row.view, ItemView::Bubble { .. }),
                    "{suite_type}: {why}: {:?}",
                    row.view
                );
            }
        }
    }
}
