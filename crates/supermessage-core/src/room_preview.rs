//! The roster row's preview line.
//!
//! Ported from `$lib/components/roomPreview.ts`. It composes one line out of
//! facts the core already resolved — never a placeholder, and never a sender
//! prefix except your own.
//!
//! It moved because of one branch: the pending-decision line is the roster's
//! **amber switch**, and amber means the operator owes someone an answer and
//! nothing else. Which event types trip it is a contract shared with Kaambaan,
//! not a per-platform styling choice.

use std::collections::HashSet;

use crate::dto::RoomSummary;

/// Event types whose arrival means a decision is pending.
///
/// **Empty, and it must stay empty** until a schema actually carries a
/// decision. An entry here puts amber on a roster row, and there is no gate
/// schema to send yet — see `custom_events` for the versioning contract that
/// will define one.
pub fn decision_bearing_event_types() -> &'static HashSet<String> {
    static TYPES: std::sync::OnceLock<HashSet<String>> = std::sync::OnceLock::new();
    TYPES.get_or_init(HashSet::new)
}

/// The one string the pending path renders.
///
/// Fixed, not derived from the event: a gate's own prose is untrusted and
/// unbounded, and a roster line has room for neither.
const APPROVAL_NEEDED: &str = "Approval needed";

/// The prefix for a message this account sent.
const OWN_PREFIX: &str = "You: ";

/// The facts a preview is composed from.
///
/// Taken as its own struct rather than a whole room, so the mechanism can be
/// tested against a fixture without constructing a roster entry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RoomPreviewFacts<'a> {
    pub last_message: Option<&'a str>,
    pub last_message_is_own: bool,
    pub last_message_names_sender: bool,
    pub last_event_type: Option<&'a str>,
}

/// A composed preview line.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RoomPreview {
    /// Ready to render as text. Never empty.
    pub text: String,
    /// Whether this is the pending-decision line rather than a message
    /// preview — the row's amber switch.
    ///
    /// `true` only ever means "the operator owes someone an answer". It is not
    /// a severity, a warning, or an error.
    pub pending: bool,
}

/// Build the preview line, or `None` when the row must omit it entirely.
///
/// There is no placeholder string: a row with nothing to say says nothing.
///
/// `decision_types` is a parameter rather than a global read so a caller names
/// the set it is trusting, and so a test can prove the mechanism against a
/// fixture type without the production set ever gaining an entry.
///
/// **The pending check comes first, and deliberately ignores `last_message`.**
/// "Approval needed" *replaces* whatever the event's text would have been
/// rather than competing with it, so a gate can never leak its own body onto
/// the roster — and the amber cannot be suppressed by a preview that happened
/// to come back empty.
pub fn compose_room_preview(
    facts: &RoomPreviewFacts<'_>,
    decision_types: &HashSet<String>,
) -> Option<RoomPreview> {
    if let Some(event_type) = facts.last_event_type {
        if decision_types.contains(event_type) {
            return Some(RoomPreview {
                text: APPROVAL_NEEDED.to_string(),
                pending: true,
            });
        }
    }

    // The core already collapses whitespace and returns `None` rather than an
    // empty string, so this trim is defence in depth rather than a second
    // opinion: it is what stops a blank preview rendering as a bare "You: ",
    // a line that would look like a bug and say nothing.
    let message = facts.last_message?;
    if message.trim().is_empty() {
        return None;
    }

    let prefix = if facts.last_message_is_own && !facts.last_message_names_sender {
        OWN_PREFIX
    } else {
        ""
    };
    Some(RoomPreview {
        text: format!("{prefix}{message}"),
        pending: false,
    })
}

/// The preview for a room, against the production decision set.
pub fn room_preview(room: &RoomSummary) -> Option<RoomPreview> {
    compose_room_preview(
        &RoomPreviewFacts {
            last_message: room.last_message.as_deref(),
            last_message_is_own: room.last_message_is_own,
            last_message_names_sender: room.last_message_names_sender,
            last_event_type: room.last_event_type.as_deref(),
        },
        decision_bearing_event_types(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn facts<'a>(last_message: Option<&'a str>) -> RoomPreviewFacts<'a> {
        RoomPreviewFacts {
            last_message,
            last_message_is_own: false,
            last_message_names_sender: false,
            last_event_type: None,
        }
    }

    fn none() -> HashSet<String> {
        HashSet::new()
    }

    fn gates() -> HashSet<String> {
        HashSet::from(["org.example.gate.v1".to_string()])
    }

    #[test]
    fn shows_the_last_message() {
        assert_eq!(
            compose_room_preview(&facts(Some("hello there")), &none()),
            Some(RoomPreview {
                text: "hello there".into(),
                pending: false
            })
        );
    }

    #[test]
    fn prefixes_your_own_message() {
        let mut f = facts(Some("on it"));
        f.last_message_is_own = true;
        assert_eq!(
            compose_room_preview(&f, &none()).expect("a preview").text,
            "You: on it"
        );
    }

    #[test]
    fn does_not_double_name_a_sender_the_preview_already_names() {
        // An own emote already reads as a sentence about its sender, so the
        // prefix would render "You: <MyName> waves".
        let mut f = facts(Some("Rakesh waves"));
        f.last_message_is_own = true;
        f.last_message_names_sender = true;
        assert_eq!(
            compose_room_preview(&f, &none()).expect("a preview").text,
            "Rakesh waves"
        );
    }

    #[test]
    fn omits_the_line_entirely_when_there_is_nothing_to_show() {
        // No placeholder string: a row with nothing to say says nothing.
        assert_eq!(compose_room_preview(&facts(None), &none()), None);
        assert_eq!(compose_room_preview(&facts(Some("")), &none()), None);
        assert_eq!(compose_room_preview(&facts(Some("   ")), &none()), None);
    }

    #[test]
    fn a_blank_own_message_does_not_render_as_a_bare_prefix() {
        // The failure this guards is specific: "You: " alone, which looks like
        // a bug and says nothing.
        let mut f = facts(Some("   "));
        f.last_message_is_own = true;
        assert_eq!(compose_room_preview(&f, &none()), None);
    }

    #[test]
    fn a_decision_bearing_event_type_turns_the_row_amber() {
        let mut f = facts(Some("some gate prose"));
        f.last_event_type = Some("org.example.gate.v1");
        assert_eq!(
            compose_room_preview(&f, &gates()),
            Some(RoomPreview {
                text: "Approval needed".into(),
                pending: true
            })
        );
    }

    #[test]
    fn the_pending_line_replaces_the_events_own_text_rather_than_competing() {
        // A gate's prose is untrusted and unbounded; it must never reach the
        // roster.
        let mut f = facts(Some("PLEASE APPROVE ME urgently, click here"));
        f.last_event_type = Some("org.example.gate.v1");
        let preview = compose_room_preview(&f, &gates()).expect("a preview");
        assert_eq!(preview.text, "Approval needed");
        assert!(!preview.text.contains("urgently"));
    }

    #[test]
    fn the_amber_survives_a_preview_that_came_back_empty() {
        // The check comes first for exactly this: a decision must not be
        // suppressed by a message preview that happened to be blank.
        let mut f = facts(None);
        f.last_event_type = Some("org.example.gate.v1");
        assert!(
            compose_room_preview(&f, &gates())
                .expect("a preview")
                .pending
        );
    }

    #[test]
    fn an_ordinary_event_type_is_not_a_decision() {
        let mut f = facts(Some("hello"));
        f.last_event_type = Some("m.room.message");
        let preview = compose_room_preview(&f, &gates()).expect("a preview");
        assert!(!preview.pending);
        assert_eq!(preview.text, "hello");
    }

    #[test]
    fn the_production_decision_set_is_empty_and_must_stay_empty() {
        // An entry here puts amber on a roster row, and amber means one thing.
        // There is no gate schema to send yet.
        assert!(
            decision_bearing_event_types().is_empty(),
            "a decision-bearing type was added without a schema to carry it"
        );
    }
}
