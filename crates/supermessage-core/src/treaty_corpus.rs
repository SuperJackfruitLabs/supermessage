//! Treaty's board-room corpus, read by this client's own code.
//!
//! `fixtures/treaty-matrix-events/examples.json` is
//! `@superjackfruitlabs/treaty-matrix-events`'s `examples.json` at the version
//! `scripts/check-treaty-corpus.sh` pins: for every board-room event type, content a sender may
//! emit (`valid`) and content a sender must not emit and a carrier must not act on (`invalid`,
//! each saying what refuses it). treaty's JSON Schemas are the contract; this is the contract held
//! to this client, with no dependency on treaty at run time.
//!
//! Every type is one of three things here:
//!
//! - **Drawn or read**: a card, a notice or a receipt this client reads. Each valid case must be
//!   read as that type by the production reader; each case the schema refuses (`schema`,
//!   `unique`) must not be. A `recorded` case is refused only by the carrier, against what it
//!   recorded when it posted, so a reader is not asked to refuse it.
//! - **Sent**: an answer this client builds. For each case, every card of the type it answers is
//!   offered to the production builder with the case's own choices: some card must build exactly a
//!   valid case, and no card may build an invalid one, whatever refuses it.
//! - **Not read**, with the reason.
//!
//! A type treaty adds that is in none of these fails
//! [`every_type_in_the_corpus_is_accounted_for`].

use std::collections::BTreeSet;

use serde_json::{Map, Value};

use crate::custom_events::{
    default_registry, resolve_custom_event, CustomEventDecision, CustomEventView, GATE_EVENT_TYPE,
    PERMISSION_REQUEST_EVENT_TYPE,
};
use crate::dto::TimelineItemDto;
use crate::embedded::{embedded_suite_event, EMBEDDED_GATE_KEY, EMBEDDED_PERMISSION_KEY};

const EXAMPLES: &str = include_str!("../../../fixtures/treaty-matrix-events/examples.json");

/// The key a v1 question card rides under on its prose message. Named here only: this client
/// does not read it (see [`NOT_READ`]).
const EMBEDDED_ELICITATION_KEY: &str = "dev.superpipeline.elicitation";

/// Where a reader looks for a type.
#[derive(Clone, Copy)]
enum Read {
    /// A card on a prose message (`suite_event_type`), drawn through `embedded_suite_event` and
    /// the production registry with a decision to offer.
    Card,
    /// A card sent as its own event type, drawn through the registry with a decision to offer.
    Event,
    /// A v1 card under a content key on a prose message: the key, and the type that draws it.
    Keyed(&'static str, &'static str),
    /// A reminder or an escalation, read from the message's content.
    Notice,
    /// A notice or a receipt read off a timeline row by its module.
    Row(fn(&TimelineItemDto) -> bool),
}

/// Every type this client draws or reads, and how.
const READ: &[(&str, Read)] = &[
    (crate::gate_v2::GATE_V2_EVENT_TYPE, Read::Card),
    (crate::question_v2::QUESTION_V2_EVENT_TYPE, Read::Card),
    (crate::delivery_v2::DELIVERY_V2_EVENT_TYPE, Read::Card),
    (crate::attention_v2::ATTENTION_V2_EVENT_TYPE, Read::Card),
    (crate::permission_v2::PERMISSION_V2_EVENT_TYPE, Read::Card),
    (crate::gate_checks::CHECKS_WAITING_TYPE, Read::Card),
    (GATE_EVENT_TYPE, Read::Event),
    (PERMISSION_REQUEST_EVENT_TYPE, Read::Event),
    (
        EMBEDDED_GATE_KEY,
        Read::Keyed(EMBEDDED_GATE_KEY, GATE_EVENT_TYPE),
    ),
    (
        EMBEDDED_PERMISSION_KEY,
        Read::Keyed(EMBEDDED_PERMISSION_KEY, PERMISSION_REQUEST_EVENT_TYPE),
    ),
    (
        crate::decision_timeout::DECISION_REMINDED_EVENT_TYPE,
        Read::Notice,
    ),
    (
        crate::decision_timeout::DECISION_ESCALATED_EVENT_TYPE,
        Read::Notice,
    ),
    (
        crate::gate_release::RELEASED_TYPE,
        Read::Row(release_notice),
    ),
    (
        crate::gate_release::PULLED_BACK_TYPE,
        Read::Row(release_notice),
    ),
    (
        crate::gate_quorum::GATE_PROGRESS_TYPE,
        Read::Row(crate::gate_quorum::reads),
    ),
    (
        crate::gate_checks::CHECK_FAILED_TYPE,
        Read::Row(crate::gate_checks::reads),
    ),
    (
        crate::gate_checks::CHECK_OVERRIDDEN_TYPE,
        Read::Row(crate::gate_checks::reads),
    ),
    (
        crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
        Read::Row(crate::gate_outcome::reads),
    ),
    (
        crate::attention_v2::ATTENTION_OUTCOME_EVENT_TYPE,
        Read::Row(crate::attention_v2::reads),
    ),
    (
        crate::permission_v2::PERMISSION_OUTCOME_EVENT_TYPE,
        Read::Row(crate::permission_v2::reads),
    ),
];

/// Every answer this client sends, by the card type it answers.
const SENT: &[(&str, &str)] = &[
    (
        crate::gate_v2::GATE_DECISION_V2_SUITE_TYPE,
        crate::gate_v2::GATE_V2_EVENT_TYPE,
    ),
    (crate::timeline::GATE_DECISION_SUITE_TYPE, GATE_EVENT_TYPE),
    (
        crate::question_v2::QUESTION_ANSWER_V2_SUITE_TYPE,
        crate::question_v2::QUESTION_V2_EVENT_TYPE,
    ),
    (
        crate::delivery_v2::DELIVERY_ANSWER_V2_SUITE_TYPE,
        crate::delivery_v2::DELIVERY_V2_EVENT_TYPE,
    ),
    (
        crate::attention_v2::ATTENTION_ANSWER_V2_SUITE_TYPE,
        crate::attention_v2::ATTENTION_V2_EVENT_TYPE,
    ),
    (
        crate::permission_v2::PERMISSION_ANSWER_V2_SUITE_TYPE,
        crate::permission_v2::PERMISSION_V2_EVENT_TYPE,
    ),
    (
        crate::gate_release::PULL_BACK_SUITE_TYPE,
        crate::gate_v2::GATE_V2_EVENT_TYPE,
    ),
    (
        crate::gate_checks::CHECK_OVERRIDE_SUITE_TYPE,
        crate::gate_checks::CHECKS_WAITING_TYPE,
    ),
];

/// Types this client does not read, and why.
const NOT_READ: &[(&str, &str)] = &[(
    EMBEDDED_ELICITATION_KEY,
    "an agent's question is drawn from question.v2 only; a message carrying just the v1 card \
     stays the prose it also is (`crate::embedded::embedded_suite_event`)",
)];

fn schemas() -> Map<String, Value> {
    let corpus: Value = serde_json::from_str(EXAMPLES).expect("the corpus is JSON");
    corpus["schemas"]
        .as_object()
        .expect("the corpus lists its schemas")
        .clone()
}

fn valid(event_type: &str) -> Vec<(String, Value)> {
    schemas()[event_type]["valid"]
        .as_object()
        .unwrap_or_else(|| panic!("{event_type} has valid cases"))
        .iter()
        .map(|(label, content)| (label.clone(), content.clone()))
        .collect()
}

/// `(why, refused by, content)` for each invalid case.
fn invalid(event_type: &str) -> Vec<(String, String, Value)> {
    schemas()[event_type]["invalid"]
        .as_array()
        .unwrap_or_else(|| panic!("{event_type} has invalid cases"))
        .iter()
        .map(|c| {
            (
                c["why"].as_str().unwrap_or_default().to_string(),
                c["refusedBy"].as_str().unwrap_or_default().to_string(),
                c["body"].clone(),
            )
        })
        .collect()
}

fn body(content: &Value) -> Option<&str> {
    content.get("body").and_then(Value::as_str)
}

/// The decision the production registry offers for `payload` drawn as `event_type`.
fn decision(event_type: &str, payload: &Value, body: Option<&str>) -> Option<CustomEventDecision> {
    match resolve_custom_event(default_registry(), Some(event_type), Some(payload), body) {
        CustomEventView::Rendered { decision, .. } => decision,
        _ => None,
    }
}

/// The decision a prose message carries as `event_type`, if it is drawn as that type.
fn card(event_type: &str, content: &Value) -> Option<CustomEventDecision> {
    let (drawn_as, payload) = embedded_suite_event(content)?;
    if drawn_as != event_type {
        return None;
    }
    decision(drawn_as, &payload, body(content))
}

/// A notice or receipt as the timeline hands it to a module: its own event, sent by the hub.
fn row(event_type: &str, content: &Value) -> TimelineItemDto {
    crate::timeline::project_item_parts(
        "$notice",
        Some("$notice"),
        "customMessage",
        None,
        Some(event_type),
        Some("@speaker:example.org"),
        None,
        None,
        false,
        body(content),
        None,
        None,
        Some(content.clone()),
        Some(1),
        false,
        None,
        None,
        false,
        Vec::new(),
        Vec::new(),
    )
}

fn release_notice(item: &TimelineItemDto) -> bool {
    crate::gate_release::notice_of(item).is_some()
}

/// Whether this client reads `content` as `event_type`, the way `how` says it looks.
fn reads(event_type: &str, how: Read, content: &Value) -> bool {
    match how {
        Read::Card => card(event_type, content).is_some(),
        Read::Event => decision(event_type, content, body(content)).is_some(),
        Read::Keyed(key, drawn_as) => {
            let message = serde_json::json!({
                "msgtype": "m.text",
                "body": "A request, in prose.",
                key: content,
            });
            card(drawn_as, &message).is_some()
        }
        Read::Notice => crate::decision_timeout::notice_type(content) == Some(event_type),
        Read::Row(read) => read(&row(event_type, content)),
    }
}

// ---- answers

/// The card decisions this client draws from each valid case of `card_type`.
fn cards(card_type: &str) -> Vec<CustomEventDecision> {
    let how = READ
        .iter()
        .find(|(t, _)| *t == card_type)
        .map(|(_, how)| *how)
        .expect("an answered card type is read");
    valid(card_type)
        .into_iter()
        .filter_map(|(_, content)| match how {
            Read::Card => card(card_type, &content),
            Read::Event => decision(card_type, &content, body(&content)),
            _ => None,
        })
        .collect()
}

fn text<'a>(content: &'a Value, key: &str) -> Option<&'a str> {
    content.get(key).and_then(Value::as_str)
}

/// The events the corpus's cards were posted as, for `answer_type`: what its valid answers
/// reference. A host hands the builder the event of the card the reader answered, never an event
/// the answer chose, so these are the only references it is given.
fn card_events(answer_type: &str) -> BTreeSet<String> {
    valid(answer_type)
        .iter()
        .filter_map(|(_, answer)| {
            answer
                .get("m.relates_to")?
                .get("event_id")?
                .as_str()
                .map(str::to_string)
        })
        .collect()
}

/// What `answer_type` content this client builds from `card`, posted as `reply`, with the
/// answer's own choices; nothing when the builder refuses.
fn build(answer_type: &str, card: &CustomEventDecision, answer: &Value, reply: &str) -> Vec<Value> {
    let option = text(answer, "option_id");
    let comment = text(answer, "comment");
    let typed = text(answer, "text");
    let built = |r: crate::error::CoreResult<Value>| r.ok();
    let mut out = Vec::new();
    match answer_type {
        crate::gate_v2::GATE_DECISION_V2_SUITE_TYPE => {
            if let (Some(gate), Some(option)) = (&card.gate_v2, option) {
                // No version, and each version the gate offers: whichever the reader chose.
                let versions = gate.variants.iter().map(|v| Some(v.id.as_str()));
                for chosen in std::iter::once(None).chain(versions) {
                    out.extend(built(crate::gate_v2::decision_request(
                        gate, option, chosen, comment, reply,
                    )));
                }
            }
        }
        crate::timeline::GATE_DECISION_SUITE_TYPE => {
            if let Some(option) = option {
                let gate_id = text(answer, "gate_id").unwrap_or_default();
                out.extend(built(crate::timeline::gate_decision_request(
                    gate_id,
                    option,
                    comment,
                    reply,
                    &card.prompt,
                )));
            }
        }
        crate::question_v2::QUESTION_ANSWER_V2_SUITE_TYPE => {
            if let Some(question) = &card.question_v2 {
                out.extend(built(crate::question_v2::answer_request(
                    question, option, typed, reply,
                )));
            }
        }
        crate::delivery_v2::DELIVERY_ANSWER_V2_SUITE_TYPE => {
            if let (Some(delivery), Some(option)) = (&card.delivery_v2, option) {
                out.extend(built(crate::delivery_v2::answer_request(
                    delivery, option, typed, reply,
                )));
            }
        }
        crate::attention_v2::ATTENTION_ANSWER_V2_SUITE_TYPE => {
            if let (Some(attention), Some(option)) = (&card.attention_v2, option) {
                out.extend(built(crate::attention_v2::answer_request(
                    attention, option, typed, reply,
                )));
            }
        }
        crate::permission_v2::PERMISSION_ANSWER_V2_SUITE_TYPE => {
            if let (Some(permission), Some(option)) = (&card.permission_v2, option) {
                out.extend(built(crate::permission_v2::answer_request(
                    permission, option, reply,
                )));
            }
        }
        crate::gate_release::PULL_BACK_SUITE_TYPE => {
            if let Some(gate) = &card.gate_v2 {
                out.extend(built(crate::gate_release::pull_back_request(
                    &gate.gate_id,
                    comment.unwrap_or_default(),
                    reply,
                )));
            }
        }
        crate::gate_checks::CHECK_OVERRIDE_SUITE_TYPE => {
            if let Some(waiting) = &card.checks_waiting {
                out.extend(built(crate::gate_checks::override_request(
                    waiting,
                    text(answer, "check_name").unwrap_or_default(),
                    comment.unwrap_or_default(),
                    reply,
                )));
            }
        }
        other => panic!("no builder for {other}"),
    }
    out
}

/// An answer as compared: without its prose `body`, which is words for a person and not the
/// contract, and with a key whose value is `null` the same as one left out, as every reader takes
/// them.
fn wire(content: &Value) -> Map<String, Value> {
    let mut map = content.as_object().cloned().unwrap_or_default();
    map.remove("body");
    map.retain(|_, v| !v.is_null());
    map
}

/// Whether `built` is `answer` on the wire. A `schema_version` the answer leaves out is one its
/// schema does not require (every schema that requires it has it in every case), and the baseline
/// version this client adds says nothing more.
fn same_answer(built: &Value, answer: &Value) -> bool {
    let want = wire(answer);
    let mut got = wire(built);
    if !want.contains_key("schema_version") && got.get("schema_version") == Some(&Value::from(1)) {
        got.remove("schema_version");
    }
    got == want
}

/// Whether some card of `card_type`, answered from where it was posted, builds exactly `answer`.
fn sends(answer_type: &str, card_type: &str, answer: &Value) -> bool {
    let events = card_events(answer_type);
    cards(card_type).iter().any(|card| {
        events
            .iter()
            .flat_map(|reply| build(answer_type, card, answer, reply))
            .any(|built| same_answer(&built, answer))
    })
}

// ---- the tests

#[test]
fn every_type_in_the_corpus_is_accounted_for() {
    let corpus: BTreeSet<String> = schemas().keys().cloned().collect();
    let here: BTreeSet<String> = READ
        .iter()
        .map(|(t, _)| *t)
        .chain(SENT.iter().map(|(t, _)| *t))
        .chain(NOT_READ.iter().map(|(t, _)| *t))
        .map(str::to_string)
        .collect();
    assert_eq!(
        corpus, here,
        "every type treaty's corpus holds is read, sent or listed as not read"
    );
}

#[test]
fn every_valid_case_is_read_as_its_type() {
    let mut failed = Vec::new();
    for (event_type, how) in READ {
        for (label, content) in valid(event_type) {
            if !reads(event_type, *how, &content) {
                failed.push(format!("{event_type}: {label}"));
            }
        }
    }
    assert!(
        failed.is_empty(),
        "valid cases not read:\n{}",
        failed.join("\n")
    );
}

/// Refused cases this client reads anyway: `(type, the case's reason as treaty words it, why the
/// reading is still honest, a check that it is)`.
///
/// treaty's corpus says a sender must not emit these and a carrier must not act on them; a
/// reader's obligation is narrower, to offer no decision it cannot honestly resolve. Each case here
/// is read with the refused part left out or made harmless, and the check proves it. A refused
/// case read and not listed fails; so does a listed case this client stops reading, so the list
/// cannot outlive what it excuses.
type Honest = fn(&Value) -> bool;
const TOLERATED: &[(&str, &str, &str, Honest)] = &[
    (
        crate::gate_v2::GATE_V2_EVENT_TYPE,
        "deadline: ",
        "a deadline this client cannot read is left off the card; the gate itself is the hub's",
        |c| card(crate::gate_v2::GATE_V2_EVENT_TYPE, c).is_some_and(|d| d.deadline.is_none()),
    ),
    (
        crate::delivery_v2::DELIVERY_V2_EVENT_TYPE,
        "switch_to_automatic on an incomplete preview",
        "the card is drawn with the switch withheld, as for any preview it could not show in full",
        |c| {
            card(crate::delivery_v2::DELIVERY_V2_EVENT_TYPE, c)
                .and_then(|d| d.delivery_v2)
                .is_some_and(|d| d.approvals_withheld.is_some())
        },
    ),
    (
        GATE_EVENT_TYPE,
        "no body",
        "the body is for a stock client; this one draws the card from its fields",
        |_| true,
    ),
    (
        GATE_EVENT_TYPE,
        "carries ",
        "fields this client does not know are not read (treaty: readers ignore unknown properties)",
        |_| true,
    ),
    (
        GATE_EVENT_TYPE,
        "a handoff_summary that is not a string",
        "a summary that is not text is not drawn; the decision does not depend on it",
        |c| {
            matches!(
                resolve_custom_event(default_registry(), Some(GATE_EVENT_TYPE), Some(c), body(c)),
                CustomEventView::Rendered { reasoning: None, decision: Some(_), .. }
            )
        },
    ),
    (
        PERMISSION_REQUEST_EVENT_TYPE,
        "schema_version 2",
        "drawn flagged as newer than this client knows, and answered by name as every version is",
        |c| newer_version(PERMISSION_REQUEST_EVENT_TYPE, c),
    ),
    (
        EMBEDDED_PERMISSION_KEY,
        "schema_version 2",
        "drawn flagged as newer than this client knows, and answered by name as every version is",
        |c| newer_version(PERMISSION_REQUEST_EVENT_TYPE, c),
    ),
    (
        PERMISSION_REQUEST_EVENT_TYPE,
        "five options",
        "four buttons are drawn; the prose beside them lists every option, answerable by name",
        |c| decision(PERMISSION_REQUEST_EVENT_TYPE, c, None).is_some_and(|d| d.options.len() <= 4),
    ),
    (
        EMBEDDED_PERMISSION_KEY,
        "five options",
        "four buttons are drawn; the prose beside them lists every option, answerable by name",
        |c| decision(PERMISSION_REQUEST_EVENT_TYPE, c, None).is_some_and(|d| d.options.len() <= 4),
    ),
    (
        PERMISSION_REQUEST_EVENT_TYPE,
        "a negative request_seq",
        "a v1 permission is answered by the option's name; the sequence number is never sent",
        |_| true,
    ),
    (
        EMBEDDED_PERMISSION_KEY,
        "a negative request_seq",
        "a v1 permission is answered by the option's name; the sequence number is never sent",
        |_| true,
    ),
    (
        crate::gate_release::RELEASED_TYPE,
        "a reason on a release that went ahead",
        "a release reads as released; the reason is read only on a blocked one",
        |c| {
            crate::gate_release::notice_of(&row(crate::gate_release::RELEASED_TYPE, c))
                .is_some_and(|n| n.settled == crate::gate_release::Settled::Released)
        },
    ),
    (
        crate::gate_release::RELEASED_TYPE,
        "a release time that is not a time",
        "the release time is not read off the notice; the held card already has it",
        |_| true,
    ),
    (
        crate::gate_release::PULLED_BACK_TYPE,
        "a blank comment",
        "a blank comment is shown as none",
        |c| {
            crate::gate_release::notice_of(&row(crate::gate_release::PULLED_BACK_TYPE, c))
                .is_some_and(|n| {
                    matches!(n.settled, crate::gate_release::Settled::PulledBack { comment: None, .. })
                })
        },
    ),
    (
        crate::gate_release::PULLED_BACK_TYPE,
        "no reference to the gate's event",
        "a notice settles only the gate it names, and only when sent by that gate's sender",
        |_| true,
    ),
    (
        crate::gate_quorum::GATE_PROGRESS_TYPE,
        "a reply relation",
        "progress counts only for the gate it names, and only from that gate's sender",
        |_| true,
    ),
    (
        crate::gate_checks::CHECK_OVERRIDDEN_TYPE,
        "a blank comment",
        "an override's comment is not shown; the notice only settles the check, from the card's sender",
        |_| true,
    ),
    (
        crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
        "no m.relates_to",
        "an outcome closes the gate whose id it names, and only when sent by that gate's sender",
        |c| c.get("gate_id").and_then(Value::as_str).is_some_and(|id| !id.is_empty()),
    ),
    (
        crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
        "a quoted reply rather than an m.reference",
        "an outcome closes the gate whose id it names, and only when sent by that gate's sender",
        |c| c.get("gate_id").and_then(Value::as_str).is_some_and(|id| !id.is_empty()),
    ),
    (
        crate::gate_outcome::GATE_OUTCOME_EVENT_TYPE,
        "no gate_id",
        "an outcome closes the card its m.reference names, and only when sent by that card's sender",
        |c| c.get("m.relates_to").and_then(|r| r.get("rel_type")) == Some(&Value::from("m.reference")),
    ),
];

fn newer_version(event_type: &str, content: &Value) -> bool {
    matches!(
        resolve_custom_event(default_registry(), Some(event_type), Some(content), None),
        CustomEventView::Rendered {
            newer_version: true,
            decision: Some(_),
            ..
        }
    )
}

fn tolerance(
    event_type: &str,
    why: &str,
) -> Option<&'static (&'static str, &'static str, &'static str, Honest)> {
    TOLERATED
        .iter()
        .find(|(t, prefix, _, _)| *t == event_type && why.starts_with(prefix))
}

#[test]
fn no_case_the_schema_refuses_is_read_unless_its_reading_is_honest() {
    let mut failed = Vec::new();
    let mut used = BTreeSet::new();
    for (event_type, how) in READ {
        for (why, refused_by, content) in invalid(event_type) {
            // A `recorded` case is the carrier's to refuse, against what it posted.
            if refused_by == "recorded" || !reads(event_type, *how, &content) {
                continue;
            }
            match tolerance(event_type, &why) {
                Some(entry @ (_, prefix, _, honest)) => {
                    used.insert((entry.0, *prefix));
                    if !honest(&content) {
                        failed.push(format!(
                            "{event_type} ({refused_by}): {why} — read, and not as excused"
                        ));
                    }
                }
                None => failed.push(format!("{event_type} ({refused_by}): {why}")),
            }
        }
    }
    for (event_type, prefix, _, _) in TOLERATED {
        if !used.contains(&(*event_type, *prefix)) {
            failed.push(format!(
                "{event_type}: \"{prefix}…\" is excused but no longer read"
            ));
        }
    }
    assert!(
        failed.is_empty(),
        "refused cases read:\n{}",
        failed.join("\n")
    );
}

#[test]
fn every_valid_answer_is_one_this_client_builds() {
    let mut failed = Vec::new();
    for (answer_type, card_type) in SENT {
        for (label, answer) in valid(answer_type) {
            if !sends(answer_type, card_type, &answer) {
                failed.push(format!("{answer_type}: {label}"));
            }
        }
    }
    assert!(
        failed.is_empty(),
        "valid answers never built:\n{}",
        failed.join("\n")
    );
}

#[test]
fn no_invalid_answer_is_ever_built() {
    let mut failed = Vec::new();
    for (answer_type, card_type) in SENT {
        for (why, refused_by, answer) in invalid(answer_type) {
            if sends(answer_type, card_type, &answer) {
                failed.push(format!("{answer_type} ({refused_by}): {why}"));
            }
        }
    }
    assert!(
        failed.is_empty(),
        "invalid answers built:\n{}",
        failed.join("\n")
    );
}

#[test]
fn a_v1_question_card_alone_stays_prose() {
    for (label, content) in valid(EMBEDDED_ELICITATION_KEY) {
        let message = serde_json::json!({
            "msgtype": "m.text",
            "body": "A question, in prose.",
            EMBEDDED_ELICITATION_KEY: content,
        });
        assert!(embedded_suite_event(&message).is_none(), "{label}");
    }
}

// ---- the bounds the corpus does not reach

/// A valid case of `event_type` with its options replaced by `options`.
fn with_options(event_type: &str, label: &str, options: Vec<Value>) -> Value {
    let (_, mut content) = valid(event_type)
        .into_iter()
        .find(|(l, _)| l == label)
        .unwrap_or_else(|| panic!("{event_type} has the case \"{label}\""));
    content["options"] = Value::Array(options);
    content
}

/// A gate.v2 offering `n` options: an approval, a request for changes and `n - 2` rejections.
fn gate_with(n: usize) -> Value {
    let mut options = vec![
        serde_json::json!({"id": "approve", "label": "Approve", "meaning": "approve", "needs_comment": false}),
        serde_json::json!({"id": "request_changes", "label": "Request changes", "meaning": "revise", "needs_comment": true}),
    ];
    options.extend((2..n).map(|i| {
        serde_json::json!({"id": format!("reject_{i}"), "label": format!("Reject {i}"), "meaning": "reject", "needs_comment": false})
    }));
    with_options(
        crate::gate_v2::GATE_V2_EVENT_TYPE,
        "an ordinary gate bound to no subject: no subject field, and the body is the v1 sentence",
        options,
    )
}

/// The most options treaty's gate.v2, question.v2 and attention.v2 schemas allow
/// (`options.maxItems`), and the hub sends.
const CONTRACT_OPTIONS_MAX: usize = 20;

#[test]
fn a_gate_with_as_many_options_as_the_contract_allows_is_drawn_whole() {
    let drawn = card(
        crate::gate_v2::GATE_V2_EVENT_TYPE,
        &gate_with(CONTRACT_OPTIONS_MAX),
    )
    .and_then(|d| d.gate_v2)
    .expect("drawn from gate.v2");
    assert_eq!(drawn.options.len(), CONTRACT_OPTIONS_MAX);
    // One more is not the contract: the v1 card beside it is read instead.
    assert!(card(
        crate::gate_v2::GATE_V2_EVENT_TYPE,
        &gate_with(CONTRACT_OPTIONS_MAX + 1)
    )
    .is_none());
}

#[test]
fn a_waiting_card_on_a_long_pipeline_offers_every_earlier_stage() {
    let resume = |id: String, label: String, meaning: &str| {
        serde_json::json!({
            "id": id, "label": label, "meaning": meaning, "needs_comment": false,
            "input": {"kind": "text", "label": "What should the next attempt do?", "required": true, "max_length": 2730},
        })
    };
    // Resume here, resume at each of eighteen earlier stages, archive: twenty.
    let mut options = vec![resume(
        "resume_here".into(),
        "Resume here".into(),
        "approve",
    )];
    options.extend((1..=18).map(|i| {
        resume(
            format!("resume_at:stage_{i}"),
            format!("Resume at stage {i}"),
            "choose",
        )
    }));
    options.push(serde_json::json!({"id": "archive", "label": "Archive", "meaning": "reject", "needs_comment": false}));
    let content = with_options(
        crate::attention_v2::ATTENTION_V2_EVENT_TYPE,
        "a blocked card with an earlier stage to resume at",
        options,
    );
    let drawn = card(crate::attention_v2::ATTENTION_V2_EVENT_TYPE, &content)
        .and_then(|d| d.attention_v2)
        .expect("drawn from attention.v2");
    assert_eq!(drawn.options.len(), CONTRACT_OPTIONS_MAX);
}

// ---- where this client and treaty disagree, pending a treaty change
//
// Each test below pins content treaty's schemas allow and this client refuses, because the
// schema is the side that is wrong. Each names the treaty change that settles it; once treaty
// refuses the content too, the case moves into the corpus and the test goes.

/// A subject's `revision` counts versions of the frozen subject: a whole number. treaty's
/// schemas type it as any `number`. Treaty follow-up: `subject.revision` becomes
/// `{"type": "integer", "minimum": 0}` in question.v2, permission.v2 and gate.v2, with a
/// fractional revision as an invalid case.
#[test]
fn disagreement_a_fractional_revision_is_not_a_subject_this_client_binds() {
    let label = "subject-bound: a strict decision with a complete preview";
    let (_, mut content) = valid(crate::question_v2::QUESTION_V2_EVENT_TYPE)
        .into_iter()
        .find(|(l, _)| l == label)
        .expect("the corpus has a subject-bound question");
    assert!(card(crate::question_v2::QUESTION_V2_EVENT_TYPE, &content).is_some());
    content["subject"]["revision"] = Value::from(1.5);
    assert!(card(crate::question_v2::QUESTION_V2_EVENT_TYPE, &content).is_none());
}

/// A gate with no options cannot be decided: there is nothing to answer it with. treaty's gate.v2
/// schema has no `minItems` on `options`. Treaty follow-up: `options.minItems: 1` in gate.v2,
/// question.v2 (where options are present), attention.v2 and permission.v2, with an empty list
/// as an invalid case. Here the v1 card beside it is read instead.
#[test]
fn disagreement_a_gate_with_no_options_is_not_drawn_from_v2() {
    assert!(card(crate::gate_v2::GATE_V2_EVENT_TYPE, &gate_with(2)).is_some());
    let empty = with_options(
        crate::gate_v2::GATE_V2_EVENT_TYPE,
        "an ordinary gate bound to no subject: no subject field, and the body is the v1 sentence",
        Vec::new(),
    );
    assert!(card(crate::gate_v2::GATE_V2_EVENT_TYPE, &empty).is_none());
}
