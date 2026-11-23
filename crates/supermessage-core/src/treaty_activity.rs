//! Treaty's chat activity and Live Activity cases, read by this client's own code.
//!
//! `agentpod.matrix-activity` and `agentpod.live-activity` are contracts in treaty: JSON Schemas
//! for what a station sends into chat (a turn's card, its deltas, a failed turn, a voice note's
//! transcript and spoken reply) and for the Live Activity the hub pushes, each with cases a
//! producer may send (`valid`) and must not (`invalid`). The cases are vendored under
//! `vendor/treaty/` at the release `treaty.json` pins.
//!
//! Every case of every schema runs through the parser this client really uses:
//!
//! - **A valid case is read.** Including the cases written for readers: a higher
//!   `schema_version`, a status, kind or property this build was not taught. Those are drawn
//!   best-effort, never dropped.
//! - **A case the schema refuses is not read, unless reading it is honest**, and then it is
//!   listed in [`TOLERATED`] with the reason and a check that proves it. A refused case read and
//!   not listed fails; so does a listed case this client stops reading.
//! - **A Live Activity request this client sends** is built by the production builder: every
//!   valid request is one it builds, and no invalid one ever is.
//!
//! A schema treaty adds that is in none of these fails
//! [`every_schema_in_the_cases_is_accounted_for`].

use std::collections::BTreeSet;

use serde::de::DeserializeOwned;
use serde_json::{Map, Value};

use crate::custom_events::{
    default_registry, resolve_custom_event, CustomEventView, TURN_ACTIVITY_EVENT_TYPE,
};
use crate::live::{
    StreamDeltaToDeviceEventContent, ThoughtDeltaToDeviceEventContent, ToolPayload, ToolPhase,
    ToolUpdateToDeviceEventContent,
};
use crate::live_activity::{delete_body, register_body, LiveActivityToken, LiveActivityTokenKind};
use crate::turn_error::{parse_turn_error, TurnErrorKind, MESSAGE_MAX_CHARS};
use crate::turns::TurnStep;
use crate::voice_reply::parse_voice_reply;
use crate::voice_transcript::parse_voice_transcript;

const MATRIX: &str = include_str!("../../../vendor/treaty/fixtures/matrix-events/examples.json");
const LIVE_ACTIVITY: &str =
    include_str!("../../../vendor/treaty/fixtures/agentpod-live-activity/examples.json");
const LIVE_ACTIVITY_HTTP: &str =
    include_str!("../../../vendor/treaty/fixtures/http/agentpod/live-activity.v1.cases.json");

// ---- the readers

/// What this client reads a case as, or `None` when it does not read it.
type Reader = fn(&Value) -> Option<Read>;

/// What a reader made of a case: enough to say whether it was drawn, and how.
#[derive(Debug)]
enum Read {
    Turn(Box<CustomEventView>),
    Delta,
    Tool(TurnStep),
    TurnError(crate::turn_error::TurnErrorCard),
    Transcript(crate::voice_transcript::VoiceNoteTranscript),
    VoiceReply,
}

fn serde<T: DeserializeOwned>(content: &Value) -> Option<T> {
    serde_json::from_value(content.clone()).ok()
}

/// `dev.agentpod.turn.v1` is a room event, drawn by the registry's turn renderer.
fn turn(content: &Value) -> Option<Read> {
    match resolve_custom_event(
        default_registry(),
        Some(TURN_ACTIVITY_EVENT_TYPE),
        Some(content),
        None,
    ) {
        view @ CustomEventView::Rendered { .. } => Some(Read::Turn(Box::new(view))),
        _ => None,
    }
}

/// The deltas are to-device events: ruma hands the content to serde.
fn stream_delta(content: &Value) -> Option<Read> {
    serde::<StreamDeltaToDeviceEventContent>(content).map(|_| Read::Delta)
}

fn thought_delta(content: &Value) -> Option<Read> {
    serde::<ThoughtDeltaToDeviceEventContent>(content).map(|_| Read::Delta)
}

/// A tool update, as `live::listen` turns it into a host's payload and a turn's step.
fn tool_update(content: &Value) -> Option<Read> {
    let wire = serde::<ToolUpdateToDeviceEventContent>(content)?;
    Some(Read::Tool(TurnStep::from_payload(&ToolPayload::from_wire(
        wire,
    ))))
}

/// The value under `dev.agentpod.turn_error` on an `m.text`.
fn turn_error(content: &Value) -> Option<Read> {
    parse_turn_error(content).map(Read::TurnError)
}

fn voice_transcript(content: &Value) -> Option<Read> {
    parse_voice_transcript(content).map(Read::Transcript)
}

fn voice_reply(content: &Value) -> Option<Read> {
    parse_voice_reply(content).map(|_| Read::VoiceReply)
}

/// Every chat activity shape, and the reader that reads it.
const READ: &[(&str, Reader)] = &[
    (TURN_ACTIVITY_EVENT_TYPE, turn),
    ("dev.agentpod.stream.delta", stream_delta),
    ("dev.agentpod.thought.delta", thought_delta),
    ("dev.agentpod.tool.update", tool_update),
    (crate::turn_error::TURN_ERROR_KEY, turn_error),
    (
        crate::voice_transcript::VOICE_TRANSCRIPT_KEY,
        voice_transcript,
    ),
    (crate::voice_reply::VOICE_REPLY_KEY, voice_reply),
];

/// The chat activity shapes [`crate::treaty_corpus`] leaves to this module.
pub(crate) const ACTIVITY: [&str; 7] = [
    TURN_ACTIVITY_EVENT_TYPE,
    "dev.agentpod.stream.delta",
    "dev.agentpod.thought.delta",
    "dev.agentpod.tool.update",
    crate::turn_error::TURN_ERROR_KEY,
    crate::voice_transcript::VOICE_TRANSCRIPT_KEY,
    crate::voice_reply::VOICE_REPLY_KEY,
];

/// Live Activity schemas no Rust code parses, and why.
const LIVE_NOT_READ: &[(&str, &str)] = &[
    (
        "fleet-content-state.v1",
        "the hub pushes it to ActivityKit, which hands it to the iOS FleetActivityAttributes; \
         FleetActivityAttributesTests decodes every case",
    ),
    (
        "live-activity-payload.v1",
        "the `aps` envelope is APNs's and ActivityKit's to read; no app code sees it",
    ),
];

// ---- the cases

fn schemas(corpus: &str) -> Map<String, Value> {
    let corpus: Value = serde_json::from_str(corpus).expect("the cases are JSON");
    corpus["schemas"]
        .as_object()
        .expect("the cases list their schemas")
        .clone()
}

fn valid(event_type: &str) -> Vec<(String, Value)> {
    schemas(MATRIX)[event_type]["valid"]
        .as_object()
        .unwrap_or_else(|| panic!("{event_type} has valid cases"))
        .iter()
        .map(|(label, content)| (label.clone(), content.clone()))
        .collect()
}

/// `(why, content)` for each invalid case.
fn invalid(event_type: &str) -> Vec<(String, Value)> {
    schemas(MATRIX)[event_type]["invalid"]
        .as_array()
        .unwrap_or_else(|| panic!("{event_type} has invalid cases"))
        .iter()
        .map(|c| {
            (
                c["why"].as_str().unwrap_or_default().to_string(),
                c["body"].clone(),
            )
        })
        .collect()
}

// ---- refused cases read anyway

/// Refused cases this client reads anyway: `(type, the case's reason as treaty words it, why the
/// reading is still honest, a check that it is)`. Matched on the start of the reason.
type Honest = fn(&Read) -> bool;

fn no_decision(read: &Read) -> bool {
    matches!(read, Read::Turn(view) if matches!(**view, CustomEventView::Rendered { decision: None, .. }))
}

fn neutral_phase(read: &Read) -> bool {
    matches!(read, Read::Tool(step) if step.phase == ToolPhase::Unknown)
}

const TOLERATED: &[(&str, &str, &str, Honest)] = &[
    // A turn card is a record of what happened. It offers no decision and acts on nothing, so the
    // rows it can read are drawn: a producer that broke the shape still did those things.
    (
        TURN_ACTIVITY_EVENT_TYPE,
        "",
        "a turn card is a record and offers no decision: the rows it can read are drawn",
        no_decision,
    ),
    (
        "dev.agentpod.tool.update",
        "no locations",
        "a call that names no path touched none the reader is shown",
        |r| matches!(r, Read::Tool(step) if step.locations.is_empty() && !step.has_detail),
    ),
    (
        "dev.agentpod.tool.update",
        "a status that is empty",
        "a status this build cannot name is drawn as neutral, never guessed",
        neutral_phase,
    ),
    (
        "dev.agentpod.tool.update",
        "a status written as a label",
        "a status this build cannot name is drawn as neutral, never guessed",
        neutral_phase,
    ),
    (
        "dev.agentpod.tool.update",
        "a kind that is not a string",
        "a kind is opaque display text and never switched on: one that is not text is none",
        |r| matches!(r, Read::Tool(step) if step.kind.is_none()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "a kind that is empty",
        "a kind this build cannot name is drawn as a plain error, never guessed",
        |r| matches!(r, Read::TurnError(card) if card.kind == TurnErrorKind::Unknown),
    ),
    (
        crate::voice_transcript::VOICE_TRANSCRIPT_KEY,
        "an empty language",
        "an empty language tag says nothing, so none is shown; the transcript stands",
        |r| matches!(r, Read::Transcript(t) if t.language.is_none()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "a message over 4000 characters",
        "the message is clipped to the contract's bound; the body beside it is whole",
        |r| matches!(r, Read::TurnError(card) if card.message.chars().count() <= MESSAGE_MAX_CHARS + 1),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "a harness over 100 characters",
        "the harness is clipped to the contract's bound",
        |r| matches!(r, Read::TurnError(card) if card.harness.chars().count() <= crate::turn_error::HARNESS_MAX_CHARS + 1),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "an empty harness",
        "the harness is a line of detail; the card is the kind and the message",
        |r| matches!(r, Read::TurnError(card) if card.harness.is_empty()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "a provider over 200 characters",
        "a provider past its bound is not drawn",
        |r| matches!(r, Read::TurnError(card) if card.provider.as_ref().is_none_or(|p| p.chars().count() <= crate::turn_error::NAME_MAX_CHARS + 1)),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "an empty model",
        "an empty model is no model",
        |r| matches!(r, Read::TurnError(card) if card.model.is_none()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "retryable that is not a boolean",
        "a retry hint that is not a boolean is no hint",
        |r| matches!(r, Read::TurnError(card) if card.retryable.is_none()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "17 attempts",
        "the chain is cut at the contract's sixteen",
        |r| matches!(r, Read::TurnError(card) if card.attempts.iter().map(|a| a.count).sum::<u32>() <= 16),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "attempts that is not a list",
        "the chain is detail: one that is not a list is none, and the card is the headline",
        |r| matches!(r, Read::TurnError(card) if card.attempts.is_empty()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "an attempt with no ",
        "an attempt that cannot be read is skipped; the rest of the chain stands",
        |r| matches!(r, Read::TurnError(card) if card.attempts.is_empty()),
    ),
    (
        crate::turn_error::TURN_ERROR_KEY,
        "an attempt message over 2000 characters",
        "the attempt's words are clipped to the contract's bound",
        |r| matches!(r, Read::TurnError(card) if card.attempts.iter().all(|a| a.message.chars().count() <= crate::turn_error::ATTEMPT_MESSAGE_MAX_CHARS + 1)),
    ),
];

fn tolerance(
    event_type: &str,
    why: &str,
) -> Option<&'static (&'static str, &'static str, &'static str, Honest)> {
    TOLERATED
        .iter()
        .find(|(t, prefix, _, _)| *t == event_type && why.starts_with(prefix))
}

// ---- the tests

#[test]
fn every_schema_in_the_cases_is_accounted_for() {
    let matrix: BTreeSet<String> = schemas(MATRIX)
        .keys()
        .filter(|t| ACTIVITY.contains(&t.as_str()))
        .cloned()
        .collect();
    let read: BTreeSet<String> = READ.iter().map(|(t, _)| t.to_string()).collect();
    assert_eq!(
        matrix, read,
        "every chat activity shape treaty has cases for is read"
    );
    assert_eq!(
        read,
        ACTIVITY.iter().map(|t| t.to_string()).collect(),
        "every activity shape the board-room test leaves here has a reader"
    );

    let live: BTreeSet<String> = schemas(LIVE_ACTIVITY).keys().cloned().collect();
    let here: BTreeSet<String> = LIVE_NOT_READ.iter().map(|(s, _)| s.to_string()).collect();
    assert_eq!(live, here, "every Live Activity schema is accounted for");
}

#[test]
fn every_valid_case_is_read() {
    let mut failed = Vec::new();
    for (event_type, read) in READ {
        let cases = valid(event_type);
        assert!(!cases.is_empty(), "{event_type} has valid cases");
        for (label, content) in cases {
            if read(&content).is_none() {
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

#[test]
fn no_case_the_schema_refuses_is_read_unless_its_reading_is_honest() {
    let mut failed = Vec::new();
    let mut used = BTreeSet::new();
    for (event_type, reader) in READ {
        for (why, content) in invalid(event_type) {
            let Some(read) = reader(&content) else {
                continue;
            };
            match tolerance(event_type, &why) {
                Some(entry @ (_, prefix, _, honest)) => {
                    used.insert((entry.0, *prefix));
                    if !honest(&read) {
                        failed.push(format!("{event_type}: {why} — read, and not as excused"));
                    }
                }
                None => failed.push(format!("{event_type}: {why}")),
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

/// Review focus 3 of treaty's phase 2: a newer card is drawn, never dropped.
#[test]
fn a_newer_turn_with_a_status_this_build_was_not_taught_is_drawn() {
    let (_, content) = valid(TURN_ACTIVITY_EVENT_TYPE)
        .into_iter()
        .find(|(_, c)| c["schema_version"].as_u64() > Some(1))
        .expect("treaty has a newer turn case");
    let Some(Read::Turn(view)) = turn(&content) else {
        panic!("a newer turn was dropped");
    };
    let CustomEventView::Rendered {
        fields,
        newer_version,
        ..
    } = *view
    else {
        unreachable!("only a rendered card is read");
    };
    assert!(newer_version, "drawn, and flagged as newer");
    let status = content["tools"][0]["status"].as_str().unwrap();
    assert!(
        fields.iter().any(|f| f.label == status),
        "the unknown status {status} is drawn as it came: {fields:?}"
    );
}

#[test]
fn a_newer_error_with_a_kind_this_build_was_not_taught_is_drawn() {
    let read: Vec<_> = valid(crate::turn_error::TURN_ERROR_KEY)
        .into_iter()
        .filter(|(_, c)| c["schema_version"].as_u64() > Some(1))
        .map(|(_, c)| parse_turn_error(&c).expect("a newer error card is drawn"))
        .collect();
    assert!(!read.is_empty());
    assert!(read.iter().all(|card| card.kind == TurnErrorKind::Unknown));
}

#[test]
fn a_tool_update_with_a_status_this_build_was_not_taught_is_neutral() {
    let (_, content) = valid("dev.agentpod.tool.update")
        .into_iter()
        .find(|(label, _)| label.contains("a status it does not know"))
        .expect("treaty has the case");
    let Some(Read::Tool(step)) = tool_update(&content) else {
        panic!("dropped");
    };
    assert_eq!(step.phase, ToolPhase::Unknown);
}

// ---- the Live Activity requests this client builds

/// Labelled bodies: a valid case's label, or an invalid case's reason.
type Cases = Vec<(String, Value)>;

/// `operations.<operation>.request`'s valid cases by label, and invalid as `(why, body)`.
fn requests(operation: &str) -> (Cases, Cases) {
    let corpus: Value = serde_json::from_str(LIVE_ACTIVITY_HTTP).unwrap();
    let request = &corpus["operations"][operation]["request"];
    let valid = request["valid"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(l, b)| (l.clone(), b.clone()))
        .collect();
    let invalid = request["invalid"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| (c["why"].as_str().unwrap().to_string(), c["body"].clone()))
        .collect();
    (valid, invalid)
}

fn kind_of(body: &Value) -> Option<LiveActivityTokenKind> {
    match body.get("kind")?.as_str()? {
        "start" => Some(LiveActivityTokenKind::Start),
        "update" => Some(LiveActivityTokenKind::Update),
        _ => None,
    }
}

fn text<'a>(body: &'a Value, key: &str) -> Option<&'a str> {
    body.get(key).and_then(Value::as_str)
}

/// What the builder makes of what a host could hand it for `body`: ActivityKit's token, the
/// signing profile's environment, the session's device and the activity. `None` when no host
/// could express the body at all (a kind or environment the type has no value for).
fn register_from(body: &Value) -> Option<Option<Value>> {
    let sandbox = match text(body, "environment")? {
        "sandbox" => true,
        "production" => false,
        _ => return None,
    };
    let token = LiveActivityToken {
        kind: kind_of(body)?,
        token: text(body, "token").unwrap_or_default().to_string(),
        sandbox,
        activity_id: text(body, "activity_id").map(str::to_string),
    };
    Some(register_body(
        &token,
        text(body, "device_id").unwrap_or_default(),
    ))
}

fn delete_from(body: &Value) -> Option<Option<Value>> {
    Some(delete_body(
        kind_of(body)?,
        text(body, "device_id").unwrap_or_default(),
        text(body, "activity_id"),
    ))
}

/// `body` without the keys the hub drops, which no builder writes.
fn known(body: &Value, keys: &[&str]) -> Value {
    let mut map = body.as_object().cloned().unwrap_or_default();
    map.retain(|k, _| keys.contains(&k.as_str()));
    Value::Object(map)
}

#[test]
fn every_valid_token_request_is_one_this_client_builds_and_no_invalid_one_ever_is() {
    let registration = ["kind", "token", "environment", "device_id", "activity_id"];
    let removal = ["kind", "device_id", "activity_id"];
    type Build = fn(&Value) -> Option<Option<Value>>;
    let operations: [(&str, Build, &[&str]); 2] = [
        ("registerLiveActivityToken", register_from, &registration),
        ("removeLiveActivityToken", delete_from, &removal),
    ];
    let mut failed = Vec::new();
    for (operation, build, keys) in operations {
        let (valid, invalid) = requests(operation);
        assert!(
            !valid.is_empty() && !invalid.is_empty(),
            "{operation} has cases"
        );
        for (label, body) in valid {
            if build(&body).flatten() != Some(known(&body, keys)) {
                failed.push(format!("{operation}: \"{label}\" is not built"));
            }
        }
        for (why, body) in invalid {
            if build(&body).flatten().is_some_and(|built| built == body) {
                failed.push(format!("{operation}: \"{why}\" is built"));
            }
        }
    }
    assert!(failed.is_empty(), "{}", failed.join("\n"));
}
