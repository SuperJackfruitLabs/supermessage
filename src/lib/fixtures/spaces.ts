import type { RoomIdentity, SearchResult, SpaceSummary } from "$lib/ipc";

/**
 * Spaces and search results.
 *
 * `SpaceSummary`'s own doc comments carry two rules worth having fixtures
 * for, because both look like bugs until you know them: `childCount: 0` is
 * a real, expected value rather than a loading state, and an invited space
 * is a *rail* entry with `childCount: 0` that cannot be selected at all.
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

export function spaceSummary(overrides: Partial<SpaceSummary> = {}): SpaceSummary {
  const name = overrides.name ?? "Ashram";
  return {
    id: overrides.id ?? "!ashram:id.agentpod.dev",
    name,
    identity: identity(name),
    avatarUrl: null,
    childCount: 10,
    membership: "joined",
    ...overrides,
  };
}

export const spaceJoined = spaceSummary();

/**
 * A joined space whose children have all gone.
 *
 * `childCount: 0` is the honest answer, not an error and not a spinner —
 * selecting it yields an empty roster, which is a real state.
 */
export const spaceEmpty = spaceSummary({
  id: "!cloudchamber:id.agentpod.dev",
  name: "Cloudchamber",
  childCount: 0,
});

/**
 * An invitation. A rail entry, never a roster row, and not selectable.
 *
 * `childCount` is 0 because the subtree of a space you have not joined is
 * not visible, so any number would be invented.
 */
export const spaceInvited = spaceSummary({
  id: "!newgame:id.agentpod.dev",
  name: "New Game",
  childCount: 0,
  membership: "invited",
});

/** A name long enough that the rail must fall back to its initial. */
export const spaceLongName = spaceSummary({
  id: "!long:id.agentpod.dev",
  name: "Rakeshs-MacBook-Pro.local — station events and run transcripts",
  childCount: 6,
});

export const spacesFew: SpaceSummary[] = [spaceJoined, spaceEmpty];

export const spacesMany: SpaceSummary[] = [
  spaceJoined,
  spaceSummary({ id: "!guild:id.agentpod.dev", name: "Guild", childCount: 14 }),
  spaceSummary({ id: "!periscope:id.agentpod.dev", name: "Periscope", childCount: 3 }),
  spaceEmpty,
  spaceLongName,
  spaceInvited,
];

/** No avatars resolve, so every entry draws its parsed initial. */
export const spaceAvatarsNone: Record<string, string | null> = {};

// ───────────────────────────── search ─────────────────────────────

export function searchResult(overrides: Partial<SearchResult> = {}): SearchResult {
  return {
    eventId: "$evt1",
    roomId: "!ashram:id.agentpod.dev",
    sender: "@atlas:id.agentpod.dev",
    body: "Staging is green across all four ABIs.",
    timestampMs: 1_700_000_000_000,
    ...overrides,
  };
}

export const searchHits: SearchResult[] = [
  searchResult(),
  searchResult({ eventId: "$evt2", body: "Ready to promote build 214." }),
  searchResult({
    eventId: "$evt3",
    roomId: "!guild:id.agentpod.dev",
    sender: "@krishna:id.agentpod.dev",
    body: "I'm Krishna — your ethical guide and life strategist in this workspace.",
  }),
];

export const searchNoHits: SearchResult[] = [];
