// What a room's membership means for what the room pane offers.
//
// The roster is filtered with `new_filter_non_left`, so an invitation is
// listed exactly like a joined room, and until `RoomSummary.membership`
// existed the webview could not tell them apart. That is issue #1: AgentPod's
// Application Service provisions one room per station and invites the
// operator to each, so every agent room arrives as an invitation — and the
// client built for rooms whose other occupants are agents could not enter a
// single one of them. Element could; this could not.
//
// The decision lives here rather than inline in the components for the same
// reason `core::room_preview` and `core::item_view` exist: it is a rule about
// what the operator is offered, it has more than one case, and a rule with
// cases is worth testing without mounting a component.

import type { Membership } from "$lib/ipc";

/**
 * What the room pane should put below the timeline.
 *
 * - `compose` — the ordinary case: a joined room, so a composer.
 * - `respondToInvitation` — an invitation: Accept / Decline, and **no
 *   composer**. Sending into a room this account has not joined fails at the
 *   homeserver, so offering a composer would be offering an action that
 *   cannot work.
 * - `nothing` — knocked, banned, or a room already left that the roster has
 *   not dropped yet. No composer and no invitation either: there is nothing
 *   honest to offer, and inventing an affordance for a state nobody can act
 *   on is worse than a quiet pane.
 */
/**
 * The line the room pane shows above Accept / Decline.
 *
 * Names the room, because an operator with 32 agent invitations waiting is
 * accepting them one at a time and the pane is the only place saying which
 * one this is.
 */
export function invitationPrompt(roomName: string): string {
  return `You have been invited to ${roomName}.`;
}

/**
 * What the timeline shows in place of history for an invitation.
 *
 * An invited room has no readable history — membership is `invite`, so the
 * homeserver sends state and nothing else — and the one event that does come
 * through renders as "… created the room", which reads like a broken room
 * rather than an unopened one. Saying so plainly is the whole fix; there is
 * no history to go and fetch.
 */
export const INVITATION_EMPTY_TIMELINE =
  "Accept the invitation to see this room's messages.";
