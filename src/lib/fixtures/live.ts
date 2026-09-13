import type { TypingUser } from "$lib/ipc";
import type { LiveTool } from "$lib/stores/live.svelte";

/**
 * The ephemeral half of a turn: who is typing, what an agent is thinking,
 * and what its tools are doing.
 *
 * None of this is history — `live.svelte.ts`'s module comment is emphatic
 * that it is not persisted, not paginated, and a device that was asleep
 * never sees it. Which is exactly why a catalogue matters here: these states
 * are hard to catch in a running app because they are gone in seconds.
 */

export function typingUser(overrides: Partial<TypingUser> = {}): TypingUser {
  const userId = overrides.userId ?? "@atlas:example.org";
  return {
    userId,
    displayName: "Atlas",
    label: "Atlas",
    ...overrides,
  };
}

export function liveTool(overrides: Partial<LiveTool> = {}): LiveTool {
  return {
    toolCallId: "call-1",
    title: "read docs/tech-stack.md",
    kind: "read",
    status: "in_progress",
    locations: [],
    ...overrides,
  };
}

// ───────────────────────────── typing ─────────────────────────────

export const typingNobody: TypingUser[] = [];

export const typingOne: TypingUser[] = [typingUser()];

export const typingTwo: TypingUser[] = [
  typingUser(),
  typingUser({ userId: "@krishna:example.org", displayName: "Krishna", label: "Krishna" }),
];

/** Enough people that the line has to summarise rather than list. */
export const typingMany: TypingUser[] = [
  typingUser(),
  typingUser({ userId: "@krishna:example.org", displayName: "Krishna", label: "Krishna" }),
  typingUser({ userId: "@sam:example.org", displayName: "Strategy Sam", label: "Strategy Sam" }),
  typingUser({ userId: "@quill:example.org", displayName: "Writer Quill", label: "Writer Quill" }),
];

/**
 * No cached display name, so the line falls back to the raw id.
 *
 * `TypingUser.displayName` is `null` until the room's member store has
 * something, and `label` is server-controlled arbitrary text otherwise —
 * its own doc comment says to guard it against overflow like any other
 * sender field.
 */
export const typingUnnamed: TypingUser[] = [
  typingUser({ userId: "@9247e5a1b3c4:id.agentpod.dev", displayName: null, label: "@9247e5a1b3c4:id.agentpod.dev" }),
];

// ─────────────────────────── live activity ───────────────────────────

export const toolsNone: LiveTool[] = [];

export const toolsRunning: LiveTool[] = [liveTool()];

/**
 * A sequence where one has failed.
 *
 * `LiveActivity` names the *last* failed tool ahead of any running one,
 * because a failure is the only state there that still matters after the
 * turn ends. This fixture is the one that shows that rule working.
 */
export const toolsWithFailure: LiveTool[] = [
  liveTool({ toolCallId: "c1", title: "read docs/tech-stack.md", status: "completed" }),
  liveTool({ toolCallId: "c2", title: "write src/lib/tokens.css", status: "failed" }),
  liveTool({ toolCallId: "c3", title: "run pnpm check", status: "in_progress" }),
];

/** Past two completed, so the "N done" counter appears. */
export const toolsManyDone: LiveTool[] = [
  liveTool({ toolCallId: "c1", title: "read a", status: "completed" }),
  liveTool({ toolCallId: "c2", title: "read b", status: "completed" }),
  liveTool({ toolCallId: "c3", title: "read c", status: "completed" }),
  liveTool({ toolCallId: "c4", title: "run the token generator", status: "in_progress" }),
];

/** A tool title long enough to need the row's `truncate`. */
export const toolsLongTitle: LiveTool[] = [
  liveTool({
    title:
      "regenerate every design-token target and diff the checked-in output " +
      "against the source that produces it",
  }),
];

// ───────────────────────────── reasoning ─────────────────────────────

export const reasoningShort = "Checking whether the roster is already sorted.";

export const reasoningLong =
  "The contrast contract on `content-faint` lists all three grounds rather " +
  "than only the reading surface, because the ground it fails on is never " +
  "the one you are looking at. In dark the worst ground flips to " +
  "`surface-raised`, since the ramp runs the other way.";

export const reasoningNone = null;
