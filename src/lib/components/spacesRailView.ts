// What the spaces rail renders, as a pure function of the core's
// `spaces_list()` result.
//
// Kept out of `SpacesRail.svelte` for the same reason `roomPreview.ts` and
// `stagedAttachment.ts` are kept out of their components: this project's
// vitest runs with `environment: "node"` and has no component-testing setup,
// so anything that has to be *proved* rather than looked at lives in a plain
// module and the component stays a thin reactive wrapper over it.
//
// Two rules live here, and both are load-bearing rather than cosmetic.
//
// 1. **No spaces, no rail** (spaces-rail design §6). `railEntries([])`
//    returns an empty list, and the component renders nothing at all when it
//    is empty — not a 56px strip holding one "All rooms" button that does
//    nothing. Most accounts have no spaces, so that strip would be the
//    common case rather than the edge one. Encoding it as "the entry list is
//    empty" rather than as an `{#if spaces.length}` in the markup is what
//    makes it testable, and what lets `+page.svelte` ask the same question
//    the same way when it decides which element pays the left safe-area
//    inset.
//
// 2. **"All rooms" is always first, and it is not derived from the spaces.**
//    Without it a reader who selects a space has no way back — there is no
//    other affordance in this UI that means "stop filtering". It is
//    prepended whenever the rail renders at all, which is exactly when there
//    is at least one space to filter by.
//
// The rail is icon-only, so an entry's *label* is the whole of its
// accessible name and its hover title: an avatar alone is not a label
// (design §6). Nothing here touches the DOM, Svelte, a store or the clock.

import type { SpaceSummary } from "$lib/ipc";

/** One button in the rail — "All rooms", or a space. */
export interface RailEntry {
  /**
   * The space's id, or `null` for the "All rooms" entry — the same `null`
   * `spaceSelect` takes to mean "restore every room", so a click handler can
   * pass this straight through without a branch.
   */
  spaceId: string | null;
  /**
   * The entry's accessible name *and* its hover title: the parsed space
   * name, its role when the name carries one, and how many rooms selecting
   * it will show. The rail shows an avatar and nothing else, so this string
   * is the only place those facts are legible to a screen reader — or to
   * anyone looking at an unfamiliar circle.
   */
  label: string;
  /**
   * The single character for the avatar's fallback circle: the parsed glyph
   * when the space name has one, otherwise the first letter of the parsed
   * name (never the raw first character of the whole name — see
   * `core::room_identity`'s `initial`). `"All"` for the "All rooms" entry,
   * which has no avatar to fall back *from*.
   */
  initial: string;
  /**
   * Whether this entry is an **invitation** rather than a space the reader is
   * in. `false` for "All rooms" and for every joined space.
   *
   * The rail has one click handler and two kinds of entry underneath it,
   * doing opposite things: a joined space filters the roster, while an
   * invitation cannot — there is no subtree to scope to, and the core answers
   * `unknownSpace` — and opens Accept / Decline instead. Carried as a flag on
   * the entry rather than looked up from the summaries again in the
   * component, so the branch is decided in the module that has tests.
   */
  pending: boolean;
}

/** What an invitation says where a joined space says how many rooms it holds. */
const INVITATION_LABEL = "Invitation";

/** The label the "All rooms" entry always carries. */
const ALL_ROOMS_LABEL = "All rooms";

/**
 * The fallback circle's content for "All rooms". Three letters rather than
 * one: it is not an initial standing in for a missing picture, it is the
 * entry's whole identity, and a lone `A` beside a column of real initials
 * would read as just another space.
 */
const ALL_ROOMS_INITIAL = "All";

/**
 * How many rooms a space will show, as a phrase for its accessible name.
 *
 * `null` — the phrase is omitted entirely — for anything that is not a
 * non-negative integer. The core always sends a `u64`, so that is defence in
 * depth rather than an expected path, but the honest rendering of "we do not
 * have a number" is silence, not `"No rooms"`, which is a specific claim
 * about a space the reader is deciding whether to open.
 *
 * `0` itself is *not* that case. An empty space is a real, expected outcome
 * (see {@link SpaceSummary.childCount}) and gets said out loud, because the
 * alternative — an entry whose name says nothing about its contents —
 * leaves the reader to discover the emptiness by selecting it and finding an
 * empty roster.
 */
function roomCountPhrase(count: number): string | null {
  if (!Number.isInteger(count) || count < 0) return null;
  if (count === 0) return "No rooms";
  return count === 1 ? "1 room" : `${count} rooms`;
}

/**
 * The accessible name for one space's entry: name, role (when the name
 * carries the `glyph — Name — Role` structure), then the room count — or,
 * for an invitation, the word `Invitation` in the count's place.
 *
 * Comma-joined in that order, the same shape and for the same reasons as
 * `RoomList.svelte`'s `rowAriaLabel`: the most identifying fact first, and
 * no visual punctuation ("·") that a screen reader would read out literally.
 *
 * Both halves come from `core::room_identity`, which bounds them (120 and 40
 * code points) — a space name is server-controlled text and nothing stops it
 * being megabytes long. That bound is why this label is safe to put in a
 * `title` attribute as well as an `aria-label`.
 */
export function spaceEntryLabel(space: SpaceSummary): string {
  const identity = space.identity;
  const parts = [identity.name];
  if (identity.role !== null) parts.push(identity.role);
  // An invitation never counts rooms. `childCount` is 0 for one, and "No
  // rooms" would be a claim about the contents of a space we are not in and
  // cannot see into — where what we actually know is that we were asked.
  if (space.membership === "invited") {
    parts.push(INVITATION_LABEL);
  } else {
    const count = roomCountPhrase(space.childCount);
    if (count !== null) parts.push(count);
  }
  return parts.join(", ");
}

/**
 * Every button the rail should render, in order — or an **empty list**,
 * which means the rail must not render at all.
 *
 * See this module's doc comment for why those two rules are one function
 * rather than two conditions in the markup. The empty case is not "a rail
 * with only All rooms in it": an account with no spaces has nothing to
 * filter by, so the entry that clears the filter has nothing to clear.
 */
export function railEntries(spaces: SpaceSummary[]): RailEntry[] {
  if (spaces.length === 0) return [];
  return [
    { spaceId: null, label: ALL_ROOMS_LABEL, initial: ALL_ROOMS_INITIAL, pending: false },
    ...spaces.map((space) => ({
      spaceId: space.id,
      label: spaceEntryLabel(space),
      initial: space.identity.initial,
      pending: space.membership === "invited",
    })),
  ];
}
