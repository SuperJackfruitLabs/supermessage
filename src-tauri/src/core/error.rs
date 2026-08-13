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
}
