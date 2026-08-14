//! The error type crossing the IPC boundary.
//!
//! Variants exist so the webview can branch on failure kind (a wrong password
//! versus an unreachable server) without parsing prose.

use serde::ser::SerializeStruct;
use serde::{Serialize, Serializer};

#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error("{0}")]
    Auth(String),
    #[error("{0}")]
    Network(String),
    #[error("{0}")]
    Store(String),
    #[error("{0}")]
    Protocol(String),
    #[error("the core is not ready yet")]
    NotReady,
    /// A room-scoped command (`send_message`, `send_reply`,
    /// `toggle_reaction`, `timeline_paginate_back`) named `requested`, but
    /// `focused` was the room actually installed in `FocusedTimeline` at the
    /// moment the command ran — the caller lost a race against a room
    /// switch. **The command did not act** — see
    /// `FocusedTimeline::active_timeline_for`'s doc comment for why this is
    /// distinct from every other variant here: it is not a failure of the
    /// underlying operation (auth/network/store/protocol all describe *that
    /// something the caller asked for went wrong*), it is a refusal to
    /// perform an operation the caller no longer means. The webview branches
    /// on `kind()` to show this differently from a generic failure — a send
    /// that silently lands in the wrong room is worse than one that visibly
    /// didn't go through at all.
    #[error("wrong room: requested {requested}, but {focused} is now focused")]
    RoomChanged { requested: String, focused: String },
    /// A file was refused before being read because it is bigger than the
    /// homeserver will accept — `core::attachments::check_upload_size`, the
    /// enforcement point for the attachments design's §4.
    ///
    /// Its own variant, rather than a `Store`/`Protocol` string, for the
    /// reason that section gives: "upload failed" is not something a reader
    /// can act on. Both numbers are on the wire so the webview can say *this
    /// file is 214.7 MiB, the limit is 50.0 MiB* — which tells the reader to
    /// send a link instead, where "failed" would only tell them to try
    /// again. The message renders them in binary units
    /// (`core::attachments::format_bytes`) because homeserver limits are
    /// powers of two and a decimal "52.4 MB" against a "50 MB" limit reads
    /// as a contradiction.
    #[error("that file is {}, but this homeserver accepts at most {}", crate::core::attachments::format_bytes(*bytes), crate::core::attachments::format_bytes(*limit))]
    AttachmentTooLarge { bytes: u64, limit: u64 },
    /// A staging token named no staged file: it was already sent (they are
    /// single use), discarded, swept by the staging timeout, or dropped
    /// because the reader switched rooms or logged out. See
    /// `core::attachments::StagedAttachments`.
    ///
    /// Distinct from [`Self::RoomChanged`] on purpose. `RoomChanged` means
    /// *the file is still staged, but not for the room you named* and is
    /// recoverable by switching back; this one means there is nothing left
    /// to send and the reader has to pick the file again. Both are refusals
    /// rather than failures — nothing was sent either way.
    #[error("that file is no longer staged; pick it again")]
    UnknownAttachment,
    /// `space_select` named a room that is not a space this account has
    /// joined: left since the rail was last fetched, never joined, or simply
    /// not a space. See `core::spaces::SpaceIndex::rooms_in`.
    ///
    /// A refusal, not a failure — nothing about the roster changed. Its own
    /// variant so the frontend can branch: the right response is to re-fetch
    /// `spaces_list` and move its own selection back to "All rooms", which
    /// is a different reaction from the one a generic `protocol` error would
    /// get. Silently widening the roster core-side instead would leave the
    /// rail highlighting a space that no longer exists while showing every
    /// room in the account underneath it.
    #[error("no joined space with id {space_id}")]
    UnknownSpace { space_id: String },
}

pub type CoreResult<T> = Result<T, CoreError>;

impl CoreError {
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Auth(_) => "auth",
            Self::Network(_) => "network",
            Self::Store(_) => "store",
            Self::Protocol(_) => "protocol",
            Self::NotReady => "notReady",
            Self::RoomChanged { .. } => "roomChanged",
            Self::AttachmentTooLarge { .. } => "attachmentTooLarge",
            Self::UnknownAttachment => "unknownAttachment",
            Self::UnknownSpace { .. } => "unknownSpace",
        }
    }
}

impl Serialize for CoreError {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut s = serializer.serialize_struct("CoreError", 2)?;
        s.serialize_field("kind", self.kind())?;
        s.serialize_field("message", &self.to_string())?;
        s.end()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_with_a_machine_readable_kind() {
        let json = serde_json::to_value(CoreError::Auth("bad password".into())).unwrap();
        assert_eq!(json["kind"], "auth");
        assert_eq!(json["message"], "bad password");
    }

    #[test]
    fn not_ready_still_carries_a_kind_and_message() {
        let json = serde_json::to_value(CoreError::NotReady).unwrap();
        assert_eq!(json["kind"], "notReady");
        assert!(json["message"].as_str().is_some_and(|m| !m.is_empty()));
    }

    #[test]
    fn room_changed_serializes_with_its_own_kind_and_names_both_rooms() {
        let json = serde_json::to_value(CoreError::RoomChanged {
            requested: "!a:x.org".into(),
            focused: "!b:x.org".into(),
        })
        .unwrap();
        assert_eq!(json["kind"], "roomChanged");
        let message = json["message"].as_str().unwrap();
        assert!(message.contains("!a:x.org"));
        assert!(message.contains("!b:x.org"));
    }

    #[test]
    fn attachment_too_large_serializes_with_its_own_kind_and_names_both_sizes() {
        let json = serde_json::to_value(CoreError::AttachmentTooLarge {
            bytes: 200 * 1024 * 1024,
            limit: 50 * 1024 * 1024,
        })
        .unwrap();
        assert_eq!(json["kind"], "attachmentTooLarge");
        let message = json["message"].as_str().unwrap();
        assert!(message.contains("200.0 MiB"), "got {message}");
        assert!(message.contains("50.0 MiB"), "got {message}");
    }

    #[test]
    fn unknown_attachment_serializes_with_its_own_kind() {
        let json = serde_json::to_value(CoreError::UnknownAttachment).unwrap();
        assert_eq!(json["kind"], "unknownAttachment");
        assert!(json["message"].as_str().is_some_and(|m| !m.is_empty()));
    }

    #[test]
    fn unknown_space_serializes_with_its_own_kind_and_names_the_space() {
        let json = serde_json::to_value(CoreError::UnknownSpace {
            space_id: "!gone:x.org".into(),
        })
        .unwrap();
        assert_eq!(json["kind"], "unknownSpace");
        assert!(json["message"].as_str().unwrap().contains("!gone:x.org"));
    }
}
