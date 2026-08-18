//! What a timeline item should be drawn as.
//!
//! Ported from `$lib/components/timelineItemView.ts`, which owned this
//! decision until iOS needed the same answers. It is a *classification* of a
//! Matrix event, not a styling choice: whether an `m.room.name` change is a
//! visible row, whether an undecryptable event says something specific or
//! something generic, whether a reaction key is safe to render at the length
//! its sender chose. Two clients disagreeing about any of that is a bug, so it
//! lives here rather than three times over.
//!
//! **Suppression happens here, deliberately, rather than in the timeline
//! projection.** The core still emits every item; this decides which of them
//! render. Dropping them earlier would mean a setting that reveals membership
//! noise could not be added without a protocol change, and it would make the
//! item stream depend on display preferences.
//!
//! The regression that produced the original module is worth keeping in view:
//! an `m.room.name` change used to render as a visible `Unsupported event
//! (m.room.name)` row. The general rule, from `docs/matrix-events.md` §C, is
//! that state events are suppressed unless they change something the reader
//! must know about.

use crate::dto::{ReplyToDto, TimelineItemDto};
use crate::rich::{blocks_from_markdown, blocks_from_sanitised_html, RichBlock};

/// The render decision for one item.
///
/// `dateDivider` has no variant here: it renders real content (a formatted
/// date) rather than a decision this vocabulary covers, and the host handles
/// it before asking.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "render")]
pub enum ItemView {
    /// An ordinary message. `muted` is `m.notice` — automated output from
    /// bridges and agents, de-emphasised but never suppressed, since it is
    /// the msgtype most of this org's agent traffic actually uses.
    ///
    /// `blocks` is the parsed body, so a host draws rich text without
    /// touching markdown or HTML itself. See `crate::rich`.
    Bubble {
        muted: bool,
        blocks: Vec<RichBlock>,
    },
    Emote,
    System {
        text: String,
    },
    /// The line between what has been read and what has not, which the SDK
    /// inserts at most once per timeline.
    ///
    /// Carries no text: the divider says everything, and a label repeated at
    /// every scroll position would be chrome pretending to be content.
    UnreadMarker,
    Placeholder {
        text: String,
    },
    /// An `m.image`. `alt` is never empty — it falls back through the media
    /// filename, then the plain body, to a generic label — because this is
    /// genuine message content rather than decoration.
    ///
    /// `width`/`height` are the image's own pixel dimensions, `None` when the
    /// sender's client never reported them. A host uses them to reserve the
    /// thumbnail's box *before* its bytes are requested, so a lazy list never
    /// reflows once they land.
    Image {
        alt: String,
        width: Option<u64>,
        height: Option<u64>,
    },
    /// An `m.file`/`m.audio`/`m.video`: an informative row naming what the
    /// message is. `label` is precomputed so a host needs no msgtype table.
    MediaFile {
        label: MediaFileLabel,
        filename: String,
        size: Option<u64>,
        mimetype: Option<String>,
    },
    None,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum MediaFileLabel {
    File,
    Audio,
    Video,
}

impl MediaFileLabel {
    /// The human-facing kind name. Also the last-resort filename, which is
    /// why it is a method rather than a host-side lookup.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::File => "File",
            Self::Audio => "Audio",
            Self::Video => "Video",
        }
    }

    fn for_msgtype(msgtype: &str) -> Option<Self> {
        match msgtype {
            "m.file" => Some(Self::File),
            "m.audio" => Some(Self::Audio),
            "m.video" => Some(Self::Video),
            _ => None,
        }
    }
}

/// The quoted parent of a reply, as a host sees it.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "state")]
pub enum ReplyQuoteView {
    /// The parent's details never loaded — a real and common outcome, not an
    /// edge case. The core already folds Unavailable/Pending/Error together,
    /// so this is the one shape a host needs. It renders as "Original message
    /// unavailable" rather than an empty quote or a spinner that will never
    /// resolve on its own.
    Unavailable,
    /// `excerpt` and `label` are mutually exclusive: `label` is only ever
    /// `Some` when `excerpt` is `None`. A ready parent can still have nothing
    /// to quote — a redacted, sticker, poll or undecryptable parent has a
    /// sender but no body — and `label` is the short classification of why,
    /// in the same vocabulary this module's placeholders use.
    Available {
        sender: String,
        excerpt: Option<String>,
        label: Option<String>,
    },
}

/// The name to attribute a line to: display name, then the raw sender id,
/// then a generic placeholder. Never empty.
pub fn attributed_name(item: &TimelineItemDto) -> String {
    item.sender_display_name
        .clone()
        .or_else(|| item.sender.clone())
        .unwrap_or_else(|| "Someone".to_string())
}

/// The verb phrase for a membership item's `detail`.
///
/// Shared with the grouping logic so a collapsed run's sentence uses exactly
/// the wording a single ungrouped membership line would. These strings are
/// user-visible copy: changing one is a product decision, not a translation
/// detail.
pub fn membership_verb(detail: Option<&str>) -> String {
    match detail {
        Some("joined") => "joined the room",
        Some("left") => "left the room",
        Some("invited") => "was invited",
        Some("banned") => "was banned",
        Some("unbanned") => "was unbanned",
        Some("kicked") => "was removed",
        Some("kickedAndBanned") => "was removed and banned",
        Some("invitationAccepted") => "accepted the invite",
        Some("invitationRejected") => "rejected the invite",
        Some("invitationRevoked") => "had their invite revoked",
        Some("knocked") => "asked to join",
        Some("knockAccepted") => "was let in",
        Some("knockRetracted") => "withdrew their request to join",
        Some("knockDenied") => "was denied entry",
        _ => "updated their membership",
    }
    .to_string()
}

/// The reply quote for an item, or `None` when the item is not a reply.
pub fn reply_quote_view(reply_to: Option<&ReplyToDto>) -> Option<ReplyQuoteView> {
    let reply_to = reply_to?;
    if !reply_to.available {
        return Some(ReplyQuoteView::Unavailable);
    }
    Some(ReplyQuoteView::Available {
        sender: reply_to
            .sender_display_name
            .clone()
            .or_else(|| reply_to.sender.clone())
            .unwrap_or_else(|| "Someone".to_string()),
        excerpt: reply_to.excerpt.clone(),
        label: reply_to.label.clone(),
    })
}

/// Whether `item` can be replied to or reacted to.
///
/// Gated on it carrying a real Matrix event id rather than a local echo's
/// transaction id: both operations take an event id, and `id` only becomes
/// one once the server has echoed the item back — which is exactly when
/// `send_state` stops being `notSentYet`/`sendingFailed`.
pub fn can_reply_or_react(item: &TimelineItemDto) -> bool {
    !matches!(
        item.send_state.as_deref(),
        Some("notSentYet") | Some("sendingFailed")
    )
}

/// Cap on the composer's reply-preview text, in `char`s.
///
/// Display-only, on a *fresh* preview built from a live local item's body —
/// distinct from `timeline::REPLY_EXCERPT_MAX_CHARS`, which caps a quoted
/// parent's excerpt. Kept short for the same reason the reply-target row is
/// one line: it is a reminder of what is being replied to, not the message.
const REPLY_PREVIEW_MAX_CHARS: usize = 140;

/// Preview text for the composer's "Replying to …" row, or `None` when there
/// is nothing to preview — a missing, empty or whitespace-only body, which is
/// what a reply to a media message with no caption looks like.
pub fn reply_preview_excerpt(body: Option<&str>) -> Option<String> {
    let trimmed = body?.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(truncate_end(trimmed, REPLY_PREVIEW_MAX_CHARS))
}

/// Cap on a reaction key's *rendered* length, in code points.
///
/// The core never truncates a reaction key: the spec puts no limit on one,
/// and a key is compared byte-for-byte against what other clients sent, so
/// mutating it on the wire would break that comparison. This is display-only.
/// A key is arbitrary sender-controlled text and not necessarily one emoji,
/// so without a cap a long space-free key could stretch a chip arbitrarily
/// wide — the overflow guard every other free-text field from a sender gets.
const REACTION_KEY_MAX_CHARS: usize = 32;

/// The text to render for a reaction key.
pub fn display_reaction_key(key: &str) -> String {
    truncate_end(key, REACTION_KEY_MAX_CHARS)
}

/// Cap on a custom event type's rendered length, in code points.
///
/// Sized for the dispatch card's header: mono at 10px with 0.08em tracking
/// inside a 68ch serif card that also carries a timestamp and padding. A
/// little over 60 glyphs fit; 48 leaves margin at a narrow window without
/// cutting any plausible reverse-DNS type (`dev.supermessage.demo.note.v1`
/// is 29).
const EVENT_TYPE_MAX_CHARS: usize = 48;

/// The text to render for a custom event's Matrix type, truncated **from the
/// left** with a leading ellipsis — `…supermessage.demo.note.v1`, never
/// `dev.supermessage.dem…`.
///
/// A reverse-DNS type's tail is the informative part; its head is the
/// namespace every event from one suite shares, so cutting the usual end
/// throws away exactly the half that distinguishes one card from another.
///
/// Done as a slice rather than with a right-to-left text direction, because
/// this string is sender-controlled. An RTL base direction hands the Unicode
/// bidi algorithm a hostile string and lets a crafted type reorder itself on
/// screen — neutrals migrate across the run, and any strong-RTL character
/// pulls surrounding punctuation with it — which turns a header meant to
/// identify a dispatch into a spoofing surface. A slice reorders nothing.
///
/// A missing, empty or whitespace-only type degrades to `"unknown"`, never to
/// an empty header.
pub fn display_event_type(event_type: Option<&str>) -> String {
    let trimmed = event_type.unwrap_or("").trim();
    if trimmed.is_empty() {
        return "unknown".to_string();
    }
    let count = trimmed.chars().count();
    if count <= EVENT_TYPE_MAX_CHARS {
        return trimmed.to_string();
    }
    let tail: String = trimmed.chars().skip(count - EVENT_TYPE_MAX_CHARS).collect();
    format!("…{tail}")
}

/// Truncate to `max` code points, appending an ellipsis when it bites.
///
/// By `char`, which in Rust *is* a Unicode scalar value, so this cannot split
/// a character the way a byte slice would panic on one. The hazard this
/// guards is real even though its JavaScript form (an unpaired surrogate)
/// cannot exist here: `&s[..max]` on a multi-byte character panics outright.
fn truncate_end(value: &str, max: usize) -> String {
    if value.chars().count() <= max {
        return value.to_string();
    }
    let head: String = value.chars().take(max).collect();
    format!("{head}…")
}

/// The parsed body of a message, from whichever of the two paths it has.
fn blocks_for(item: &TimelineItemDto) -> Vec<RichBlock> {
    match item.formatted_body.as_deref() {
        Some(html) => blocks_from_sanitised_html(html),
        None => blocks_from_markdown(item.body.as_deref().unwrap_or("")),
    }
}

/// Render decision for `kind: "message"`, switching on `msgtype`.
fn message_view(item: &TimelineItemDto) -> ItemView {
    let msgtype = item.msgtype.as_deref();
    match msgtype {
        Some("m.text") => ItemView::Bubble {
            muted: false,
            blocks: blocks_for(item),
        },
        Some("m.notice") => ItemView::Bubble {
            muted: true,
            blocks: blocks_for(item),
        },
        Some("m.emote") => ItemView::Emote,
        Some("m.image") => ItemView::Image {
            alt: item
                .media
                .as_ref()
                .map(|m| m.filename.clone())
                .or_else(|| item.body.clone())
                .unwrap_or_else(|| "Image".to_string()),
            width: item.media.as_ref().and_then(|m| m.width),
            height: item.media.as_ref().and_then(|m| m.height),
        },
        Some(other) if MediaFileLabel::for_msgtype(other).is_some() => {
            let label = MediaFileLabel::for_msgtype(other).expect("guarded by the match arm");
            ItemView::MediaFile {
                label,
                filename: item
                    .media
                    .as_ref()
                    .map(|m| m.filename.clone())
                    .or_else(|| item.body.clone())
                    .unwrap_or_else(|| label.as_str().to_string()),
                size: item.media.as_ref().and_then(|m| m.size),
                mimetype: item.media.as_ref().and_then(|m| m.mimetype.clone()),
            }
        }
        _ => ItemView::Placeholder {
            text: format!("Unsupported message ({})", msgtype.unwrap_or("unknown")),
        },
    }
}

/// Render decision for `kind: "state"`, switching on the state event type.
fn state_view(item: &TimelineItemDto) -> ItemView {
    match item.detail.as_deref() {
        // Not "Beginning of the room" — `timelineStart` owns that exact text.
        // Reaching the true start of a room's history means the SDK loads
        // `m.room.create` *and* inserts the TimelineStart virtual item in the
        // same page, so both render back to back, often separated only by a
        // date divider. Naming the creator is strictly more informative than
        // printing the generic marker twice.
        Some("m.room.create") => ItemView::System {
            text: format!("{} created the room", attributed_name(item)),
        },
        Some("m.room.encryption") => ItemView::System {
            text: "Encryption enabled".to_string(),
        },
        Some("m.room.tombstone") => ItemView::System {
            text: "This room has been replaced".to_string(),
        },
        // Suppressed unless the reader must know. This is the regression the
        // original refactor existed to prevent.
        _ => ItemView::None,
    }
}

/// The render decision for `item`.
///
/// Callers special-case `dateDivider` before reaching here.
pub fn view_for(item: &TimelineItemDto) -> ItemView {
    match item.kind.as_str() {
        "message" => message_view(item),

        "sticker" => ItemView::Placeholder {
            text: "Sticker".to_string(),
        },
        "poll" => ItemView::Placeholder {
            text: "Poll".to_string(),
        },
        "liveLocation" => ItemView::Placeholder {
            text: "Live location".to_string(),
        },
        "callInvite" => ItemView::Placeholder {
            text: "Call".to_string(),
        },
        "rtcNotification" => ItemView::Placeholder {
            text: "Call notification".to_string(),
        },
        "redacted" => ItemView::Placeholder {
            text: "Message deleted".to_string(),
        },

        // "we can see this event but hold no key for it" is expected on a
        // fresh device and resolves itself for messages sent from now on, so
        // it gets its own wording rather than the generic placeholder.
        "unableToDecrypt" => ItemView::Placeholder {
            text: "Encrypted message — this device has no key for it".to_string(),
        },

        // TASK 6: becomes ItemView::CustomEvent once the registry lands. The
        // placeholder text below is deliberately the same wording the
        // registry's own generic fallback uses, so the interim state is not
        // visibly worse than the end state.
        "customMessage" => ItemView::Placeholder {
            text: format!(
                "Custom event ({})",
                display_event_type(item.detail.as_deref())
            ),
        },

        "membership" => ItemView::System {
            text: format!(
                "{} {}",
                attributed_name(item),
                membership_verb(item.detail.as_deref())
            ),
        },

        // Almost always noise (display name and avatar tweaks); a setting can
        // reveal it later.
        "profileChange" => ItemView::None,

        "state" => state_view(item),

        // The *only* legitimate use of "Unsupported event" text — every other
        // fallback in this module has its own wording.
        "failedToParse" => ItemView::Placeholder {
            text: format!(
                "Unsupported event ({})",
                item.detail.as_deref().unwrap_or("unknown")
            ),
        },

        "readMarker" => ItemView::UnreadMarker,

        // The boundary the SDK inserts once back-pagination reaches the
        // genuine start of a room's history — at most once, and always first.
        "timelineStart" => ItemView::System {
            text: "Beginning of the room".to_string(),
        },

        // Defensive only: every kind the core currently emits is handled
        // above. This is a forward-compatibility net for a future core
        // release this build has not been updated for, not a path any current
        // event takes.
        other => ItemView::Placeholder {
            text: format!("Unsupported event ({other})"),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::MediaMetaDto;

    fn item(kind: &str) -> TimelineItemDto {
        TimelineItemDto {
            id: format!("id-{kind}"),
            kind: kind.to_string(),
            msgtype: None,
            detail: None,
            sender: Some("@someone:example.org".to_string()),
            sender_display_name: None,
            body: None,
            formatted_body: None,
            media: None,
            custom_payload: None,
            timestamp_ms: Some(1_700_000_000_000),
            is_own: false,
            send_state: None,
            reply_to: None,
            edited: false,
            reactions: Vec::new(),
            read_by: Vec::new(),
        }
    }

    fn media(filename: &str, mimetype: Option<&str>, size: Option<u64>) -> MediaMetaDto {
        MediaMetaDto {
            filename: filename.to_string(),
            mimetype: mimetype.map(str::to_string),
            size,
            width: None,
            height: None,
        }
    }

    fn reply_to() -> ReplyToDto {
        ReplyToDto {
            event_id: "$parent:example.org".to_string(),
            available: true,
            sender: Some("@alice:example.org".to_string()),
            sender_display_name: Some("Alice".to_string()),
            excerpt: Some("the original message".to_string()),
            label: None,
        }
    }

    // ---- viewFor: message ------------------------------------------------

    #[test]
    fn renders_m_text_as_a_plain_bubble() {
        let mut it = item("message");
        it.msgtype = Some("m.text".into());
        it.body = Some("hi".into());
        let ItemView::Bubble { muted, blocks } = view_for(&it) else {
            panic!("expected a bubble, got {:?}", view_for(&it));
        };
        assert!(!muted);
        assert_eq!(
            blocks,
            crate::rich::blocks_from_markdown("hi"),
            "a bubble must carry its parsed body"
        );
    }

    #[test]
    fn renders_m_notice_as_a_bubble_muted_not_dropped() {
        let mut it = item("message");
        it.msgtype = Some("m.notice".into());
        it.body = Some("build ok".into());
        let ItemView::Bubble { muted, .. } = view_for(&it) else {
            panic!("expected a bubble");
        };
        assert!(muted);
    }

    #[test]
    fn a_bubble_with_a_formatted_body_parses_the_html_not_the_plain_text() {
        // The two paths must not be confused: `body` is the untouched
        // fallback and `formatted_body` the sanitised one, and rendering the
        // wrong one either loses formatting or shows markup as text.
        let mut it = item("message");
        it.msgtype = Some("m.text".into());
        it.body = Some("**not** this".into());
        it.formatted_body = Some("<p><strong>this</strong></p>".into());
        let ItemView::Bubble { blocks, .. } = view_for(&it) else {
            panic!("expected a bubble");
        };
        assert_eq!(
            blocks,
            crate::rich::blocks_from_sanitised_html("<p><strong>this</strong></p>")
        );
    }

    #[test]
    fn renders_m_emote_as_its_own_kind_distinct_from_a_bubble() {
        let mut it = item("message");
        it.msgtype = Some("m.emote".into());
        it.body = Some("waves".into());
        assert_eq!(view_for(&it), ItemView::Emote);
    }

    #[test]
    fn renders_m_image_carrying_alt_text_and_dimensions() {
        let mut it = item("message");
        it.msgtype = Some("m.image".into());
        it.body = Some("cat.png".into());
        it.media = Some(MediaMetaDto {
            width: Some(800),
            height: Some(600),
            ..media("cat.png", Some("image/png"), Some(1024))
        });
        assert_eq!(
            view_for(&it),
            ItemView::Image {
                alt: "cat.png".into(),
                width: Some(800),
                height: Some(600)
            }
        );
    }

    #[test]
    fn falls_back_to_the_body_then_a_generic_label_for_an_image_alt() {
        let mut with_body = item("message");
        with_body.msgtype = Some("m.image".into());
        with_body.body = Some("a screenshot".into());
        assert_eq!(
            view_for(&with_body),
            ItemView::Image {
                alt: "a screenshot".into(),
                width: None,
                height: None
            }
        );

        let mut with_neither = item("message");
        with_neither.msgtype = Some("m.image".into());
        assert_eq!(
            view_for(&with_neither),
            ItemView::Image {
                alt: "Image".into(),
                width: None,
                height: None
            }
        );
    }

    #[test]
    fn renders_file_audio_video_as_an_informative_row_not_a_bare_placeholder() {
        let mut file = item("message");
        file.msgtype = Some("m.file".into());
        file.body = Some("report.pdf".into());
        file.media = Some(media("report.pdf", Some("application/pdf"), Some(2048)));
        assert_eq!(
            view_for(&file),
            ItemView::MediaFile {
                label: MediaFileLabel::File,
                filename: "report.pdf".into(),
                size: Some(2048),
                mimetype: Some("application/pdf".into()),
            }
        );

        let mut audio = item("message");
        audio.msgtype = Some("m.audio".into());
        audio.body = Some("voice.ogg".into());
        assert_eq!(
            view_for(&audio),
            ItemView::MediaFile {
                label: MediaFileLabel::Audio,
                filename: "voice.ogg".into(),
                size: None,
                mimetype: None,
            }
        );

        let mut video = item("message");
        video.msgtype = Some("m.video".into());
        assert_eq!(
            view_for(&video),
            ItemView::MediaFile {
                label: MediaFileLabel::Video,
                filename: "Video".into(),
                size: None,
                mimetype: None,
            }
        );
    }

    #[test]
    fn falls_back_to_a_placeholder_naming_the_msgtype_for_anything_else() {
        let mut it = item("message");
        it.msgtype = Some("m.location".into());
        assert_eq!(
            view_for(&it),
            ItemView::Placeholder {
                text: "Unsupported message (m.location)".into()
            }
        );
    }

    // ---- viewFor: other kinds -------------------------------------------

    #[test]
    fn names_undecryptable_events_specifically_not_generically() {
        let ItemView::Placeholder { text } = view_for(&item("unableToDecrypt")) else {
            panic!("expected a placeholder");
        };
        assert!(
            text.to_lowercase().contains("encrypted"),
            "wording lost its specificity: {text:?}"
        );
    }

    #[test]
    fn renders_redactions_as_a_deletion_tombstone_not_a_blank() {
        assert_eq!(
            view_for(&item("redacted")),
            ItemView::Placeholder {
                text: "Message deleted".into()
            }
        );
    }

    #[test]
    fn renders_nothing_for_m_room_name_the_regression_this_exists_to_prevent() {
        let mut it = item("state");
        it.detail = Some("m.room.name".into());
        assert_eq!(view_for(&it), ItemView::None);
    }

    #[test]
    fn renders_nothing_for_state_events_in_general_by_default() {
        for detail in ["m.room.topic", "m.room.power_levels"] {
            let mut it = item("state");
            it.detail = Some(detail.into());
            assert_eq!(view_for(&it), ItemView::None, "{detail} rendered a row");
        }
    }

    #[test]
    fn surfaces_room_creation_naming_the_creator() {
        let mut it = item("state");
        it.detail = Some("m.room.create".into());
        it.sender_display_name = Some("Alice".into());
        assert_eq!(
            view_for(&it),
            ItemView::System {
                text: "Alice created the room".into()
            }
        );
    }

    #[test]
    fn falls_back_to_the_raw_sender_id_for_room_creation() {
        let mut it = item("state");
        it.detail = Some("m.room.create".into());
        it.sender = Some("@alice:example.org".into());
        assert_eq!(
            view_for(&it),
            ItemView::System {
                text: "@alice:example.org created the room".into()
            }
        );
    }

    #[test]
    fn surfaces_encryption_being_enabled() {
        let mut it = item("state");
        it.detail = Some("m.room.encryption".into());
        let ItemView::System { text } = view_for(&it) else {
            panic!("expected a system line");
        };
        assert!(text.to_lowercase().contains("encryption"), "got {text:?}");
    }

    #[test]
    fn surfaces_a_tombstone_as_a_system_line() {
        let mut it = item("state");
        it.detail = Some("m.room.tombstone".into());
        assert!(matches!(view_for(&it), ItemView::System { .. }));
    }

    #[test]
    fn membership_renders_a_system_line_naming_the_sender_and_the_change() {
        let mut it = item("membership");
        it.detail = Some("joined".into());
        it.sender_display_name = Some("Alice".into());
        assert_eq!(
            view_for(&it),
            ItemView::System {
                text: "Alice joined the room".into()
            }
        );
    }

    #[test]
    fn membership_falls_back_to_the_raw_sender_id() {
        let mut it = item("membership");
        it.detail = Some("left".into());
        it.sender = Some("@bob:example.org".into());
        assert_eq!(
            view_for(&it),
            ItemView::System {
                text: "@bob:example.org left the room".into()
            }
        );
    }

    #[test]
    fn every_membership_verb_reads_as_a_sentence_and_none_falls_through_silently() {
        // The copy is the product here. A verb that silently became the
        // generic fallback would read as a plausible sentence and be wrong.
        let cases = [
            ("joined", "joined the room"),
            ("left", "left the room"),
            ("invited", "was invited"),
            ("banned", "was banned"),
            ("unbanned", "was unbanned"),
            ("kicked", "was removed"),
            ("kickedAndBanned", "was removed and banned"),
            ("invitationAccepted", "accepted the invite"),
            ("invitationRejected", "rejected the invite"),
            ("invitationRevoked", "had their invite revoked"),
            ("knocked", "asked to join"),
            ("knockAccepted", "was let in"),
            ("knockRetracted", "withdrew their request to join"),
            ("knockDenied", "was denied entry"),
        ];
        for (detail, expected) in cases {
            assert_eq!(membership_verb(Some(detail)), expected, "for {detail}");
        }
        assert_eq!(
            membership_verb(Some("somethingNew")),
            "updated their membership"
        );
        assert_eq!(membership_verb(None), "updated their membership");
    }

    #[test]
    fn suppresses_profile_changes_by_default() {
        assert_eq!(view_for(&item("profileChange")), ItemView::None);
    }

    #[test]
    fn renders_a_failed_to_parse_event_naming_the_type() {
        let mut it = item("failedToParse");
        it.detail = Some("m.some.custom".into());
        assert_eq!(
            view_for(&it),
            ItemView::Placeholder {
                text: "Unsupported event (m.some.custom)".into()
            }
        );
    }

    #[test]
    fn renders_the_read_marker_as_the_line_between_read_and_unread() {
        // It used to render nothing, so opening a room with 14 unread dropped
        // you at the bottom with no way to see where they began.
        assert_eq!(view_for(&item("readMarker")), ItemView::UnreadMarker);
    }

    #[test]
    fn renders_timeline_start_as_the_beginning_of_the_room_line() {
        assert_eq!(
            view_for(&item("timelineStart")),
            ItemView::System {
                text: "Beginning of the room".into()
            }
        );
    }

    #[test]
    fn names_stickers_polls_and_calls_as_placeholders_not_silence() {
        for kind in [
            "sticker",
            "poll",
            "liveLocation",
            "callInvite",
            "rtcNotification",
        ] {
            assert!(
                matches!(view_for(&item(kind)), ItemView::Placeholder { .. }),
                "{kind} rendered as something other than a placeholder"
            );
        }
    }

    #[test]
    fn never_returns_an_empty_placeholder_string() {
        // An empty placeholder renders as a bare empty line, which reads as a
        // rendering fault rather than as an unsupported event.
        let kinds = [
            "message",
            "sticker",
            "poll",
            "redacted",
            "unableToDecrypt",
            "liveLocation",
            "callInvite",
            "rtcNotification",
            "failedToParse",
            "customMessage",
        ];
        for kind in kinds {
            if let ItemView::Placeholder { text } = view_for(&item(kind)) {
                assert!(!text.is_empty(), "{kind} produced an empty placeholder");
            }
        }
    }

    #[test]
    fn an_unknown_kind_degrades_to_a_named_placeholder_rather_than_a_panic() {
        assert_eq!(
            view_for(&item("somethingFromTheFuture")),
            ItemView::Placeholder {
                text: "Unsupported event (somethingFromTheFuture)".into()
            }
        );
    }

    #[test]
    fn a_custom_message_is_a_named_placeholder_until_the_registry_lands() {
        // TASK 6: this becomes ItemView::CustomEvent, and the three cases
        // customEvents.test.ts covers — an unregistered type, an unregistered
        // type with a plain-text body, and the shipped demo renderer — arrive
        // with it. Recorded as a test rather than left absent so the gap is
        // visible in the suite instead of only in a plan document.
        let mut it = item("customMessage");
        it.detail = Some("org.kaambaan.card.v1".into());
        assert_eq!(
            view_for(&it),
            ItemView::Placeholder {
                text: "Custom event (org.kaambaan.card.v1)".into()
            }
        );
    }

    // ---- replyQuoteView --------------------------------------------------

    #[test]
    fn reply_quote_is_none_for_an_item_that_is_not_a_reply() {
        assert_eq!(reply_quote_view(None), None);
    }

    #[test]
    fn reply_quote_resolves_the_display_name_falling_back_to_the_sender_id() {
        let mut parent = reply_to();
        parent.sender_display_name = None;
        parent.sender = Some("@bob:example.org".into());
        assert_eq!(
            reply_quote_view(Some(&parent)),
            Some(ReplyQuoteView::Available {
                sender: "@bob:example.org".into(),
                excerpt: Some("the original message".into()),
                label: None,
            })
        );
    }

    #[test]
    fn reply_quote_falls_back_to_a_generic_placeholder_when_nothing_is_known() {
        let mut parent = reply_to();
        parent.sender_display_name = None;
        parent.sender = None;
        assert_eq!(
            reply_quote_view(Some(&parent)),
            Some(ReplyQuoteView::Available {
                sender: "Someone".into(),
                excerpt: Some("the original message".into()),
                label: None,
            })
        );
    }

    #[test]
    fn reply_quote_carries_a_none_excerpt_through() {
        let mut parent = reply_to();
        parent.excerpt = None;
        assert_eq!(
            reply_quote_view(Some(&parent)),
            Some(ReplyQuoteView::Available {
                sender: "Alice".into(),
                excerpt: None,
                label: None,
            })
        );
    }

    #[test]
    fn reply_quote_carries_the_cores_classification_label_through() {
        // Before this, a redacted or sticker reply parent rendered as a bare
        // sender name with no indication why there was nothing to quote.
        let mut parent = reply_to();
        parent.excerpt = None;
        parent.label = Some("Message deleted".into());
        assert_eq!(
            reply_quote_view(Some(&parent)),
            Some(ReplyQuoteView::Available {
                sender: "Alice".into(),
                excerpt: None,
                label: Some("Message deleted".into()),
            })
        );
    }

    #[test]
    fn reply_quote_collapses_every_unavailable_state_to_one_outcome() {
        let mut parent = reply_to();
        parent.available = false;
        parent.sender = None;
        parent.sender_display_name = None;
        parent.excerpt = None;
        assert_eq!(
            reply_quote_view(Some(&parent)),
            Some(ReplyQuoteView::Unavailable)
        );
    }

    // ---- canReplyOrReact -------------------------------------------------

    #[test]
    fn can_reply_to_an_ordinary_received_message() {
        assert!(can_reply_or_react(&item("message")));
    }

    #[test]
    fn can_reply_to_a_message_the_server_has_echoed_back() {
        let mut it = item("message");
        it.send_state = Some("sent".into());
        assert!(can_reply_or_react(&it));
    }

    #[test]
    fn cannot_reply_to_a_message_still_only_a_local_echo() {
        let mut it = item("message");
        it.send_state = Some("notSentYet".into());
        assert!(!can_reply_or_react(&it));
    }

    #[test]
    fn cannot_reply_to_a_message_whose_send_failed() {
        let mut it = item("message");
        it.send_state = Some("sendingFailed".into());
        assert!(!can_reply_or_react(&it));
    }

    // ---- replyPreviewExcerpt --------------------------------------------

    #[test]
    fn reply_preview_is_none_for_a_missing_or_whitespace_only_body() {
        assert_eq!(reply_preview_excerpt(None), None);
        assert_eq!(reply_preview_excerpt(Some("   ")), None);
        assert_eq!(reply_preview_excerpt(Some("")), None);
    }

    #[test]
    fn reply_preview_trims_surrounding_whitespace() {
        assert_eq!(
            reply_preview_excerpt(Some("  hello there  ")).as_deref(),
            Some("hello there")
        );
    }

    #[test]
    fn reply_preview_caps_a_long_body_with_an_ellipsis() {
        let long = "x".repeat(500);
        let preview = reply_preview_excerpt(Some(&long)).expect("a long body previews");
        assert!(preview.chars().count() < long.chars().count());
        assert!(preview.ends_with('…'), "no ellipsis on {preview:?}");
        assert_eq!(preview.chars().count(), REPLY_PREVIEW_MAX_CHARS + 1);
    }

    #[test]
    fn reply_preview_cuts_on_a_character_boundary_not_a_byte_one() {
        // The Rust form of the surrogate-pair hazard the TypeScript guarded:
        // `&s[..140]` on multi-byte characters panics outright.
        let long = "🎉".repeat(500);
        let preview = reply_preview_excerpt(Some(&long)).expect("previews");
        let kept = preview.trim_end_matches('…');
        assert_eq!(kept.chars().count(), REPLY_PREVIEW_MAX_CHARS);
        assert!(kept.chars().all(|c| c == '🎉'), "a character was split");
    }

    // ---- displayReactionKey ---------------------------------------------

    #[test]
    fn leaves_a_short_reaction_key_untouched() {
        assert_eq!(display_reaction_key("👍"), "👍");
    }

    #[test]
    fn caps_a_long_space_free_reaction_key_with_an_ellipsis() {
        let long = "x".repeat(100);
        let displayed = display_reaction_key(&long);
        assert!(displayed.chars().count() < long.chars().count());
        assert!(displayed.ends_with('…'));
    }

    #[test]
    fn caps_a_reaction_key_by_code_point_not_by_byte() {
        let long = "🎉".repeat(40);
        let displayed = display_reaction_key(&long);
        assert!(displayed.ends_with('…'));
        let kept = displayed.trim_end_matches('…');
        assert_eq!(kept.chars().count(), REACTION_KEY_MAX_CHARS);
        assert!(kept.chars().all(|c| c == '🎉'));
    }

    // ---- displayEventType ------------------------------------------------

    #[test]
    fn leaves_a_normal_reverse_dns_type_untouched() {
        assert_eq!(
            display_event_type(Some("dev.supermessage.demo.note.v1")),
            "dev.supermessage.demo.note.v1"
        );
    }

    #[test]
    fn truncates_an_event_type_from_the_left_keeping_the_informative_tail() {
        let event_type = format!(
            "org.example.{}permission.request.v1",
            "namespace.".repeat(20)
        );
        let displayed = display_event_type(Some(&event_type));
        assert!(
            displayed.starts_with('…'),
            "no leading ellipsis: {displayed:?}"
        );
        assert!(
            displayed.ends_with("permission.request.v1"),
            "the informative tail was cut: {displayed:?}"
        );
        // The regression this guards: ordinary right-truncation would keep the
        // shared namespace prefix and throw away the only part that names the
        // event.
        assert!(!displayed.ends_with('…'));
        assert!(!displayed.starts_with("org.example."));
    }

    #[test]
    fn caps_the_rendered_event_type_length() {
        let displayed = display_event_type(Some(&"a".repeat(500)));
        // 48 kept code points plus the one-character leading ellipsis.
        assert_eq!(displayed.chars().count(), EVENT_TYPE_MAX_CHARS + 1);
    }

    #[test]
    fn caps_an_event_type_by_code_point_not_by_byte() {
        // A Matrix event type is sender-controlled and need not be ASCII.
        let displayed = display_event_type(Some(&"🎉".repeat(80)));
        let kept = displayed.trim_start_matches('…');
        assert_eq!(kept.chars().count(), EVENT_TYPE_MAX_CHARS);
        assert!(kept.chars().all(|c| c == '🎉'));
    }

    #[test]
    fn degrades_a_missing_empty_or_whitespace_event_type_to_unknown() {
        assert_eq!(display_event_type(None), "unknown");
        assert_eq!(display_event_type(Some("")), "unknown");
        assert_eq!(display_event_type(Some("   ")), "unknown");
    }

    #[test]
    fn trims_surrounding_whitespace_from_an_event_type() {
        assert_eq!(
            display_event_type(Some("  dev.supermessage.demo.note.v1  ")),
            "dev.supermessage.demo.note.v1"
        );
    }

    // ---- attributedName --------------------------------------------------

    #[test]
    fn attributed_name_prefers_the_display_name_then_the_id_then_a_placeholder() {
        let mut it = item("message");
        it.sender_display_name = Some("Alice".into());
        assert_eq!(attributed_name(&it), "Alice");

        it.sender_display_name = None;
        assert_eq!(attributed_name(&it), "@someone:example.org");

        it.sender = None;
        assert_eq!(attributed_name(&it), "Someone");
    }
}
