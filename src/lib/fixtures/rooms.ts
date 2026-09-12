import type { Membership, RoomIdentity, RoomRow, RoomSummary } from "$lib/ipc";

/**
 * Roster view-models.
 *
 * A fresh overrides-based builder rather than a move: `searchView.test.ts`
 * and `rooms.test.ts` each define their own `room`/`summary`, and they are
 * not the same builders — one varies by name, the other by membership.
 * Merging them means rewriting both files' call sites, which is a different
 * change with a different risk. They stay where they are; this is what the
 * stories use.
 */

function identity(name: string, overrides: Partial<RoomIdentity> = {}): RoomIdentity {
  return {
    glyph: null,
    name,
    role: null,
    initial: name.slice(0, 1).toUpperCase(),
    ...overrides,
  };
}

export function roomSummary(overrides: Partial<RoomSummary> = {}): RoomSummary {
  const name = overrides.name ?? "Ashram";
  return {
    id: overrides.id ?? "!ashram:id.agentpod.dev",
    name,
    avatarUrl: null,
    unread: 0,
    lastMessage: null,
    lastMessageIsOwn: false,
    lastMessageNamesSender: false,
    lastEventType: null,
    lastActivityMs: null,
    runtime: null,
    membership: "joined" as Membership,
    ...overrides,
  };
}

export function roomRow(overrides: Partial<RoomRow> & { name?: string } = {}): RoomRow {
  const { name, ...rest } = overrides;
  const summary = roomSummary({ ...(name ? { name } : {}), ...(rest.room ?? {}) });
  return {
    room: summary,
    identity: identity(summary.name),
    preview: null,
    affordance: "compose",
    ...rest,
  };
}

/** Rooms a search result can be attributed to. */
export const roomRowsForSearch: RoomRow[] = [
  roomRow({ room: roomSummary({ id: "!ashram:id.agentpod.dev", name: "Ashram" }) }),
  roomRow({ room: roomSummary({ id: "!guild:id.agentpod.dev", name: "Guild" }) }),
];


// ───────────────────────── roster rows ─────────────────────────
//
// The three agent states a row can draw a dot for, plus the ones that look
// like bugs until you know the rule: an invitation reads as a room in every
// other respect, and `quiet` draws a TRANSPARENT dot rather than nothing so
// names stay aligned down the column.

export const rosterQuiet: RoomRow = roomRow({
  room: roomSummary({ id: "!quiet:id.agentpod.dev", name: "Research", lastActivityMs: 1_700_000_000_000 }),
});

export const rosterApprovalNeeded: RoomRow = roomRow({
  room: roomSummary({
    id: "!deploy:id.agentpod.dev",
    name: "deploy-ops",
    unread: 1,
    lastActivityMs: 1_700_000_000_000,
  }),
  preview: { text: "Promote build 214 to production?", pending: true },
});

export const rosterAgentWorking: RoomRow = roomRow({
  room: roomSummary({
    id: "!atlas:id.agentpod.dev",
    name: "Atlas",
    lastActivityMs: 1_700_000_000_000,
  }),
  identity: { glyph: null, name: "Atlas", role: "OPENCLAW @ ASHRAM", initial: "A" },
});

export const rosterUnread: RoomRow = roomRow({
  room: roomSummary({ id: "!unread:id.agentpod.dev", name: "Guild", unread: 12, lastActivityMs: 1_700_000_000_000 }),
});

/** An invitation: a room in every respect except what opening it does. */
export const rosterInvitation: RoomRow = roomRow({
  room: roomSummary({ id: "!invited:id.agentpod.dev", name: "New Game", membership: "invited" }),
  affordance: "respondToInvitation",
});

/** A name with nowhere to go but `truncate`. */
export const rosterLongName: RoomRow = roomRow({
  room: roomSummary({
    id: "!long:id.agentpod.dev",
    name: "Rakeshs-MacBook-Pro.local — station events and run transcripts",
    lastActivityMs: 1_700_000_000_000,
  }),
});
