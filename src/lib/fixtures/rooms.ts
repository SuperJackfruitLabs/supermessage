import type {
  AgentAvatarStyle,
  Membership,
  RoomIdentity,
  RoomMember,
  RoomRow,
  RoomSummary,
  NeedsYouInbox,
  RoomStatus,
  RosterRow,
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


// ───────────────────── the navigation revamp's fleet ─────────────────────
//
// iOS `Previews/NavigationRevampFixtures.swift`, row for row: agents in every state, a room of
// people, and an invitation. The clock is fixed (iOS measures against `Date()`), so the age
// labels are stable across runs.

/** The instant the fleet's ages are measured from. */
export const FLEET_NOW = 1_700_000_300_000;

const ago = (minutes: number): number => FLEET_NOW - minutes * 60_000;

function fleetRow(
  id: string,
  name: string,
  role: string | null,
  glyph: string | null,
  overrides: Partial<RoomRow> & { summary?: Partial<RoomSummary> },
): RoomRow {
  const { summary, ...rest } = overrides;
  return roomRow({
    room: roomSummary({ id, name: glyph ? `${glyph} ${name}${role ? ` — ${role}` : ""}` : name, ...summary }),
    identity: identity(name, { glyph, role, initial: glyph ?? name.slice(0, 1).toUpperCase() }),
    ...rest,
  });
}

export const fleetSuperpipeline: RoomRow = fleetRow("!superpipeline:example.org", "Superpipeline", "Delivery", "⌘", {
  preview: { text: "Merge this branch into main?", pending: true },
  summary: { unread: 2, lastActivityMs: ago(3), runtime: { harness: "superpipeline", host: "foundry" } },
});
export const fleetAtlas: RoomRow = fleetRow("!atlas:example.org", "Atlas", "Platform", "✳", {
  preview: { text: "Atlas: Rebased onto main and the diff is empty.", pending: false },
  summary: { unread: 1, lastActivityMs: ago(6), runtime: { harness: "claude-code", host: "foundry" } },
});
export const fleetSam: RoomRow = fleetRow("!sam:example.org", "Strategy Sam", "Strategy", "◆", {
  preview: { text: "Can I open a draft PR for the pricing page?", pending: true },
  summary: { lastActivityMs: ago(40), runtime: { harness: "codex", host: "studio" } },
});
export const fleetDesignReview: RoomRow = fleetRow("!design:example.org", "Design Review", null, null, {
  preview: { text: "Priya: the new tab bar reads well", pending: false },
  summary: { unread: 4, lastActivityMs: ago(90) },
});
export const fleetQuill: RoomRow = fleetRow("!quill:example.org", "Quill", "Writing", "✒", {
  preview: { text: "Draft is in the branch.", pending: false },
  summary: { lastActivityMs: ago(60 * 24 * 3) },
});
export const fleetEstate: RoomRow = fleetRow("!estate:example.org", "Estate Planning", null, null, {
  summary: { membership: "invited" },
  affordance: "respondToInvitation",
});

/** The rows as the core arranges them for the fleet (state and tile as `core::roster` gives them). */
export const fleetRosterRows: RosterRow[] = [
  { row: fleetSuperpipeline, state: "needsYou", describesAgent: true, avatar: agentAvatarVariantsFixture[0]! },
  { row: fleetAtlas, state: "active", describesAgent: true, avatar: agentAvatarVariantsFixture[1]! },
  { row: fleetSam, state: "needsYou", describesAgent: true, avatar: agentAvatarVariantsFixture[2]! },
  { row: fleetDesignReview, state: "quiet", describesAgent: false },
  { row: fleetQuill, state: "idle", describesAgent: true, avatar: agentAvatarVariantsFixture[3]! },
  { row: fleetEstate, state: "quiet", describesAgent: false },
];

/** `core::roster::needs_you_inbox` over the fleet: two decisions and an invitation. */
export const fleetNeedsYouInbox: NeedsYouInbox = {
  decisions: [fleetRosterRows[0]!, fleetRosterRows[2]!],
  invitations: [fleetRosterRows[5]!],
  count: 3,
  badgeCount: 2,
};

/** `core::roster::agent_directory` over the fleet: agents by recency, no invitations. */
export const fleetAgentDirectory: RosterRow[] = [
  fleetRosterRows[0]!,
  fleetRosterRows[1]!,
  fleetRosterRows[2]!,
  fleetRosterRows[4]!,
];

/** The same fleet with every decision answered and no invitation left. */
export const caughtUpInbox: NeedsYouInbox = { decisions: [], invitations: [], count: 0, badgeCount: 0 };

/** What the core says of each fleet agent's status (`roomStatus` then `roomStatusWord`). */
export const fleetStatuses: Record<string, { status: RoomStatus; word: string }> = {
  "!superpipeline:example.org": { status: "needsYou", word: "Needs you" },
  "!atlas:example.org": { status: "working", word: "Working" },
  "!sam:example.org": { status: "needsYou", word: "Needs you" },
  "!quill:example.org": { status: "idle", word: "Idle" },
};
