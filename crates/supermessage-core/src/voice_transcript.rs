//! The voice transcript: what a voice note said, drawn under the note.
//!
//! When the AgentPod hub has transcribed a voice note, it posts an
//! `m.notice` that **replies** to the note (`m.relates_to.m.in_reply_to`).
//! Its `body` is the fallback every client can show — `Transcript: <text>` —
//! and its `content` also carries [`VOICE_TRANSCRIPT_KEY`], holding the same
//! transcript as structured data: the text, and optionally the language the
//! transcriber detected and the note's length in whole seconds.
//!
//! This module turns that key into a [`VoiceNoteTranscript`] a host can draw
//! without deciding anything: the caption ("Transcript · hi · 0:42") and the
//! accessibility sentence are composed here, so iOS, Android and the desktop
//! say the same thing about the same note.
//!
//! ## Untrusted input, read strictly
//!
//! Anyone who can send to a room can put this key on a message. Unlike the
//! turn error card, which reads a newer version best-effort, this parser is
//! **strict**: `schema_version` must be exactly `1`, and every field must be
//! what the contract says it is —
//!
//! | Field | Contract |
//! |---|---|
//! | `text` | Required string, non-empty once trimmed, at most [`TEXT_MAX_CHARS`]. |
//! | `language` | Optional string, at most [`LANGUAGE_MAX_CHARS`]: ASCII letters, digits, `-`, `_` and spaces. |
//! | `seconds` | Optional integer, `0..=`[`SECONDS_MAX`]. |
//!
//! A payload that breaks any of it is not this transcript, and
//! [`parse_voice_transcript`] returns `None`. `null` counts as absent for the
//! two optional fields. Strictness costs nothing a reader needs: the notice's
//! `body` already says the whole transcript, and a `None` here means the
//! notice is drawn exactly as it would be without the key.
//!
//! Nothing read out of the payload becomes markup, a link, an image source or
//! a style on any host.
//!
//! ## Under its note, wherever it lands
//!
//! The transcript arrives seconds after the note, and anything can land in
//! between — the sender's own "Hi?" a second later, an agent's reply, the
//! unread divider. Drawn where it landed, it read as a reply to whatever was
//! just above it. So [`reconcile`] runs over the materialised timeline in the
//! core, after every batch (the seam `crate::voice_reply::reconcile` uses),
//! and every host inherits one answer:
//!
//! - **Both loaded** — the notice replies (`m.in_reply_to`) to a voice
//!   message drawn as a note (`ItemView::Audio`): the note's row carries the
//!   transcript (`ItemView::Audio { transcript: Some(..) }`, drawn directly
//!   under the note, in the same row) and the notice's row is hidden
//!   (`ItemView::None`). Both are rewritten in place as `Set`s — no row is
//!   inserted or moved, so nothing scrolls.
//! - **Only the notice loaded** — the note is further back than pagination
//!   has reached: the transcript is drawn standalone, as before
//!   (`ItemView::VoiceTranscript`), and folds in when the note arrives.
//! - **Either side goes** — a redacted notice takes the transcript off the
//!   note; a redacted (or paginated-out) note leaves the transcript standalone.
//!   An edit re-projects the row and the fold is settled again, with the new
//!   words.
//! - **One transcript per note.** A second notice naming the same note (a
//!   re-transcription, or someone else's) stays standalone, so it is seen
//!   for what it is rather than silently replacing the first.
//!
//! A note drawn as something else — an agent's spoken answer folded into its
//! text (`crate::voice_reply`) — keeps its transcript standalone: the text is
//! already the words.
//!
//! The cost is that module's: three linear passes, one hash lookup per row.

use std::collections::HashMap;

use serde_json::Value;

use crate::dto::TimelineRow;
use crate::item_view::ItemView;

/// The `content` key the AgentPod hub writes the structured transcript under.
pub const VOICE_TRANSCRIPT_KEY: &str = "dev.agentpod.voice_transcript";

/// The only `schema_version` this parser reads.
pub const SCHEMA_VERSION: u64 = 1;

/// `text`, in code points. Matches the contract.
pub const TEXT_MAX_CHARS: usize = 20_000;
/// `language`, in code points. Matches the contract.
pub const LANGUAGE_MAX_CHARS: usize = 16;
/// `seconds`: an hour. Matches the contract.
pub const SECONDS_MAX: u64 = 3_600;

/// A voice note's transcript, ready to draw.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct VoiceNoteTranscript {
    /// What was said, trimmed. Never empty. Selectable text, drawn as-is.
    pub text: String,
    /// The language the transcriber detected — `"en"`, `"hi"` — as the hub
    /// wrote it. `None` when it did not say.
    pub language: Option<String>,
    /// The note's length in whole seconds, when the hub said.
    pub seconds: Option<u32>,
    /// `seconds` as `m:ss` — `"0:42"`, `"12:05"`. `None` with `seconds`.
    pub duration: Option<String>,
    /// The block's small heading: `"Transcript"`, then ` · language` and
    /// ` · duration` for whichever the hub gave — `"Transcript · hi · 0:42"`.
    pub caption: String,
    /// What a screen reader says for the whole block — `"Transcript of voice
    /// note: …"` with the text.
    pub accessibility_label: String,
}

/// Parse `content[VOICE_TRANSCRIPT_KEY]` — the value under the key, not the
/// whole `content` — into a transcript, or `None` when it is not one.
///
/// `None` means "draw the ordinary notice", never "draw nothing". See the
/// module doc for what counts as malformed.
pub fn parse_voice_transcript(value: &Value) -> Option<VoiceNoteTranscript> {
    let object = value.as_object()?;

    // Exactly 1: `as_u64` also refuses `1.5`, `"1"` and a negative.
    if object.get("schema_version")?.as_u64()? != SCHEMA_VERSION {
        return None;
    }

    let text = object.get("text")?.as_str()?.trim();
    if text.is_empty() || text.chars().count() > TEXT_MAX_CHARS {
        return None;
    }

    let language = match object.get("language") {
        None | Some(Value::Null) => None,
        Some(value) => {
            let language = value.as_str()?.trim();
            if language.chars().count() > LANGUAGE_MAX_CHARS
                || !language.chars().all(is_language_char)
            {
                return None;
            }
            // An empty tag says nothing; it is not a reason to refuse.
            (!language.is_empty()).then(|| language.to_string())
        }
    };

    let seconds = match object.get("seconds") {
        None | Some(Value::Null) => None,
        Some(value) => {
            let seconds = value.as_u64().filter(|s| *s <= SECONDS_MAX)?;
            Some(u32::try_from(seconds).ok()?)
        }
    };

    let duration = seconds.map(minutes_and_seconds);
    let caption = std::iter::once("Transcript")
        .chain(language.as_deref())
        .chain(duration.as_deref())
        .collect::<Vec<_>>()
        .join(" · ");

    Some(VoiceNoteTranscript {
        text: text.to_string(),
        accessibility_label: format!("Transcript of voice note: {text}"),
        language,
        seconds,
        duration,
        caption,
    })
}

/// What a language tag is made of: `en`, `pt-BR`, `zh_Hant`, or a name a
/// transcriber spelled out (`english`). ASCII only, so a bidi override or a
/// control character cannot ride in on the caption.
fn is_language_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | ' ')
}

/// `m:ss`, minutes unpadded and uncapped: 42 is `0:42`, 725 is `12:05`, an
/// hour is `60:00`.
fn minutes_and_seconds(seconds: u32) -> String {
    format!("{}:{:02}", seconds / 60, seconds % 60)
}

/// A transcript notice, as its own row remembers it: the note it replies to
/// and how it is drawn when that note is not there to carry it.
///
/// Carried on the notice's row ([`TimelineRow::voice_transcript`]) because
/// the fold is re-settled after every batch and must survive the row being
/// hidden; the raw event the transcript was read from is gone by then.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TranscriptNotice {
    /// The voice message the notice replies to (`m.in_reply_to`). `None`
    /// for a notice that replies to nothing, which is never folded.
    pub note_event_id: Option<String>,
    pub transcript: VoiceNoteTranscript,
    /// The standalone view's side — see `ItemView::VoiceTranscript`.
    pub on_own_note: bool,
}

/// The note a row's transcript belongs to, when the row is a transcript
/// notice that replies to one.
fn transcript_side(row: &TimelineRow) -> Option<&str> {
    if row.item.kind != "message" {
        return None;
    }
    row.voice_transcript.as_ref()?.note_event_id.as_deref()
}

/// Whether a row is a voice message drawn as a note, which a transcript may
/// be drawn under.
fn is_transcribable_note(row: &TimelineRow) -> bool {
    row.item.kind == "message"
        && row.item.msgtype.as_deref() == Some("m.audio")
        && row.item.event_id.is_some()
        && matches!(row.view, ItemView::Audio { .. })
}

/// Settle which transcripts are drawn under their notes, in place, returning
/// the indices of the rows whose view changed. See the module doc for the
/// rule.
///
/// Settled: a second pass over the same rows changes nothing, so a batch that
/// touches neither side emits no `Set` for them.
pub fn reconcile(rows: &mut [TimelineRow]) -> Vec<usize> {
    // Pass 1: the notes transcripts name, earliest transcript first, and
    // whether any note carries a transcript now (which may have to come off).
    let mut wanted: HashMap<&str, usize> = HashMap::new();
    let mut any_folded = false;
    for (index, row) in rows.iter().enumerate() {
        if let Some(note) = transcript_side(row) {
            wanted.entry(note).or_insert(index);
        }
        any_folded |= matches!(
            row.view,
            ItemView::Audio {
                transcript: Some(_),
                ..
            }
        );
    }
    if wanted.is_empty() && !any_folded {
        return Vec::new();
    }

    // Pass 2: the notes those name, found by event id.
    let mut note_to_transcript: HashMap<usize, usize> = HashMap::new();
    let mut transcript_folded = vec![false; rows.len()];
    if !wanted.is_empty() {
        for (index, row) in rows.iter().enumerate() {
            let Some(&transcript_index) = row.item.event_id.as_deref().and_then(|e| wanted.get(e))
            else {
                continue;
            };
            if is_transcribable_note(row) {
                note_to_transcript.insert(index, transcript_index);
                transcript_folded[transcript_index] = true;
            }
        }
    }

    // Pass 3: what each row involved should be drawn as.
    let mut updates: Vec<(usize, ItemView)> = Vec::new();
    for (index, row) in rows.iter().enumerate() {
        let wanted_view = if transcript_side(row).is_some() {
            match (transcript_folded[index], &row.view) {
                (true, ItemView::None) => continue,
                (true, _) => ItemView::None,
                (false, ItemView::None) => {
                    let notice = row.voice_transcript.as_ref().expect("a transcript side");
                    ItemView::VoiceTranscript {
                        transcript: notice.transcript.clone(),
                        on_own_note: notice.on_own_note,
                    }
                }
                (false, _) => continue,
            }
        } else if let ItemView::Audio { audio, transcript } = &row.view {
            let should = note_to_transcript.get(&index).map(|t| {
                &rows[*t]
                    .voice_transcript
                    .as_ref()
                    .expect("a transcript side")
                    .transcript
            });
            if transcript.as_ref() == should {
                continue;
            }
            ItemView::Audio {
                audio: audio.clone(),
                transcript: should.cloned(),
            }
        } else {
            continue;
        };
        updates.push((index, wanted_view));
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
    use serde_json::json;

    fn hub() -> Value {
        json!({
            "schema_version": 1,
            "text": "Can you move the review to Thursday?",
            "language": "en",
            "seconds": 42,
        })
    }

    #[test]
    fn the_hubs_transcript_reads_with_its_language_and_length() {
        let t = parse_voice_transcript(&hub()).expect("the hub's own payload must parse");
        assert_eq!(t.text, "Can you move the review to Thursday?");
        assert_eq!(t.language.as_deref(), Some("en"));
        assert_eq!(t.seconds, Some(42));
        assert_eq!(t.duration.as_deref(), Some("0:42"));
        assert_eq!(t.caption, "Transcript · en · 0:42");
        assert_eq!(
            t.accessibility_label,
            "Transcript of voice note: Can you move the review to Thursday?"
        );
    }

    #[test]
    fn the_caption_names_only_what_the_hub_gave() {
        let mut only_text = hub();
        only_text.as_object_mut().unwrap().remove("language");
        only_text.as_object_mut().unwrap().remove("seconds");
        assert_eq!(
            parse_voice_transcript(&only_text).unwrap().caption,
            "Transcript"
        );

        let mut no_language = hub();
        no_language["language"] = Value::Null;
        let t = parse_voice_transcript(&no_language).unwrap();
        assert_eq!(t.language, None);
        assert_eq!(t.caption, "Transcript · 0:42", "no dangling separator");

        let mut no_seconds = hub();
        no_seconds["seconds"] = Value::Null;
        assert_eq!(
            parse_voice_transcript(&no_seconds).unwrap().caption,
            "Transcript · en"
        );

        let mut blank_language = hub();
        blank_language["language"] = json!("  ");
        assert_eq!(
            parse_voice_transcript(&blank_language).unwrap().language,
            None
        );
    }

    #[test]
    fn the_length_reads_as_minutes_and_seconds() {
        for (seconds, expected) in [
            (0, "0:00"),
            (9, "0:09"),
            (60, "1:00"),
            (725, "12:05"),
            (3_600, "60:00"),
        ] {
            let mut payload = hub();
            payload["seconds"] = json!(seconds);
            let t = parse_voice_transcript(&payload).unwrap();
            assert_eq!(t.duration.as_deref(), Some(expected), "{seconds}");
        }
    }

    #[test]
    fn a_non_latin_transcript_is_kept_whole() {
        let mut payload = hub();
        payload["text"] = json!("कल सुबह दस बजे टीम की बैठक है");
        payload["language"] = json!("hi");
        payload["seconds"] = json!(4);
        let t = parse_voice_transcript(&payload).unwrap();
        assert_eq!(t.text, "कल सुबह दस बजे टीम की बैठक है");
        assert_eq!(t.caption, "Transcript · hi · 0:04");
    }

    #[test]
    fn the_text_is_trimmed() {
        let mut payload = hub();
        payload["text"] = json!("\n  hello  \n");
        assert_eq!(parse_voice_transcript(&payload).unwrap().text, "hello");
    }

    #[test]
    fn the_bounds_are_inclusive() {
        let mut payload = hub();
        // Two bytes a character, so a byte count would refuse this.
        payload["text"] = json!("é".repeat(TEXT_MAX_CHARS));
        payload["language"] = json!("x".repeat(LANGUAGE_MAX_CHARS));
        payload["seconds"] = json!(SECONDS_MAX);
        assert!(parse_voice_transcript(&payload).is_some());
    }

    #[test]
    fn anything_outside_the_contract_is_the_ordinary_notice() {
        let base = hub();
        let mut cases: Vec<(&str, Value)> = vec![
            ("a string", json!("hello")),
            ("an array", json!([base.clone()])),
            ("null", Value::Null),
        ];
        for (field, replacement) in [
            ("schema_version", Value::Null),
            ("schema_version", json!("1")),
            ("schema_version", json!(0)),
            ("schema_version", json!(2)),
            ("schema_version", json!(1.5)),
            ("text", Value::Null),
            ("text", json!(7)),
            ("text", json!("   ")),
            ("text", json!("a".repeat(TEXT_MAX_CHARS + 1))),
            ("language", json!(7)),
            ("language", json!("x".repeat(LANGUAGE_MAX_CHARS + 1))),
            ("language", json!("en\u{202e}")),
            ("language", json!("e\nn")),
            ("language", json!("en<b>")),
            ("seconds", json!(-1)),
            ("seconds", json!(3_601)),
            ("seconds", json!(4.5)),
            ("seconds", json!("42")),
        ] {
            let mut payload = base.clone();
            if replacement.is_null() {
                payload.as_object_mut().unwrap().remove(field);
            } else {
                payload[field] = replacement;
            }
            cases.push((field, payload));
        }
        for (what, payload) in cases {
            assert_eq!(parse_voice_transcript(&payload), None, "{what}: {payload}");
        }
    }

    #[test]
    fn unknown_fields_in_version_one_are_ignored() {
        let mut payload = hub();
        payload["confidence"] = json!(0.93);
        assert!(parse_voice_transcript(&payload).is_some());
    }

    #[test]
    fn the_wire_names_a_host_reads_are_camel_case() {
        let json = serde_json::to_value(parse_voice_transcript(&hub()).unwrap()).unwrap();
        assert_eq!(json["caption"], "Transcript · en · 0:42");
        assert_eq!(json["duration"], "0:42");
        assert_eq!(
            json["accessibilityLabel"],
            "Transcript of voice note: Can you move the review to Thursday?"
        );
    }

    // --- under its note ----------------------------------------------------

    use crate::dto::{AudioMetaDto, MediaMetaDto, ReplyToDto, TimelineItemDto};

    const ME: &str = "@me:hs";
    const HUB: &str = "@agent_scribe:hs";

    fn item(id: &str, sender: &str, msgtype: &str, body: &str) -> TimelineItemDto {
        crate::timeline::project_item_parts(
            id,
            Some(&format!("${id}")),
            "message",
            Some(msgtype),
            None,
            Some(sender),
            None,
            None,
            false,
            Some(body),
            None,
            None,
            None,
            Some(1),
            sender == ME,
            None,
            None,
            false,
            Vec::new(),
            Vec::new(),
        )
    }

    fn note(id: &str) -> TimelineRow {
        let mut it = item(id, ME, "m.audio", "Voice message.ogg");
        it.media = Some(MediaMetaDto {
            filename: "Voice message.ogg".into(),
            mimetype: Some("audio/ogg".into()),
            size: Some(9_000),
            width: None,
            height: None,
            audio: Some(AudioMetaDto {
                is_voice: true,
                duration_ms: Some(3_000),
                waveform: None,
            }),
        });
        TimelineRow::new(it)
    }

    fn said(id: &str, text: &str) -> VoiceNoteTranscript {
        parse_voice_transcript(&json!({ "schema_version": 1, "text": format!("{text} ({id})") }))
            .unwrap()
    }

    /// The hub's notice: an `m.notice` replying to the note, as
    /// `timeline::row_from_parts` projects it.
    fn transcript_of(id: &str, note_id: &str) -> TimelineRow {
        let mut it = item(id, HUB, "m.notice", &format!("Transcript: hello ({id})"));
        it.reply_to = Some(ReplyToDto {
            event_id: format!("${note_id}"),
            available: true,
            sender: Some(ME.into()),
            sender_display_name: None,
            excerpt: None,
            label: Some("Audio".into()),
        });
        TimelineRow::with_voice_transcript(it, Some(said(id, "hello")), ME)
    }

    fn typed(id: &str) -> TimelineRow {
        TimelineRow::new(item(id, ME, "m.text", "Hi?"))
    }

    fn folded(row: &TimelineRow) -> Option<&VoiceNoteTranscript> {
        match &row.view {
            ItemView::Audio { transcript, .. } => transcript.as_ref(),
            _ => None,
        }
    }

    fn is_standalone(row: &TimelineRow) -> bool {
        matches!(row.view, ItemView::VoiceTranscript { .. })
    }

    #[test]
    fn a_transcript_landing_after_another_message_is_drawn_under_its_note() {
        // The live case: the note, "Hi?" a second later, then the transcript.
        let mut rows = vec![note("n"), typed("hi")];
        assert!(reconcile(&mut rows).is_empty(), "no transcript yet");
        rows.push(transcript_of("t", "n"));
        assert!(is_standalone(&rows[2]), "as projected, before settling");

        assert_eq!(reconcile(&mut rows), vec![0, 2]);
        assert_eq!(folded(&rows[0]), Some(&said("t", "hello")));
        assert_eq!(
            rows[2].view,
            ItemView::None,
            "the notice's own row is hidden"
        );
        // "Hi?" is untouched: no row was inserted, moved or redrawn.
        assert_eq!(rows[1], typed("hi"));
        // The note is still the note: its player, its length.
        let ItemView::Audio { audio, .. } = &rows[0].view else {
            unreachable!()
        };
        assert_eq!(audio.length_label.as_deref(), Some("0:03"));

        assert!(reconcile(&mut rows).is_empty(), "settled");
    }

    #[test]
    fn a_transcript_whose_note_is_not_loaded_stays_standalone_until_it_is() {
        let mut rows = vec![typed("hi"), transcript_of("t", "n")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_standalone(&rows[1]));

        // Back-pagination brings the note in at the front.
        rows.insert(0, note("n"));
        assert_eq!(reconcile(&mut rows), vec![0, 2]);
        assert!(folded(&rows[0]).is_some());
        assert_eq!(rows[2].view, ItemView::None);
    }

    #[test]
    fn the_note_paginated_out_brings_the_transcript_back() {
        let mut rows = vec![note("n"), typed("hi"), transcript_of("t", "n")];
        reconcile(&mut rows);
        rows.remove(0);
        assert_eq!(reconcile(&mut rows), vec![1]);
        assert_eq!(rows[1].view, transcript_of("t", "n").view);
    }

    #[test]
    fn a_redacted_transcript_comes_off_the_note() {
        let mut rows = vec![note("n"), transcript_of("t", "n")];
        reconcile(&mut rows);
        // The SDK's `Set` for a redaction: no longer a message, no key.
        let mut gone = rows[1].item.clone();
        gone.kind = "redacted".into();
        gone.msgtype = None;
        rows[1] = TimelineRow::new(gone);

        assert_eq!(reconcile(&mut rows), vec![0]);
        assert_eq!(folded(&rows[0]), None);
        assert_eq!(rows[0].view, note("n").view);
        assert!(matches!(rows[1].view, ItemView::Placeholder { .. }));
    }

    #[test]
    fn a_redacted_note_leaves_the_transcript_standalone() {
        let mut rows = vec![note("n"), transcript_of("t", "n")];
        reconcile(&mut rows);
        let mut gone = rows[0].item.clone();
        gone.kind = "redacted".into();
        gone.msgtype = None;
        rows[0] = TimelineRow::new(gone);

        assert_eq!(reconcile(&mut rows), vec![1]);
        assert!(is_standalone(&rows[1]));
    }

    #[test]
    fn an_edited_transcript_is_drawn_with_its_new_words() {
        let mut rows = vec![note("n"), transcript_of("t", "n")];
        reconcile(&mut rows);
        // The SDK folds the `m.replace` in and re-sends the notice whole,
        // freshly projected from the edit.
        let mut edited = rows[1].item.clone();
        edited.edited = true;
        rows[1] = TimelineRow::with_voice_transcript(edited, Some(said("t", "goodbye")), ME);

        assert_eq!(reconcile(&mut rows), vec![0, 1]);
        assert_eq!(folded(&rows[0]), Some(&said("t", "goodbye")));
        assert_eq!(rows[1].view, ItemView::None);
    }

    #[test]
    fn a_reaction_re_sending_the_note_keeps_its_transcript() {
        let mut rows = vec![note("n"), transcript_of("t", "n")];
        reconcile(&mut rows);
        let settled = rows.clone();
        // A reaction or receipt on the note: re-sent freshly projected,
        // without its transcript.
        rows[0] = note("n");
        assert_eq!(reconcile(&mut rows), vec![0]);
        assert_eq!(rows, settled);
    }

    #[test]
    fn the_first_transcript_of_a_note_folds_and_a_second_stays_standalone() {
        let mut rows = vec![
            note("n"),
            transcript_of("t1", "n"),
            transcript_of("t2", "n"),
        ];
        reconcile(&mut rows);
        assert_eq!(folded(&rows[0]), Some(&said("t1", "hello")));
        assert_eq!(rows[1].view, ItemView::None);
        assert!(is_standalone(&rows[2]), "seen, not silently swapped in");
    }

    #[test]
    fn a_transcript_replying_to_something_that_is_not_a_note_stays_standalone() {
        let mut rows = vec![typed("hi"), transcript_of("t", "hi")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_standalone(&rows[1]));
    }

    #[test]
    fn a_note_folded_into_an_agents_text_keeps_its_transcript_standalone() {
        // `crate::voice_reply` hid the note's row: the text is the words.
        let mut hidden = note("n");
        hidden.view = ItemView::None;
        let mut rows = vec![hidden, transcript_of("t", "n")];
        assert!(reconcile(&mut rows).is_empty());
        assert!(is_standalone(&rows[1]));
    }

    #[test]
    fn a_timeline_without_transcripts_is_untouched() {
        let mut rows = vec![note("a"), typed("b")];
        let before = rows.clone();
        assert!(reconcile(&mut rows).is_empty());
        assert_eq!(rows, before);
    }

    #[test]
    fn a_long_room_folds_in_linear_time() {
        const NOTES: usize = 5_000;
        let mut rows = Vec::with_capacity(NOTES * 3);
        for n in 0..NOTES {
            rows.push(note(&format!("n{n}")));
            rows.push(typed(&format!("hi{n}")));
            rows.push(transcript_of(&format!("t{n}"), &format!("n{n}")));
        }
        let started = std::time::Instant::now();
        assert_eq!(reconcile(&mut rows).len(), NOTES * 2);
        let first = started.elapsed();
        let started = std::time::Instant::now();
        assert!(reconcile(&mut rows).is_empty());
        let settled = started.elapsed();
        let budget = std::time::Duration::from_millis(1_500);
        assert!(first < budget, "first pass took {first:?}");
        assert!(settled < budget, "settled pass took {settled:?}");
    }

    #[test]
    fn the_folded_transcript_is_on_the_notes_json_and_the_link_is_not() {
        let mut rows = vec![note("n"), transcript_of("t", "n")];
        reconcile(&mut rows);
        let json = serde_json::to_value(&rows[0]).unwrap();
        assert_eq!(json["view"]["render"], "audio");
        assert_eq!(json["view"]["transcript"]["caption"], "Transcript");
        // A note without one carries no key at all, so no fixture changes.
        let plain = serde_json::to_value(note("m")).unwrap();
        assert!(plain["view"].get("transcript").is_none());
        assert!(plain.get("voiceTranscript").is_none());
    }
}
