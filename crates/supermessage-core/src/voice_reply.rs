//! The voice reply: an agent's answer, spoken, drawn as one message.
//!
//! When a reply to a voice note is spoken back, the AgentPod hub posts the
//! agent's ordinary **text** reply first and then, seconds later, an MSC3245
//! voice message of the same words, sent as the agent. The voice message's
//! `content` carries [`VOICE_REPLY_KEY`], naming the text it speaks:
//!
//! ```json
//! "dev.agentpod.voice_reply": {
//!   "schema_version": 1,
//!   "text_event_id": "$text…",
//!   "voice": "bf_emma",
//!   "seconds": 4
//! }
//! ```
//!
//! It is deliberately **not** an `m.in_reply_to`: a client that does not know
//! the key would quote the whole answer a second time above the note. Such a
//! client shows two messages — the text and a voice note — and that is the
//! plain-text fallback. This client folds them into one.
//!
//! ## The pairing rule
//!
//! [`reconcile`] runs over the materialised timeline in the core, after every
//! batch, so every host inherits one answer (the same seam as
//! `crate::embedded::reconcile`):
//!
//! - **Both loaded** — the voice message's key names a text or notice message
//!   in the timeline, from **the same sender**: the text row is drawn as its
//!   ordinary bubble with the voice note's player on it
//!   (`ItemView::Bubble { voice: Some(..) }`, player first, then the text),
//!   and the voice row is hidden (`ItemView::None`). The text row is the
//!   primary one: it arrives first, it is what every other client shows, and
//!   reactions, replies, edits and "copy" all address it.
//! - **Only the voice loaded** — the text is further back than pagination
//!   has reached, or never arrived: the voice row is drawn standalone, as the
//!   voice note it is. When the text arrives, the two fold together.
//! - **Either side goes** — a redacted voice message leaves the text as a
//!   plain bubble; a redacted text leaves the voice note standalone.
//! - **An edit** of the text (`m.replace`) is folded into the text row by the
//!   SDK and keeps the pairing; the player shows beside the edited words.
//! - **One voice per text.** If two voice messages name the same text, the
//!   earlier one pairs and the other stays standalone.
//!
//! A text row the projection drew as something else — a turn error card, a
//! transcript, a suite decision — is never paired: those were decided from
//! the raw event, and a voice note does not outrank them.
//!
//! ## Untrusted input, read strictly
//!
//! Anyone who can send to a room can put this key on a message, so the pairing
//! requires the voice message and the text to share a sender: nobody can fold
//! a message of someone else's into their own audio. And the payload is read
//! as strictly as `crate::voice_transcript` reads its own: `schema_version`
//! exactly `1`, and every field within the contract (agentpod
//! `packages/contract/src/matrix-events.ts`, `VoiceReply`):
//!
//! | Field | Contract |
//! |---|---|
//! | `text_event_id` | Required string, 1..=[`TEXT_EVENT_ID_MAX_CHARS`]. |
//! | `voice` | Required string, 1..=[`VOICE_MAX_CHARS`]. |
//! | `seconds` | Optional integer, `0..=`[`SECONDS_MAX`]. |
//!
//! Unknown fields are ignored. A payload that breaks any of it is not a voice
//! reply: the message is the ordinary voice note it would have been without
//! the key.
//!
//! ## Cost
//!
//! Three linear passes per batch, each doing constant work per row: the voice
//! rows are collected, the text rows they name are found through a hash map
//! keyed by event id, and only the rows whose drawing changes are rewritten.
//! Nothing is a scan per row — a room of twenty thousand rows costs the same
//! per row as a room of twenty.

use std::collections::HashMap;

use serde_json::Value;

use crate::dto::TimelineRow;
use crate::item_view::ItemView;

/// The `content` key the AgentPod hub writes the voice reply under.
pub const VOICE_REPLY_KEY: &str = "dev.agentpod.voice_reply";

/// The only `schema_version` this parser reads.
pub const SCHEMA_VERSION: u64 = 1;

/// `text_event_id`, in code points. Matches the contract.
pub const TEXT_EVENT_ID_MAX_CHARS: usize = 255;
/// `voice`, in code points. Matches the contract.
pub const VOICE_MAX_CHARS: usize = 64;
/// `seconds`: an hour. Matches the contract.
pub const SECONDS_MAX: u64 = 3_600;

/// What a voice message says it speaks: the text message it is the voice of.
///
/// Carried on the voice message's own row ([`TimelineRow::voice_reply`]) so
/// the pairing survives the row being hidden and redrawn; a host has no need
/// to read it — the paired bubble already carries everything it draws.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct VoiceReplyLink {
    /// The agent's text message this voice note speaks.
    pub text_event_id: String,
    /// The voice it was spoken in, as the speech service names it.
    pub voice: String,
    /// The audio's length in whole seconds, when the hub said.
    pub seconds: Option<u32>,
}

/// The voice note drawn on a text message it speaks: the player, and the
/// event the player plays.
///
/// `event_id` is the **voice message's**, not the row's: it is what a host
/// fetches the audio by (`audio_source`, `media_fetch`). Everything else a
/// host does with the bubble — react, reply, edit, copy — addresses the row's
/// own item, the text.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct VoiceReplyPlayer {
    pub event_id: String,
    /// The note, as `ItemView::Audio` would draw it, titled and read as a
    /// reply: `"Voice reply"`, `"Voice reply, 4 seconds"`.
    pub audio: crate::audio::AudioView,
}

/// Parse `content[VOICE_REPLY_KEY]` — the value under the key, not the whole
/// `content` — or `None` when it is not a voice reply.
///
/// `None` means "draw the ordinary voice note", never "draw nothing".
pub fn parse_voice_reply(value: &Value) -> Option<VoiceReplyLink> {
    let object = value.as_object()?;

    // Exactly 1: `as_u64` also refuses `1.5`, `"1"` and a negative.
    if object.get("schema_version")?.as_u64()? != SCHEMA_VERSION {
        return None;
    }

    let text_event_id = bounded_string(object.get("text_event_id")?, TEXT_EVENT_ID_MAX_CHARS)?;
    let voice = bounded_string(object.get("voice")?, VOICE_MAX_CHARS)?;

    let seconds = match object.get("seconds") {
        None | Some(Value::Null) => None,
        Some(value) => {
            let seconds = value.as_u64().filter(|s| *s <= SECONDS_MAX)?;
            Some(u32::try_from(seconds).ok()?)
        }
    };

    Some(VoiceReplyLink {
        text_event_id,
        voice,
        seconds,
    })
}

/// A required string of `1..=max` code points, taken as sent — an event id
/// is compared, not displayed, so it is not trimmed.
fn bounded_string(value: &Value, max: usize) -> Option<String> {
    let s = value.as_str()?;
    let count = s.chars().count();
    (1..=max).contains(&count).then(|| s.to_string())
}

/// Whether `raw_json` could carry the key at all — a text search, so the
/// message that does not (nearly all of them) never pays for a parse.
pub fn may_carry_voice_reply(raw_json: &str) -> bool {
    raw_json.contains(VOICE_REPLY_KEY)
}

/// The voice message a row is, when it is one that names its text: a remote
/// `m.audio` carrying a valid key.
fn voice_side(row: &TimelineRow) -> Option<(&str, &str)> {
    let link = row.voice_reply.as_ref()?;
    let item = &row.item;
    if item.kind != "message" || item.msgtype.as_deref() != Some("m.audio") {
        return None;
    }
    item.event_id.as_deref()?;
    Some((link.text_event_id.as_str(), item.sender.as_deref()?))
}

/// Whether a row is a text a voice note may be drawn on: a plain text or
/// notice message, drawn as the projection draws a plain one.
fn is_pairable_text(row: &TimelineRow) -> bool {
    let item = &row.item;
    item.kind == "message"
        && matches!(item.msgtype.as_deref(), Some("m.text") | Some("m.notice"))
        // A suite decision riding on the prose is drawn as its card.
        && item.detail.is_none()
        && item.custom_payload.is_none()
        && matches!(
            row.view,
            ItemView::Bubble { .. }
                | ItemView::System {
                    kind: crate::item_view::SystemKind::Notice { .. },
                    ..
                }
        )
}

/// Settle which voice replies are folded into their text, in place,
/// returning the indices of the rows whose view changed. See the module doc
/// for the rule.
///
/// Settled: a second pass over the same rows changes nothing, so a batch that
/// touches neither half of a pair emits no `Set` for it.
pub fn reconcile(rows: &mut [TimelineRow]) -> Vec<usize> {
    // Pass 1: the voice messages naming a text, earliest first, and whether
    // any text is drawn with a voice now (which may have to come off).
    let mut wanted: HashMap<&str, usize> = HashMap::new();
    let mut any_paired = false;
    for (index, row) in rows.iter().enumerate() {
        if let Some((text_event_id, _)) = voice_side(row) {
            wanted.entry(text_event_id).or_insert(index);
        }
        any_paired |= matches!(row.view, ItemView::Bubble { voice: Some(_), .. });
    }
    if wanted.is_empty() && !any_paired {
        return Vec::new();
    }

    // Pass 2: the texts those name, found by event id — one hash lookup per
    // row, never a search.
    let mut text_to_voice: HashMap<usize, usize> = HashMap::new();
    let mut voice_paired = vec![false; rows.len()];
    if !wanted.is_empty() {
        for (index, row) in rows.iter().enumerate() {
            let Some(event_id) = row.item.event_id.as_deref() else {
                continue;
            };
            let Some(&voice_index) = wanted.get(event_id) else {
                continue;
            };
            let Some((_, voice_sender)) = voice_side(&rows[voice_index]) else {
                continue;
            };
            if row.item.sender.as_deref() == Some(voice_sender) && is_pairable_text(row) {
                text_to_voice.insert(index, voice_index);
                voice_paired[voice_index] = true;
            }
        }
    }

    // Pass 3: what each row involved should be drawn as. A row already drawn
    // the way it should be is left alone without recomputing its view: any
    // change to an item arrives as a freshly projected row, which is never
    // drawn paired, so "paired with this voice already" means "current".
    let mut updates: Vec<(usize, ItemView)> = Vec::new();
    for (index, row) in rows.iter().enumerate() {
        let wanted_view = if voice_side(row).is_some() {
            match (voice_paired[index], &row.view) {
                (true, ItemView::None) => continue,
                (true, _) => ItemView::None,
                (false, ItemView::None) => crate::item_view::view_for(&row.item),
                (false, _) => continue,
            }
        } else if let Some(&voice_index) = text_to_voice.get(&index) {
            let voice = &rows[voice_index];
            let already = match &row.view {
                ItemView::Bubble {
                    voice: Some(player),
                    ..
                } => voice.item.event_id.as_deref() == Some(player.event_id.as_str()),
                _ => false,
            };
            if already {
                continue;
            }
            match crate::item_view::voice_reply_view(&row.item, &voice.item) {
                Some(view) => view,
                None => continue,
            }
        } else if matches!(row.view, ItemView::Bubble { voice: Some(_), .. }) {
            // Paired before, not now: its voice was redacted, paginated out,
            // or stopped naming it.
            crate::item_view::view_for(&row.item)
        } else {
            continue;
        };
        if row.view != wanted_view {
            updates.push((index, wanted_view));
        }
    }

    updates
        .into_iter()
        .map(|(index, view)| {
            rows[index].view = view;
            index
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::{AudioMetaDto, MediaMetaDto, TimelineItemDto};
    use serde_json::json;

    const AGENT: &str = "@agent_scribe:hs";

    fn hub() -> Value {
        json!({
            "schema_version": 1,
            "text_event_id": "$text",
            "voice": "bf_emma",
            "seconds": 4,
        })
    }

    // --- parsing ---------------------------------------------------------

    #[test]
    fn the_hubs_payload_reads_whole() {
        assert_eq!(
            parse_voice_reply(&hub()),
            Some(VoiceReplyLink {
                text_event_id: "$text".into(),
                voice: "bf_emma".into(),
                seconds: Some(4),
            })
        );
    }

    #[test]
    fn seconds_is_optional_and_null_is_absent() {
        let mut payload = hub();
        payload.as_object_mut().unwrap().remove("seconds");
        assert_eq!(parse_voice_reply(&payload).unwrap().seconds, None);
        payload["seconds"] = Value::Null;
        assert_eq!(parse_voice_reply(&payload).unwrap().seconds, None);
    }

    #[test]
    fn the_bounds_are_inclusive() {
        let mut payload = hub();
        // Two bytes a character, so a byte count would refuse these.
        payload["text_event_id"] = json!("é".repeat(TEXT_EVENT_ID_MAX_CHARS));
        payload["voice"] = json!("é".repeat(VOICE_MAX_CHARS));
        payload["seconds"] = json!(SECONDS_MAX);
        assert!(parse_voice_reply(&payload).is_some());
        payload["seconds"] = json!(0);
        payload["text_event_id"] = json!("$");
        payload["voice"] = json!("x");
        assert!(parse_voice_reply(&payload).is_some());
    }

    #[test]
    fn a_blended_voice_is_a_voice() {
        let mut payload = hub();
        payload["voice"] = json!("af_heart:60+af_bella:40");
        assert_eq!(
            parse_voice_reply(&payload).unwrap().voice,
            "af_heart:60+af_bella:40"
        );
    }

    #[test]
    fn anything_outside_the_contract_is_an_ordinary_voice_note() {
        let base = hub();
        let mut cases: Vec<(String, Value)> = vec![
            ("a string".into(), json!("$text")),
            ("an array".into(), json!([base.clone()])),
            ("null".into(), Value::Null),
        ];
        for (field, replacement) in [
            ("schema_version", Value::Null),
            ("schema_version", json!("1")),
            ("schema_version", json!(0)),
            ("schema_version", json!(2)),
            ("schema_version", json!(1.5)),
            ("text_event_id", Value::Null),
            ("text_event_id", json!(7)),
            ("text_event_id", json!("")),
            (
                "text_event_id",
                json!("x".repeat(TEXT_EVENT_ID_MAX_CHARS + 1)),
            ),
            ("voice", Value::Null),
            ("voice", json!(["bf_emma"])),
            ("voice", json!("")),
            ("voice", json!("x".repeat(VOICE_MAX_CHARS + 1))),
            ("seconds", json!(-1)),
            ("seconds", json!(3_601)),
            ("seconds", json!(4.5)),
            ("seconds", json!("4")),
        ] {
            let mut payload = base.clone();
            if replacement.is_null() {
                payload.as_object_mut().unwrap().remove(field);
            } else {
                payload[field] = replacement.clone();
            }
            cases.push((format!("{field} = {replacement}"), payload));
        }
        for (what, payload) in cases {
            assert_eq!(parse_voice_reply(&payload), None, "{what}: {payload}");
        }
    }

    #[test]
    fn unknown_fields_in_version_one_are_ignored() {
        let mut payload = hub();
        payload["speed"] = json!(1.1);
        assert!(parse_voice_reply(&payload).is_some());
    }

    #[test]
    fn the_text_search_finds_the_key() {
        assert!(may_carry_voice_reply(
            r#"{"content":{"dev.agentpod.voice_reply":{}}}"#
        ));
        assert!(!may_carry_voice_reply(
            r#"{"content":{"dev.agentpod.voice_transcript":{}}}"#
        ));
    }

    // --- pairing ---------------------------------------------------------

    fn item(id: &str, sender: &str, msgtype: &str, body: &str) -> TimelineItemDto {
        crate::timeline::project_item_parts(
            id,
            Some(&format!("${id}")),
            "message",
            Some(msgtype),
            None,
            Some(sender),
            Some("Scribe"),
            None,
            false,
            Some(body),
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

    fn text(id: &str) -> TimelineRow {
        TimelineRow::new(item(id, AGENT, "m.text", "It is **four** o'clock."))
    }

    fn voice_by(id: &str, sender: &str, speaks: &str) -> TimelineRow {
        let mut it = item(id, sender, "m.audio", "Voice message.ogg");
        it.media = Some(MediaMetaDto {
            filename: "Voice message.ogg".into(),
            mimetype: Some("audio/ogg".into()),
            size: Some(48_213),
            width: None,
            height: None,
            audio: Some(AudioMetaDto {
                is_voice: true,
                duration_ms: Some(4_210),
                waveform: Some(vec![0.1, 0.5, 0.9]),
            }),
        });
        let mut row = TimelineRow::new(it);
        row.voice_reply = Some(VoiceReplyLink {
            text_event_id: format!("${speaks}"),
            voice: "bf_emma".into(),
            seconds: Some(4),
        });
        row
    }

    fn voice(id: &str, speaks: &str) -> TimelineRow {
        voice_by(id, AGENT, speaks)
    }

    fn player(row: &TimelineRow) -> Option<&VoiceReplyPlayer> {
        match &row.view {
            ItemView::Bubble { voice, .. } => voice.as_ref(),
            _ => None,
        }
    }

    fn is_plain_bubble(row: &TimelineRow) -> bool {
        matches!(row.view, ItemView::Bubble { voice: None, .. })
    }

    fn is_standalone_voice(row: &TimelineRow) -> bool {
        matches!(row.view, ItemView::Audio { .. })
    }

    #[test]
    fn text_then_voice_is_one_bubble_with_the_player_on_the_text() {
        let mut rows = vec![text("t"), voice("v", "t")];
        let changed = reconcile(&mut rows);
        assert_eq!(changed, vec![0, 1]);

        let player = player(&rows[0]).expect("the text carries the voice");
        assert_eq!(player.event_id, "$v", "the player plays the voice event");
        assert_eq!(player.audio.title, "Voice reply");
        assert_eq!(player.audio.accessibility_label, "Voice reply, 4 seconds");
        assert_eq!(player.audio.length_label.as_deref(), Some("0:04"));
        assert_eq!(rows[1].view, ItemView::None, "the voice row is hidden");
        // The bubble is still the text, parsed as the text always is.
        let ItemView::Bubble { blocks, muted, .. } = &rows[0].view else {
            unreachable!()
        };
        assert!(!muted);
        assert_eq!(
            blocks,
            &match crate::item_view::view_for(&rows[0].item) {
                ItemView::Bubble { blocks, .. } => blocks,
                other => panic!("{other:?}"),
            }
        );

        assert!(reconcile(&mut rows).is_empty(), "settled");
    }

    #[test]
    fn voice_then_text_pairs_the_same_way() {
        let mut rows = vec![voice("v", "t")];
        assert!(
            reconcile(&mut rows).is_empty(),
            "alone, it stays a voice note"
        );
        assert!(is_standalone_voice(&rows[0]));

        rows.push(text("t"));
        assert_eq!(reconcile(&mut rows), vec![0, 1]);
        assert_eq!(rows[0].view, ItemView::None);
        assert_eq!(player(&rows[1]).unwrap().event_id, "$v");
    }

    #[test]
    fn a_voice_whose_text_is_not_loaded_is_a_voice_note_until_it_is() {
        // Back-pagination reached the voice but not the text before it.
        let mut rows = vec![voice("v", "t"), text("later")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_standalone_voice(&rows[0]));

        // The next page brings the text in at the front.
        rows.insert(0, text("t"));
        assert_eq!(reconcile(&mut rows), vec![0, 1]);
        assert!(player(&rows[0]).is_some());
        assert_eq!(rows[1].view, ItemView::None);
        assert!(is_plain_bubble(&rows[2]));
    }

    #[test]
    fn the_text_paginated_out_brings_the_voice_back() {
        let mut rows = vec![text("t"), voice("v", "t")];
        reconcile(&mut rows);
        rows.remove(0);
        assert_eq!(reconcile(&mut rows), vec![0]);
        assert!(is_standalone_voice(&rows[0]));
    }

    #[test]
    fn a_redacted_voice_leaves_the_plain_text() {
        let mut rows = vec![text("t"), voice("v", "t")];
        reconcile(&mut rows);
        // The SDK's `Set` for a redaction: the row is no longer a message and
        // carries no key (a redaction strips `content`).
        let mut gone = rows[1].item.clone();
        gone.kind = "redacted".into();
        gone.msgtype = None;
        rows[1] = TimelineRow::new(gone);

        assert_eq!(reconcile(&mut rows), vec![0]);
        assert!(is_plain_bubble(&rows[0]));
        assert!(matches!(
            rows[1].view,
            ItemView::Placeholder {
                kind: crate::item_view::PlaceholderKind::Redacted,
                ..
            }
        ));
    }

    #[test]
    fn a_redacted_text_leaves_the_voice_note_standalone() {
        let mut rows = vec![text("t"), voice("v", "t")];
        reconcile(&mut rows);
        let mut gone = rows[0].item.clone();
        gone.kind = "redacted".into();
        gone.msgtype = None;
        rows[0] = TimelineRow::new(gone);

        assert_eq!(reconcile(&mut rows), vec![1]);
        assert!(is_standalone_voice(&rows[1]));
    }

    #[test]
    fn an_edited_text_keeps_its_voice_and_shows_the_new_words() {
        let mut rows = vec![text("t"), voice("v", "t")];
        reconcile(&mut rows);
        // The SDK folds the `m.replace` in and re-sends the text row whole,
        // freshly projected: a plain bubble again.
        let mut edited = rows[0].item.clone();
        edited.body = Some("It is five o'clock.".into());
        edited.edited = true;
        rows[0] = TimelineRow::new(edited);
        assert!(is_plain_bubble(&rows[0]));

        assert_eq!(reconcile(&mut rows), vec![0]);
        let ItemView::Bubble { blocks, voice, .. } = &rows[0].view else {
            unreachable!()
        };
        assert!(voice.is_some());
        assert!(format!("{blocks:?}").contains("five"));
        assert_eq!(rows[1].view, ItemView::None);
    }

    #[test]
    fn a_reaction_re_sending_either_row_keeps_the_pair() {
        let mut rows = vec![text("t"), voice("v", "t")];
        reconcile(&mut rows);
        let paired = rows.clone();

        // A reaction on the text, a receipt on the voice: the SDK re-sends
        // both rows freshly projected.
        let mut reacted = rows[0].item.clone();
        reacted.reactions = vec![crate::dto::ReactionDto {
            key: "👍".into(),
            display_key: "👍".into(),
            count: 1,
            by_me: true,
            senders: vec!["@me:hs".into()],
        }];
        rows[0] = TimelineRow::new(reacted);
        let link = rows[1].voice_reply.clone();
        rows[1] = TimelineRow::new(rows[1].item.clone());
        rows[1].voice_reply = link;

        assert_eq!(reconcile(&mut rows), vec![0, 1]);
        assert_eq!(rows[0].view, paired[0].view);
        assert_eq!(rows[1].view, ItemView::None);
        assert_eq!(
            rows[0].item.reactions.len(),
            1,
            "the reaction is the text's"
        );
    }

    #[test]
    fn someone_elses_voice_cannot_take_over_a_message() {
        let mut rows = vec![text("t"), voice_by("v", "@mallory:hs", "t")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_plain_bubble(&rows[0]));
        assert!(is_standalone_voice(&rows[1]));
    }

    #[test]
    fn a_voice_naming_no_loaded_event_changes_nothing_else() {
        let mut rows = vec![text("a"), voice("v", "elsewhere"), text("b")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_standalone_voice(&rows[1]));
    }

    #[test]
    fn the_first_voice_for_a_text_pairs_and_a_second_stays_a_voice_note() {
        let mut rows = vec![text("t"), voice("v1", "t"), voice("v2", "t")];
        reconcile(&mut rows);
        assert_eq!(player(&rows[0]).unwrap().event_id, "$v1");
        assert_eq!(rows[1].view, ItemView::None);
        assert!(is_standalone_voice(&rows[2]));
    }

    #[test]
    fn a_notice_reply_pairs_and_keeps_its_muted_bubble() {
        // A one-line notice is otherwise a system line; with its voice it is
        // a message the reader listens to.
        let mut rows = vec![
            TimelineRow::new(item("t", AGENT, "m.notice", "Done.")),
            voice("v", "t"),
        ];
        assert!(matches!(rows[0].view, ItemView::System { .. }));
        reconcile(&mut rows);
        assert!(matches!(
            rows[0].view,
            ItemView::Bubble {
                muted: true,
                voice: Some(_),
                ..
            }
        ));
        // And it goes back to the system line when the voice goes.
        rows.pop();
        reconcile(&mut rows);
        assert!(matches!(rows[0].view, ItemView::System { .. }));
    }

    #[test]
    fn a_text_drawn_from_its_raw_event_is_never_paired() {
        // A turn error card rides the same kind of message; the card wins.
        let card = crate::turn_error::parse_turn_error(&json!({
            "schema_version": 1,
            "kind": "timeout",
            "message": "The agent did not answer in time.",
            "harness": "claude-code",
        }))
        .expect("a valid card");
        let mut rows = vec![
            TimelineRow::with_turn_error(item("t", AGENT, "m.text", "failed"), Some(card)),
            voice("v", "t"),
        ];
        assert!(reconcile(&mut rows).is_empty());
        assert!(matches!(rows[0].view, ItemView::TurnError { .. }));
        assert!(is_standalone_voice(&rows[1]));
    }

    #[test]
    fn a_text_carrying_a_suite_decision_is_never_paired() {
        let mut decision = item("t", AGENT, "m.text", "Allow it?");
        decision.detail = Some(crate::custom_events::PERMISSION_REQUEST_EVENT_TYPE.into());
        decision.custom_payload = Some(crate::dto::CustomPayload(json!({})));
        let mut rows = vec![TimelineRow::new(decision), voice("v", "t")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_standalone_voice(&rows[1]));
    }

    #[test]
    fn a_timeline_without_voice_replies_is_untouched() {
        let mut rows = vec![text("a"), text("b")];
        let before = rows.clone();
        assert!(reconcile(&mut rows).is_empty());
        assert_eq!(rows, before);
    }

    #[test]
    fn a_long_room_pairs_in_linear_time() {
        // Twenty thousand rows, five thousand of them voice replies. A pass
        // that searched the rows for each voice's text is 5,000 × 20,000 —
        // a hundred million comparisons, seconds even in release — where the
        // indexed one is three passes.
        const PAIRS: usize = 5_000;
        let mut rows = Vec::with_capacity(PAIRS * 4);
        for n in 0..PAIRS {
            rows.push(text(&format!("t{n}")));
            rows.push(text(&format!("chat{n}")));
            rows.push(voice(&format!("v{n}"), &format!("t{n}")));
            rows.push(text(&format!("more{n}")));
        }
        let started = std::time::Instant::now();
        let changed = reconcile(&mut rows);
        let first = started.elapsed();
        assert_eq!(changed.len(), PAIRS * 2);

        let started = std::time::Instant::now();
        assert!(reconcile(&mut rows).is_empty());
        let settled = started.elapsed();

        // Generous for a debug build on a loaded CI runner; the quadratic
        // version takes well over ten times this.
        let budget = std::time::Duration::from_millis(1_500);
        assert!(first < budget, "first pass took {first:?}");
        assert!(settled < budget, "settled pass took {settled:?}");
    }
}
