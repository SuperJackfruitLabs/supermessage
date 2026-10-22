//! The rooms this account has sent in since signing in.
//!
//! The acknowledgement dock says "Sent to Atlas" only after the reader has
//! written something; a room they have merely opened shows nothing. That fact
//! is "this session's", not the room's: it does not survive signing out, and
//! it does not survive leaving the room. The core owns it so every host
//! answers the same way (ruling 12e).

use std::collections::HashSet;
use std::sync::Mutex;

#[derive(Default)]
pub struct SentRooms(Mutex<HashSet<String>>);

impl SentRooms {
    /// This account has sent in `room_id`.
    pub fn note(&self, room_id: &str) {
        self.0
            .lock()
            .expect("sent rooms lock poisoned")
            .insert(room_id.to_owned());
    }

    pub fn has(&self, room_id: &str) -> bool {
        self.0
            .lock()
            .expect("sent rooms lock poisoned")
            .contains(room_id)
    }

    /// Forget one room (the account left it).
    pub fn forget(&self, room_id: &str) {
        self.0
            .lock()
            .expect("sent rooms lock poisoned")
            .remove(room_id);
    }

    /// Forget every room (the account signed out).
    pub fn clear(&self) {
        self.0.lock().expect("sent rooms lock poisoned").clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Ported from `TypingCastTests` "a send is remembered per room".
    ///
    /// Mutation seen failing: `has` ignoring the room id (returning
    /// `!set.is_empty()`) fails the `!other:x` assertion.
    #[test]
    fn a_send_is_remembered_per_room() {
        let sent = SentRooms::default();
        assert!(!sent.has("!r:x"));
        sent.note("!r:x");
        assert!(sent.has("!r:x"));
        assert!(!sent.has("!other:x"));
    }

    /// Mutation seen failing: `forget` made a no-op fails the final assertion.
    #[test]
    fn leaving_a_room_forgets_it() {
        let sent = SentRooms::default();
        sent.note("!r:x");
        sent.note("!keep:x");
        sent.forget("!r:x");
        assert!(!sent.has("!r:x"));
        assert!(sent.has("!keep:x"));
    }
}
