//! The generated tile an agent's room wears when it has no picture.
//!
//! **Deterministic from the room id, and only from it.** A fleet of agents
//! that all draw the same grey circle is a list a reader has to read; one
//! where Atlas is always the same tile is a list they can recognise. The id
//! is the one thing about a room that never changes — a rename must not
//! repaint it — and the hash is FNV-1a rather than a per-process seeded
//! hasher, so the same room does not change colour on every launch.
//!
//! **Palette roles only.** The core names a tile in roles the host resolves
//! through its theme. `signal` is not among them and cannot be: amber means a
//! pending decision and only the decision card draws it. `danger` is left out
//! for the same kind of reason — a red face reads as an error before it reads
//! as a name.
//!
//! Moved here from `AgentAvatarStyle.swift` so that every host draws the same
//! tile for the same room.

use serde::{Deserialize, Serialize};

/// A palette role a tile is drawn in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum AvatarRole {
    Accent,
    AccentSoft,
    AccentContent,
    Ok,
    ContentMuted,
    Surface,
    SurfaceRaised,
}

/// One tile: what it is filled with, the ink drawn on it, and which of the
/// [`VARIANTS`] it is.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct AgentAvatarStyle {
    /// What the tile is filled with.
    pub ground: AvatarRole,
    /// The glyph or initial drawn on it.
    pub ink: AvatarRole,
    /// Index into [`VARIANTS`].
    pub variant: u8,
}

/// Every pairing, each chosen so the ink clears the ground in both
/// appearances (`accentContent` is defined as what is legible on `accent`;
/// the others pair a light role with a dark one in paper and the reverse in
/// dark). The index is the style's `variant`; the order is part of the rule.
pub const VARIANTS: [(AvatarRole, AvatarRole); 6] = [
    (AvatarRole::Accent, AvatarRole::AccentContent),
    (AvatarRole::AccentSoft, AvatarRole::Accent),
    (AvatarRole::Ok, AvatarRole::Surface),
    (AvatarRole::SurfaceRaised, AvatarRole::Accent),
    (AvatarRole::ContentMuted, AvatarRole::Surface),
    (AvatarRole::SurfaceRaised, AvatarRole::Ok),
];

/// 64-bit FNV-1a over the UTF-8 bytes of `text`.
pub fn fnv1a64(text: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

fn style_at(variant: usize) -> AgentAvatarStyle {
    let (ground, ink) = VARIANTS[variant];
    AgentAvatarStyle {
        ground,
        ink,
        variant: variant as u8,
    }
}

/// The tile for the room `room_id`.
pub fn style_for_room(room_id: &str) -> AgentAvatarStyle {
    style_at((fnv1a64(room_id) % VARIANTS.len() as u64) as usize)
}

/// Every tile, in `variant` order.
pub fn variants() -> Vec<AgentAvatarStyle> {
    (0..VARIANTS.len()).map(style_at).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    /// Ported from `RosterViewsTests.avatarIsDeterministic`. Pinned values,
    /// not just "equal to itself": a per-process hasher would pass a
    /// same-process equality check and still repaint every room on the next
    /// launch.
    ///
    /// Mutation seen failing: changing the FNV prime's last digit, and
    /// replacing the multiply with an add.
    #[test]
    fn a_rooms_tile_is_the_same_every_time_it_is_asked_for() {
        assert_eq!(fnv1a64(""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(fnv1a64("a"), 0xaf63_dc4c_8601_ec8c);
        assert_eq!(
            style_for_room("!atlas:example.org"),
            style_for_room("!atlas:example.org")
        );
    }

    /// Ported from `RosterViewsTests.avatarSpreads`.
    ///
    /// Mutation seen failing: `% 5` instead of `% 6`.
    #[test]
    fn tiles_spread_across_the_palette_rather_than_collapsing_onto_one() {
        let used: HashSet<u8> = (0..60)
            .map(|i| style_for_room(&format!("!room{i}:example.org")).variant)
            .collect();
        assert_eq!(used.len(), VARIANTS.len());
    }

    /// Ported from `RosterViewsTests.avatarPalette`.
    ///
    /// Mutation seen failing: replacing the last pairing with `(Ok, Ok)`.
    #[test]
    fn no_tile_draws_amber_or_red_and_no_tile_is_ink_on_its_own_ground() {
        for style in variants() {
            assert_ne!(style.ground, style.ink);
        }
        let all = [
            AvatarRole::Accent,
            AvatarRole::AccentSoft,
            AvatarRole::AccentContent,
            AvatarRole::Ok,
            AvatarRole::ContentMuted,
            AvatarRole::Surface,
            AvatarRole::SurfaceRaised,
        ];
        for role in all {
            let name = serde_json::to_string(&role).unwrap();
            assert_ne!(name, "\"signal\"");
            assert_ne!(name, "\"danger\"");
        }
        assert_eq!(
            serde_json::to_string(&AvatarRole::AccentSoft).unwrap(),
            "\"accentSoft\""
        );
    }

    /// Pinned so a later port (or a reordering of [`VARIANTS`]) cannot drift
    /// from the Swift original.
    ///
    /// Mutation seen failing: swapping variants 3 and 5 in `VARIANTS`.
    #[test]
    fn known_rooms_keep_their_tiles() {
        let pin = |id: &str, variant: u8, ground, ink| {
            assert_eq!(
                style_for_room(id),
                AgentAvatarStyle {
                    ground,
                    ink,
                    variant
                },
                "{id}"
            );
        };
        pin(
            "!atlas:example.org",
            3,
            AvatarRole::SurfaceRaised,
            AvatarRole::Accent,
        );
        pin(
            "!quill:example.org",
            5,
            AvatarRole::SurfaceRaised,
            AvatarRole::Ok,
        );
        pin(
            "!room0:example.org",
            1,
            AvatarRole::AccentSoft,
            AvatarRole::Accent,
        );
        pin(
            "!room1:example.org",
            4,
            AvatarRole::ContentMuted,
            AvatarRole::Surface,
        );
        assert_eq!(style_for_room("demo.atlas").variant, 5);
    }
}
