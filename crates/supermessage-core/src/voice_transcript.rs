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

use serde_json::Value;

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
}
