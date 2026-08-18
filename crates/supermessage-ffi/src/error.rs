//! What the core's failures look like to Swift and Kotlin.
//!
//! `CoreError` carries source errors — an SDK error, an IO error — which
//! cannot cross an FFI boundary. This mirrors its variants and keeps the
//! message, so a host gets a typed `catch` with something readable in it
//! rather than a string it has to parse.
//!
//! This mirroring is justified in a way that mirroring the DTOs was not: the
//! types genuinely differ, and the difference is forced by the boundary.

use supermessage_core::error::CoreError;

/// A failure the host can act on.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    /// Credentials were refused, or the session is no longer valid. The host
    /// should return to its sign-in screen.
    #[error("{message}")]
    Auth { message: String },

    /// The homeserver could not be reached. Worth retrying.
    #[error("{message}")]
    Network { message: String },

    /// The credential store or the local database refused. On iOS this is
    /// also what a locked device looks like — the Data Protection keychain is
    /// unreadable until first unlock, which is a state to wait out rather
    /// than an error to report.
    #[error("{message}")]
    Store { message: String },

    /// Anything else: a malformed response, an unexpected state.
    #[error("{message}")]
    Protocol { message: String },

    /// There is no session yet. The host asked for something that needs one.
    #[error("no session is running")]
    NotReady,

    /// The focused room moved while the request was in flight. Not a failure
    /// of the request so much as of its timing — the host may reissue it
    /// against the room now focused.
    #[error("expected room {requested}, but {focused} is focused")]
    RoomChanged { requested: String, focused: String },

    /// The file is larger than the server will take. Carries both numbers so
    /// a host can say "12MB, limit 10MB" rather than "too large".
    #[error("attachment is {bytes} bytes, over the {limit} byte limit")]
    AttachmentTooLarge { bytes: u64, limit: u64 },

    /// The staged attachment token is unknown — already sent, already
    /// discarded, or from a previous run.
    #[error("no such staged attachment")]
    UnknownAttachment,

    /// The space id is not one this account is in.
    #[error("no such space: {space_id}")]
    UnknownSpace { space_id: String },
}

impl From<CoreError> for FfiError {
    fn from(error: CoreError) -> Self {
        // Exhaustive, so a new variant in the core has to be considered here
        // rather than collapsing into `Protocol` by default.
        match error {
            CoreError::Auth(message) => Self::Auth { message },
            CoreError::Network(message) => Self::Network { message },
            CoreError::Store(message) => Self::Store { message },
            CoreError::Protocol(message) => Self::Protocol { message },
            CoreError::NotReady => Self::NotReady,
            CoreError::RoomChanged { requested, focused } => {
                Self::RoomChanged { requested, focused }
            }
            CoreError::AttachmentTooLarge { bytes, limit } => {
                Self::AttachmentTooLarge { bytes, limit }
            }
            CoreError::UnknownAttachment => Self::UnknownAttachment,
            CoreError::UnknownSpace { space_id } => Self::UnknownSpace { space_id },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_variant_keeps_its_kind_and_message() {
        // The kind is what a host branches on — a locked keychain should not
        // send someone back to a login screen.
        let auth: FfiError = CoreError::Auth("bad password".into()).into();
        assert!(matches!(auth, FfiError::Auth { .. }));
        assert_eq!(auth.to_string(), "bad password");

        let store: FfiError = CoreError::Store("keychain locked".into()).into();
        assert!(matches!(store, FfiError::Store { .. }));
        assert_eq!(store.to_string(), "keychain locked");
    }
}
