//! Which of the seven person colours a user is drawn in.
//!
//! One colour per person, for their name and the bar on a quote of them, is
//! what tells "Strategy Sam" from "Strategy Sam (Hermes on Guild)" at a
//! glance in a room full of agents (Telegram's peer colours, 2026-09-24).
//! The colours themselves are tokens (`[peer.*]` in `design/tokens.toml`,
//! each checked as text on every ground); this decides only the index.
//!
//! **The core decides, so every device agrees.** A host-side hash would be
//! four implementations of one rule; the desktop, which cannot call the core
//! per row, carries a port pinned to the same test vectors
//! (`src/lib/peerColor.ts`).

/// How many person colours there are. `scripts/tokens/model.py` asserts the
/// token file has exactly this many per appearance.
pub const PEER_COUNT: u8 = 7;

/// The colour index for `user_id`: 32-bit FNV-1a over its UTF-8 bytes,
/// modulo [`PEER_COUNT`]. Stable across devices, sessions and releases —
/// changing it would recolour everyone.
pub fn peer_color_index(user_id: &str) -> u8 {
    let mut hash: u32 = 0x811c_9dc5;
    for byte in user_id.as_bytes() {
        hash ^= u32::from(*byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    (hash % u32::from(PEER_COUNT)) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pinned_vectors_that_the_desktop_port_shares() {
        // src/lib/peerColor.test.ts asserts the same five. Change one and
        // the other must change with it, or the desktop recolours people.
        assert_eq!(peer_color_index("@agent_krishna:id.agentpod.dev"), 2);
        assert_eq!(peer_color_index("@agent_strategy-sam:id.agentpod.dev"), 3);
        assert_eq!(peer_color_index("@strategy-sam:guild.example.org"), 5);
        assert_eq!(peer_color_index("@rakesh:id.agentpod.dev"), 5);
        assert_eq!(peer_color_index(""), 2);
    }

    #[test]
    fn the_two_strategy_sams_are_told_apart() {
        // The case this exists for: the agent and its bridge identity.
        assert_ne!(
            peer_color_index("@agent_strategy-sam:id.agentpod.dev"),
            peer_color_index("@strategy-sam:guild.example.org")
        );
    }

    #[test]
    fn always_in_range() {
        for i in 0..500 {
            assert!(peer_color_index(&format!("@user{i}:example.org")) < PEER_COUNT);
        }
    }
}
