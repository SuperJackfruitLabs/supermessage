import type {
  AgentAvatarStyle,
  Membership,
  RoomIdentity,
  RoomMember,
  RoomRow,
  RoomSummary,
} from "$lib/ipc";

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

// ───────────────────────── room info ─────────────────────────

export function member(overrides: Partial<RoomMember> = {}): RoomMember {
  return {
    userId: "@atlas:id.agentpod.dev",
    displayName: "Atlas",
    avatarUrl: null,
    isAgent: false,
    isIgnored: false,
    ...overrides,
  };
}

export const memberHuman = member({ userId: "@rakesh:id.agentpod.dev", displayName: "Rakesh" });

/** An agent, whose display name carries the suite's role convention. */
export const memberAgent = member({
  userId: "@atlas:id.agentpod.dev",
  displayName: "Atlas (OpenClaw on Ashram)",
});

/**
 * No display name at all, so the row falls back to the id.
 *
 * `memberDisplayName` owns that fallback; this is the fixture that shows it.
 */
export const memberWithoutName = member({
  userId: "@9247e5a1b3c4f8e2:id.agentpod.dev",
  displayName: null,
});

/**
 * A display name that is one long unbroken run.
 *
 * The member row uses `break-words` rather than `truncate` precisely for
 * this: a name is sender-controlled free text, and `truncate` would hide it
 * outright rather than let a reader see it wrap.
 */
export const memberLongName = member({
  userId: "@long:id.agentpod.dev",
  displayName: "wss://bridge.agentpod.dev/v1/sessions/" + "a".repeat(70),
});

/** The room identity the info panel's 64px header draws. */
export const roomIdentityPlain: RoomIdentity = identity("Research");

export const roomIdentityWithRole: RoomIdentity = {
  glyph: null,
  name: "Atlas",
  role: "OPENCLAW @ ASHRAM",
  initial: "A",
};

export const roomIdentityLongName: RoomIdentity = identity(
  "Rakeshs-MacBook-Pro.local — station events and run transcripts",
);

/**
 * `agentAvatarVariants()` as the core returns it (`core::agent_avatar`), fixed so the frames
 * do not move if the core's palette does: ground and ink per variant, in variant order.
 */
export const agentAvatarVariantsFixture: AgentAvatarStyle[] = [
  { ground: "accent", ink: "accentContent", variant: 0 },
  { ground: "accentSoft", ink: "accent", variant: 1 },
  { ground: "ok", ink: "surface", variant: 2 },
  { ground: "surfaceRaised", ink: "accent", variant: 3 },
  { ground: "contentMuted", ink: "surface", variant: 4 },
  { ground: "surfaceRaised", ink: "ok", variant: 5 },
];

/** An agent that has said something, to show the preview on the second line. */
export const rosterAgentPreview: RoomRow = roomRow({
  room: roomSummary({
    id: "!kiran:id.agentpod.dev",
    name: "Kiran",
    unread: 3,
    lastActivityMs: 1_700_000_200_000,
  }),
  identity: { glyph: null, name: "Kiran", role: "CLAUDE-CODE @ FOUNDRY", initial: "K" },
  preview: { text: "Staging is green across all four ABIs.", pending: false },
});

/** A busy room: the badge reads 99+. */
export const rosterUnreadOver99: RoomRow = roomRow({
  room: roomSummary({
    id: "!busy:id.agentpod.dev",
    name: "Guild",
    unread: 140,
    lastActivityMs: 1_700_000_250_000,
  }),
  preview: { text: "Anyone looked at the build?", pending: false },
});
