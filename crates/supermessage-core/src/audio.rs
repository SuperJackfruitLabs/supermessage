//! Audio messages: what a host needs to draw a voice note or an audio file as
//! a player, and the bytes it needs to play one.
//!
//! Three hosts draw the same `m.audio` event. Whether it is a voice note,
//! how long it is and what that length reads as, what its waveform looks
//! like, and what a screen reader says about it are decided **here**, once,
//! so a note cannot be "0:07" on a phone and "0:06" on a desktop.
//!
//! ## What the event says
//!
//! A voice note (MSC3245, the unstable form every client sends today) is an
//! `m.audio` with `org.matrix.msc3245.voice: {}` and an
//! `org.matrix.msc1767.audio` block holding `duration` in **milliseconds** and
//! `waveform`, a list of integers `0..=1024` (MSC3246's first version, which
//! ruma's `UnstableAmplitude` still reads; the proposal's later 0–256 range
//! never reached the unstable key). `info.duration` carries the length too,
//! also in milliseconds (spec: `m.audio` `AudioInfo`).
//!
//! Anyone can send an event, so the waveform is bounded here
//! ([`WAVEFORM_MAX_BARS`]) and its values are normalised and clamped to
//! `0..=1` whatever they claimed to be.
//!
//! ## What a player can open
//!
//! [`playable_audio`] hands a host bytes its platform player can open. For an
//! Ogg/Opus voice note — which is what Element and this app send — that is a
//! remux into CAF for a host that says it needs one (AVFoundation, and a
//! WebKit that cannot play Ogg). See `crate::opus_container`.

use crate::dto::AudioMetaDto;
use crate::error::{CoreError, CoreResult};
use crate::opus_container::{is_caf, is_ogg_opus, ogg_to_caf};

/// The most bars a host is handed. MSC3246 asks senders for 30 to 120; a
/// longer list is averaged down to this, never passed through, because a
/// hostile 50,000-entry waveform would otherwise ride along on every timeline
/// diff that touches the event.
pub const WAVEFORM_MAX_BARS: usize = 120;

/// The top of MSC3246's first-version amplitude range.
pub const AMPLITUDE_MAX: f32 = 1024.0;

/// A day. A length beyond this is not a recording anyone made; it is treated
/// as unknown rather than drawn as "1440:00".
pub const DURATION_MAX_MS: u64 = 24 * 60 * 60 * 1000;

/// An `m.audio` message, ready to draw as a player.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct AudioView {
    /// Flagged as a voice message (MSC3245). A host draws a voice bubble for
    /// this and an audio-file player, with its file name, for anything else.
    pub is_voice: bool,
    /// The length, from the event. `None` when the sender did not say; a
    /// host then learns it from its player once the file is open, and formats
    /// it with [`audio_clock_label`] like any other time.
    pub duration_ms: Option<u64>,
    /// `duration_ms` as the reader sees it at rest: `"0:07"`, `"1:05"`,
    /// `"1:02:03"`. Rounded to the nearest second, and never `"0:00"` for a
    /// note that has any sound in it.
    pub length_label: Option<String>,
    /// Bars between 0 and 1, oldest first, at most [`WAVEFORM_MAX_BARS`].
    /// `None` when the event carried none: a host draws a neutral, even set of
    /// bars rather than inventing a shape.
    pub waveform: Option<Vec<f32>>,
    /// What to call it: `"Voice message"` for a voice note, the file's name
    /// otherwise.
    pub title: String,
    pub filename: String,
    pub size: Option<u64>,
    pub mimetype: Option<String>,
    /// What the sender wrote with it (MSC2530), when anything.
    pub caption: Option<String>,
    /// What a screen reader says for the player at rest: `"Voice message, 7
    /// seconds"`. The platform's own hint ("double-tap to play") is the
    /// host's to add.
    pub accessibility_label: String,
}

/// Reads an `m.audio`'s own content into [`AudioMetaDto`]: the voice flag,
/// the length, and the waveform normalised to `0..=1` and bounded.
///
/// The MSC1767 block's duration wins over `info.duration` when both are
/// present — it is the one voice clients write together with the waveform —
/// and a zero or absurd length from either is `None`.
pub fn audio_meta(
    content: &matrix_sdk::ruma::events::room::message::AudioMessageEventContent,
) -> AudioMetaDto {
    let block = content.audio.as_ref();
    let duration = block
        .map(|b| b.duration)
        .into_iter()
        .chain(content.info.as_ref().and_then(|i| i.duration))
        .map(|d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
        .find(|ms| (1..=DURATION_MAX_MS).contains(ms));
    let waveform = block.and_then(|b| {
        normalise_waveform(
            b.waveform
                .iter()
                .map(|a| u64::from(a.get()) as f32 / AMPLITUDE_MAX),
        )
    });
    AudioMetaDto {
        is_voice: content.voice.is_some(),
        duration_ms: duration,
        waveform,
    }
}

/// Clamps each level into `0..=1` (NaN is silence) and averages a list
/// longer than [`WAVEFORM_MAX_BARS`] down to it. `None` for an empty list.
pub fn normalise_waveform(levels: impl IntoIterator<Item = f32>) -> Option<Vec<f32>> {
    let levels: Vec<f32> = levels
        .into_iter()
        .map(|v| if v.is_nan() { 0.0 } else { v.clamp(0.0, 1.0) })
        .collect();
    if levels.is_empty() {
        return None;
    }
    if levels.len() <= WAVEFORM_MAX_BARS {
        return Some(levels);
    }
    let n = levels.len();
    Some(
        (0..WAVEFORM_MAX_BARS)
            .map(|bar| {
                let start = bar * n / WAVEFORM_MAX_BARS;
                let end = ((bar + 1) * n / WAVEFORM_MAX_BARS).max(start + 1);
                let slice = &levels[start..end];
                slice.iter().sum::<f32>() / slice.len() as f32
            })
            .collect(),
    )
}

/// A position or a length as a clock: `m:ss` under an hour, `h:mm:ss` from
/// one. **Truncates** to the whole second, which is what a clock does: a
/// note 6.9 s in has played six seconds.
///
/// The one formatter every host uses for a playing note's elapsed time, so
/// the phone and the desktop tick over at the same instant.
pub fn audio_clock_label(ms: u64) -> String {
    clock(ms / 1000)
}

/// A length at rest: rounded to the nearest second, and at least one second
/// for anything with sound in it, so a 0.6 s note reads "0:01", not "0:00".
pub fn audio_length_label(ms: u64) -> String {
    clock(rounded_seconds(ms))
}

fn rounded_seconds(ms: u64) -> u64 {
    if ms == 0 {
        0
    } else {
        ((ms + 500) / 1000).max(1)
    }
}

fn clock(seconds: u64) -> String {
    let (h, m, s) = (seconds / 3600, (seconds / 60) % 60, seconds % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// A length as a sentence says it: "7 seconds", "1 minute 5 seconds",
/// "2 minutes". English, like every other sentence the core composes.
pub fn spoken_length(ms: u64) -> String {
    let seconds = rounded_seconds(ms);
    let (h, m, s) = (seconds / 3600, (seconds / 60) % 60, seconds % 60);
    let unit = |n: u64, one: &str| {
        if n == 1 {
            format!("1 {one}")
        } else {
            format!("{n} {one}s")
        }
    };
    let mut parts = Vec::new();
    if h > 0 {
        parts.push(unit(h, "hour"));
    }
    if m > 0 {
        parts.push(unit(m, "minute"));
    }
    if s > 0 || parts.is_empty() {
        parts.push(unit(s, "second"));
    }
    parts.join(" ")
}

/// The player view for an `m.audio` item, from its projected metadata.
pub fn audio_view(
    meta: Option<&AudioMetaDto>,
    filename: String,
    size: Option<u64>,
    mimetype: Option<String>,
    caption: Option<String>,
) -> AudioView {
    let is_voice = meta.is_some_and(|m| m.is_voice);
    let duration_ms = meta.and_then(|m| m.duration_ms);
    let title = if is_voice {
        "Voice message".to_string()
    } else {
        filename.clone()
    };
    let accessibility_label = match (is_voice, duration_ms) {
        (true, Some(ms)) => format!("Voice message, {}", spoken_length(ms)),
        (true, None) => "Voice message".to_string(),
        (false, Some(ms)) => format!("Audio, {filename}, {}", spoken_length(ms)),
        (false, None) => format!("Audio, {filename}"),
    };
    AudioView {
        is_voice,
        duration_ms,
        length_label: duration_ms.map(audio_length_label),
        waveform: meta.and_then(|m| m.waveform.clone()),
        title,
        filename,
        size,
        mimetype,
        caption,
        accessibility_label,
    }
}

/// Audio bytes a host's player can open, and what they are.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PlayableAudio {
    pub data: Vec<u8>,
    /// What `data` is, sniffed from the bytes: `audio/ogg`, `audio/x-caf`,
    /// `audio/mp4`… — never the sender's claim when the bytes say otherwise.
    pub mimetype: String,
    /// The extension a player that goes by file name needs: `ogg`, `caf`,
    /// `m4a`…
    pub file_extension: String,
    /// The length read from the file itself, when the container says it
    /// exactly (Ogg and CAF Opus). A host prefers its player's own once open.
    pub duration_ms: Option<u64>,
}

/// Turns a downloaded audio file into something the host can play.
///
/// `opus_in_caf` is the host saying its player reads Opus only from CAF —
/// AVFoundation on iOS, and a WebKit whose `<audio>` cannot play Ogg. An
/// Ogg/Opus file is then remuxed (not transcoded; see
/// `crate::opus_container`). Anything else goes through unchanged, with its
/// type sniffed from its bytes rather than taken from the event.
pub fn playable_audio(
    bytes: Vec<u8>,
    declared_mimetype: Option<&str>,
    opus_in_caf: bool,
) -> CoreResult<PlayableAudio> {
    if is_ogg_opus(&bytes) {
        if opus_in_caf {
            let caf = ogg_to_caf(&bytes).map_err(|e| CoreError::Protocol(e.to_string()))?;
            return Ok(PlayableAudio {
                data: caf.bytes,
                mimetype: "audio/x-caf".into(),
                file_extension: "caf".into(),
                duration_ms: Some(caf.duration_ms),
            });
        }
        let duration_ms = crate::opus_container::ogg_duration_ms(&bytes).ok();
        return Ok(PlayableAudio {
            data: bytes,
            mimetype: "audio/ogg".into(),
            file_extension: "ogg".into(),
            duration_ms,
        });
    }
    let (mimetype, extension) = sniff_audio(&bytes)
        .map(|(m, e)| (m.to_string(), e.to_string()))
        .or_else(|| {
            // Unrecognised bytes: the sender's word is all there is, and only
            // if it at least claims to be audio.
            declared_mimetype
                .filter(|m| m.starts_with("audio/"))
                .map(|m| (m.to_string(), "audio".to_string()))
        })
        .ok_or_else(|| CoreError::Protocol("this is not an audio file".into()))?;
    Ok(PlayableAudio {
        data: bytes,
        mimetype,
        file_extension: extension,
        duration_ms: None,
    })
}

/// The container of an audio file, from its first bytes.
fn sniff_audio(bytes: &[u8]) -> Option<(&'static str, &'static str)> {
    if is_caf(bytes) {
        Some(("audio/x-caf", "caf"))
    } else if bytes.starts_with(b"OggS") {
        Some(("audio/ogg", "ogg"))
    } else if bytes.get(4..8) == Some(b"ftyp") {
        Some(("audio/mp4", "m4a"))
    } else if bytes.starts_with(b"ID3")
        || (bytes.len() > 1 && bytes[0] == 0xFF && bytes[1] & 0xE0 == 0xE0)
    {
        Some(("audio/mpeg", "mp3"))
    } else if bytes.starts_with(b"RIFF") && bytes.get(8..12) == Some(b"WAVE") {
        Some(("audio/wav", "wav"))
    } else if bytes.starts_with(b"fLaC") {
        Some(("audio/flac", "flac"))
    } else if bytes.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) {
        Some(("audio/webm", "webm"))
    } else if bytes.starts_with(b"FORM") && bytes.get(8..12) == Some(b"AIFF") {
        Some(("audio/aiff", "aiff"))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use matrix_sdk::ruma::events::room::message::{
        AudioInfo, AudioMessageEventContent, UnstableAudioDetailsContentBlock,
        UnstableVoiceContentBlock,
    };
    use matrix_sdk::ruma::OwnedMxcUri;
    use std::time::Duration;

    const LIBOPUS_OGG: &[u8] = include_bytes!("../tests/fixtures/voice-libopus.ogg");
    const APPLE_CAF: &[u8] = include_bytes!("../tests/fixtures/voice-apple.caf");

    /// An event as another client sent it, parsed the way the timeline
    /// parses it — from JSON, not from ruma's constructors.
    fn content(json: serde_json::Value) -> AudioMessageEventContent {
        serde_json::from_value(json).expect("valid m.audio content")
    }

    fn voice_json(waveform: serde_json::Value) -> serde_json::Value {
        serde_json::json!({
            "msgtype": "m.audio",
            "body": "Voice message.ogg",
            "url": "mxc://example.org/abc",
            "info": { "mimetype": "audio/ogg", "size": 7992, "duration": 7_400 },
            "org.matrix.msc1767.audio": { "duration": 7_400, "waveform": waveform },
            "org.matrix.msc3245.voice": {}
        })
    }

    #[test]
    fn a_voice_note_with_a_waveform_reads_its_flag_length_and_bars() {
        let meta = audio_meta(&content(voice_json(serde_json::json!([0, 256, 512, 1024]))));
        assert!(meta.is_voice);
        assert_eq!(meta.duration_ms, Some(7_400));
        assert_eq!(meta.waveform, Some(vec![0.0, 0.25, 0.5, 1.0]));

        let view = audio_view(
            Some(&meta),
            "Voice message.ogg".into(),
            Some(7992),
            None,
            None,
        );
        assert_eq!(view.title, "Voice message");
        assert_eq!(view.length_label.as_deref(), Some("0:07"));
        assert_eq!(view.accessibility_label, "Voice message, 7 seconds");
    }

    #[test]
    fn a_voice_note_without_a_waveform_has_none_rather_than_a_made_up_shape() {
        let mut json = voice_json(serde_json::json!([]));
        json["org.matrix.msc1767.audio"]
            .as_object_mut()
            .unwrap()
            .remove("waveform");
        let meta = audio_meta(&content(json));
        assert!(meta.is_voice);
        assert_eq!(meta.duration_ms, Some(7_400));
        assert_eq!(meta.waveform, None);
    }

    #[test]
    fn a_plain_audio_file_is_not_a_voice_note() {
        let meta = audio_meta(&content(serde_json::json!({
            "msgtype": "m.audio",
            "body": "song.mp3",
            "url": "mxc://example.org/abc",
            "info": { "mimetype": "audio/mpeg", "duration": 185_000 }
        })));
        assert!(!meta.is_voice);
        assert_eq!(meta.duration_ms, Some(185_000));
        assert_eq!(meta.waveform, None);
        let view = audio_view(Some(&meta), "song.mp3".into(), None, None, None);
        assert_eq!(view.title, "song.mp3");
        assert_eq!(view.length_label.as_deref(), Some("3:05"));
        assert_eq!(
            view.accessibility_label,
            "Audio, song.mp3, 3 minutes 5 seconds"
        );
    }

    #[test]
    fn a_missing_or_nonsense_length_is_unknown() {
        let meta = audio_meta(&content(serde_json::json!({
            "msgtype": "m.audio",
            "body": "Voice message.ogg",
            "url": "mxc://example.org/abc",
            "org.matrix.msc3245.voice": {}
        })));
        assert_eq!(meta.duration_ms, None);
        let view = audio_view(Some(&meta), "Voice message.ogg".into(), None, None, None);
        assert_eq!(view.length_label, None);
        assert_eq!(view.accessibility_label, "Voice message");

        // Zero, and a year: neither is a recording's length.
        let zero = audio_meta(&content(voice_json(serde_json::json!([1])).tap(|j| {
            j["org.matrix.msc1767.audio"]["duration"] = 0.into();
            j["info"]["duration"] = 0.into();
        })));
        assert_eq!(zero.duration_ms, None);
        let year = audio_meta(&content(voice_json(serde_json::json!([1])).tap(|j| {
            j["org.matrix.msc1767.audio"]["duration"] = 31_536_000_000u64.into();
            j["info"]["duration"] = 31_536_000_000u64.into();
        })));
        assert_eq!(year.duration_ms, None);
    }

    #[test]
    fn info_duration_stands_in_when_the_voice_block_has_none_worth_reading() {
        let meta = audio_meta(&content(voice_json(serde_json::json!([1])).tap(|j| {
            j["org.matrix.msc1767.audio"]["duration"] = 0.into();
            j["info"]["duration"] = 6_100.into();
        })));
        assert_eq!(meta.duration_ms, Some(6_100));
    }

    #[test]
    fn an_oversized_waveform_is_averaged_down_to_the_bound() {
        // 50,000 bars alternating silence and full scale.
        let bars: Vec<u64> = (0..50_000)
            .map(|i| if i % 2 == 0 { 0 } else { 1024 })
            .collect();
        let meta = audio_meta(&content(voice_json(serde_json::json!(bars))));
        let waveform = meta.waveform.unwrap();
        assert_eq!(waveform.len(), WAVEFORM_MAX_BARS);
        assert!(
            waveform.iter().all(|v| (0.49..=0.51).contains(v)),
            "{waveform:?}"
        );
    }

    #[test]
    fn out_of_range_levels_are_clamped_not_passed_through() {
        // Above 1024 saturates in ruma's reader; this bounds what reaches a host
        // whatever the reader does.
        let meta = audio_meta(&content(voice_json(serde_json::json!([5000, 1024, 0]))));
        assert_eq!(meta.waveform, Some(vec![1.0, 1.0, 0.0]));
        assert_eq!(
            normalise_waveform([-1.0, f32::NAN, 2.0, 0.5]),
            Some(vec![0.0, 0.0, 1.0, 0.5])
        );
        assert_eq!(normalise_waveform([]), None);
    }

    #[test]
    fn a_waveform_at_the_bound_is_kept_bar_for_bar() {
        let levels: Vec<f32> = (0..WAVEFORM_MAX_BARS).map(|i| i as f32 / 200.0).collect();
        assert_eq!(normalise_waveform(levels.clone()), Some(levels));
    }

    #[test]
    fn our_own_constructed_content_reads_back_the_same_way() {
        // What the SDK builds for a send, read by what the timeline reads.
        let mut sent = AudioMessageEventContent::plain(
            "Voice message.ogg".into(),
            OwnedMxcUri::from("mxc://example.org/abc"),
        );
        sent.audio = Some(UnstableAudioDetailsContentBlock::new(
            Duration::from_millis(1_468),
            vec![0u16.into(), 512u16.into()],
        ));
        sent.voice = Some(UnstableVoiceContentBlock::new());
        sent.info = Some(Box::new(AudioInfo::new()));
        let meta = audio_meta(&sent);
        assert_eq!(meta.duration_ms, Some(1_468));
        assert_eq!(meta.waveform, Some(vec![0.0, 0.5]));
    }

    #[test]
    fn clock_labels_truncate_and_length_labels_round() {
        assert_eq!(audio_clock_label(0), "0:00");
        assert_eq!(audio_clock_label(6_999), "0:06");
        assert_eq!(audio_clock_label(65_000), "1:05");
        assert_eq!(audio_clock_label(299_999), "4:59");
        assert_eq!(audio_clock_label(3_723_000), "1:02:03");

        assert_eq!(audio_length_label(6_499), "0:06");
        assert_eq!(audio_length_label(6_500), "0:07");
        assert_eq!(audio_length_label(299_000), "4:59");
        // Any sound at all is at least a second.
        assert_eq!(audio_length_label(300), "0:01");
        assert_eq!(audio_length_label(0), "0:00");
    }

    /// The table the desktop's port of the clock is checked against too.
    #[test]
    fn the_clock_matches_the_table_every_host_is_held_to() {
        let table: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/audio-clock.json")).unwrap();
        let cases = table["cases"].as_array().unwrap();
        assert!(cases.len() >= 10);
        for case in cases {
            let ms = case["ms"].as_u64().unwrap();
            assert_eq!(
                audio_clock_label(ms),
                case["label"].as_str().unwrap(),
                "{ms} ms"
            );
        }
    }

    #[test]
    fn spoken_lengths_read_as_a_sentence_would() {
        assert_eq!(spoken_length(7_000), "7 seconds");
        assert_eq!(spoken_length(1_000), "1 second");
        assert_eq!(spoken_length(60_000), "1 minute");
        assert_eq!(spoken_length(65_000), "1 minute 5 seconds");
        assert_eq!(spoken_length(3_661_000), "1 hour 1 minute 1 second");
        assert_eq!(spoken_length(400), "1 second");
    }

    #[test]
    fn the_audio_view_is_tagged_camel_case_for_the_desktop() {
        let view = audio_view(None, "a.mp3".into(), Some(1), None, None);
        let json = serde_json::to_value(&view).unwrap();
        assert_eq!(json["isVoice"], false);
        assert_eq!(json["lengthLabel"], serde_json::Value::Null);
        assert_eq!(json["accessibilityLabel"], "Audio, a.mp3");
    }

    // ---- playable_audio ---------------------------------------------------

    #[test]
    fn an_ogg_voice_note_is_remuxed_for_a_host_that_needs_caf() {
        let playable = playable_audio(LIBOPUS_OGG.to_vec(), Some("audio/ogg"), true).unwrap();
        assert_eq!(playable.mimetype, "audio/x-caf");
        assert_eq!(playable.file_extension, "caf");
        assert!(is_caf(&playable.data));
        assert_eq!(playable.duration_ms, Some(1_468));
    }

    #[test]
    fn an_ogg_voice_note_goes_through_unchanged_for_a_host_that_plays_ogg() {
        let playable = playable_audio(LIBOPUS_OGG.to_vec(), Some("audio/ogg"), false).unwrap();
        assert_eq!(playable.mimetype, "audio/ogg");
        assert_eq!(playable.data, LIBOPUS_OGG);
        assert_eq!(playable.duration_ms, Some(1_468));
    }

    #[test]
    fn a_legacy_m4a_note_is_passed_through_as_mp4() {
        let mut m4a = vec![0, 0, 0, 0x20];
        m4a.extend_from_slice(b"ftypM4A ");
        let playable = playable_audio(m4a.clone(), Some("audio/mp4"), true).unwrap();
        assert_eq!(playable.mimetype, "audio/mp4");
        assert_eq!(playable.file_extension, "m4a");
        assert_eq!(playable.data, m4a);
    }

    #[test]
    fn bytes_decide_the_type_not_the_senders_claim() {
        let playable = playable_audio(APPLE_CAF.to_vec(), Some("audio/ogg"), false).unwrap();
        assert_eq!(playable.mimetype, "audio/x-caf");
    }

    #[test]
    fn unrecognised_bytes_play_only_if_they_at_least_claim_to_be_audio() {
        assert_eq!(
            playable_audio(b"????".to_vec(), Some("audio/amr"), true)
                .unwrap()
                .mimetype,
            "audio/amr"
        );
        assert!(playable_audio(b"????".to_vec(), Some("text/html"), true).is_err());
        assert!(playable_audio(b"????".to_vec(), None, true).is_err());
    }

    #[test]
    fn a_damaged_ogg_note_is_an_error_not_a_panic() {
        let mut broken = LIBOPUS_OGG[..200].to_vec();
        broken[100] ^= 0xFF;
        assert!(playable_audio(broken, Some("audio/ogg"), true).is_err());
    }

    /// `serde_json::Value` tweaks inline.
    trait Tap: Sized {
        fn tap(self, f: impl FnOnce(&mut Self)) -> Self;
    }
    impl Tap for serde_json::Value {
        fn tap(mut self, f: impl FnOnce(&mut Self)) -> Self {
            f(&mut self);
            self
        }
    }
}
