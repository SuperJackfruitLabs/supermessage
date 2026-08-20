//! What a room's membership lets the reader do.
//!
//! Ported from `$lib/components/invitationView.ts`. Small, and shared because
//! the mapping is a statement about Matrix membership rather than a styling
//! choice: offering a composer for a room you have not joined produces a send
//! that fails at the homeserver, and offering nothing where an invitation is
//! waiting hides the only action there is.
//!
//! The **copy stays out**. `invitationPrompt` interpolates a room name into a
//! sentence and `INVITATION_EMPTY_TIMELINE` explains an empty pane; both are
//! user-visible wording a host renders inline, and a host cannot await
//! mid-render for a format string. Two clients wording them differently is a
//! design difference a person can see and fix, not a correctness bug — which
//! is the line this whole migration is drawn on.

use crate::dto::Membership;

/// What the room pane should offer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum RoomAffordance {
    /// An ordinary room: write in it.
    Compose,
    /// An invitation: accept or decline.
    RespondToInvitation,
    /// Nothing honest to offer, and inventing an affordance for a state
    /// nobody can act on is worse than a quiet pane.
    Nothing,
}

pub fn room_affordance(membership: Membership) -> RoomAffordance {
    match membership {
        Membership::Joined => RoomAffordance::Compose,
        Membership::Invited => RoomAffordance::RespondToInvitation,
        // `Left` is reachable in the window between leaving a room and the
        // roster's next diff dropping it; `Knocked` and `Banned` are states
        // the SDK can report and this client has no flow for.
        Membership::Left | Membership::Knocked | Membership::Banned => RoomAffordance::Nothing,
    }
}

/// Whether a roster row should read as an invitation rather than a room.
pub fn is_invitation(membership: Membership) -> bool {
    matches!(membership, Membership::Invited)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_joined_room_offers_a_composer() {
        assert_eq!(room_affordance(Membership::Joined), RoomAffordance::Compose);
    }

    #[test]
    fn an_invitation_offers_a_response_not_a_composer() {
        // Offering a composer here produces a send that fails at the
        // homeserver, which reads as the app being broken.
        assert_eq!(
            room_affordance(Membership::Invited),
            RoomAffordance::RespondToInvitation
        );
    }

    #[test]
    fn every_other_membership_offers_nothing() {
        for membership in [Membership::Left, Membership::Knocked, Membership::Banned] {
            assert_eq!(
                room_affordance(membership),
                RoomAffordance::Nothing,
                "for {membership:?}"
            );
        }
    }

    #[test]
    fn only_an_invitation_reads_as_one() {
        assert!(is_invitation(Membership::Invited));
        for membership in [
            Membership::Joined,
            Membership::Left,
            Membership::Knocked,
            Membership::Banned,
        ] {
            assert!(!is_invitation(membership), "for {membership:?}");
        }
    }
}
