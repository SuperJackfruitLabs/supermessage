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
