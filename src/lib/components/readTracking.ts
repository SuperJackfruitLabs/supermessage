// The "is the reader genuinely at the live end of the timeline" predicate
// `Timeline.svelte` evaluates before ever calling `timelineStore.markRead`.
// Pure and framework-free, like `draftTracker.ts`/`typingTracker.ts` — this
// project's vitest has no component-mounting setup (`environment: "node"`),
// so the decision that actually matters (not merely "is this room open")
// has to be testable without one.
//
// This task's brief is explicit about what marking read wrong costs: a
// background window, or a reader scrolled up into history, must never mark
// a room read — doing so silently destroys unread state for a message the
// reader hasn't actually seen. `shouldMarkRead` is conjunctive on every
// signal that rules that out, not merely "the room is focused":
//
//   - `followBottom` — the same signal `Timeline.svelte` already computes to
//     decide whether an incoming message should auto-scroll the view (see
//     that file's top-of-script doc comment). Scrolled up into history is
//     `false` here.
//   - `windowFocused` — the app window actually has OS-level focus, not
//     merely "this room is the one selected in a backgrounded window".
//   - `lastItemId` — `null` when the timeline has nothing to mark read at
//     all (an empty room, or a page mid-load).
//   - `lastMarkedId` — the last item this predicate already said yes for.
//     Without this, every qualifying diff/scroll/focus change would fire a
//     fresh `markRead` call for an item that's already been marked —
//     `Timeline::mark_as_read`'s own dedup (skipping a receipt that
//     wouldn't advance the room's read state) would absorb the redundant
//     *network* traffic, but not the redundant IPC round trip on every
//     qualifying re-render.

export interface ReadStateInput {
  /** Whether the reader is scrolled to (or within) the bottom threshold — `Timeline.svelte`'s own `followBottom`. */
  followBottom: boolean;
  /** Whether the app window currently has focus. */
  windowFocused: boolean;
  /** The id of the newest item currently known, or `null` if there is none. */
  lastItemId: string | null;
  /** The id of the last item this predicate already returned `true` for, or `null` if it never has. */
  lastMarkedId: string | null;
}

/**
 * Whether the focused room should be marked read right now. See this
 * module's doc comment for what each input guards against.
 */
export function shouldMarkRead(input: ReadStateInput): boolean {
  return (
    input.followBottom &&
    input.windowFocused &&
    input.lastItemId !== null &&
    input.lastItemId !== input.lastMarkedId
  );
}
