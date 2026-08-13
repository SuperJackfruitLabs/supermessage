// Pure display helpers for `RoomInfoPanel.svelte` — the same split every
// other component in this codebase makes between presentation logic that's
// worth unit-testing (`timelineItemView.ts`, `timelineGrouping.ts`,
// `draftTracker.ts`) and the Svelte markup that calls it. No DOM, no store:
// every function here takes plain values from `RoomInfo`/`RoomMember`
// (`$lib/ipc.ts`) and returns a plain value, so it's testable in this
// project's `environment: "node"` vitest without a component-mounting
// harness (which this repo deliberately doesn't have).

import type { RoomInfo, RoomMember } from "$lib/ipc";

/**
 * The room's display name — its own `m.room.name` when set, falling back to
 * its room id. Mirrors the same fallback `core::rooms::project_room_parts`
 * already applies to `RoomSummary.name` (see that function's doc comment):
 * `RoomInfoDto.name` deliberately does *not* apply it on the Rust side
 * (`docs/matrix-events.md`'s "surface in room info" note is about showing
 * the room's own topic/alias, not re-deriving its display name a second
 * way), so this is where a caller that needs "always a non-empty string" —
 * a heading — gets one.
 */
export function roomDisplayName(info: Pick<RoomInfo, "name" | "roomId">): string {
  const trimmed = info.name?.trim();
  return trimmed && trimmed.length > 0 ? trimmed : info.roomId;
}

/**
 * A member's display name — their own `m.room.member` display name when
 * set, falling back to their user id. The same convention every other
 * sender-name field in this codebase already uses (see `Timeline.svelte`'s
 * `item.senderDisplayName ?? item.sender`).
 */
export function memberDisplayName(member: Pick<RoomMember, "displayName" | "userId">): string {
  const trimmed = member.displayName?.trim();
  return trimmed && trimmed.length > 0 ? trimmed : member.userId;
}

/**
 * A single uppercase initial for `label` (a room or member display name),
 * for the placeholder shown before/absent a real avatar image — mirrors
 * `RoomList.svelte`'s `initials` exactly, including the reason it iterates
 * code points rather than indexing `label[0]` directly: an emoji-led name
 * (this deployment's agent rooms are commonly named this way) is an astral
 * code point, and `label[0]` would take only half of its UTF-16 surrogate
 * pair — a broken glyph, not the emoji.
 */
export function initial(label: string): string {
  const trimmed = label.trim();
  const first = [...trimmed][0];
  return first === undefined ? "?" : first.toUpperCase();
}
