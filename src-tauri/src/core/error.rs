//! The error type crossing the IPC boundary.
//!
//! Variants exist so the webview can branch on failure kind (a wrong password
//! versus an unreachable server) without parsing prose.

// `Network` and `Protocol` are still not constructed anywhere: `secrets`
// (Task 3) is the first real consumer of `CoreError`/`CoreResult`, via
// `CoreError::Store`, but `secrets` itself has no caller yet (that lands with
// login in a later M0 task), so rustc's dead-code analysis can't see past it
// either. Revisit once login wires `core::secrets` in.
#![allow(dead_code)]

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
}
