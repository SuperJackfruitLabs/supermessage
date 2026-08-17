// The spaces rail's state: which spaces exist, and which one (if any) the
// roster is currently scoped to.
//
// **This store deliberately owns no room-list machinery, and that is the
// whole design.** Selecting a space is one command — `space_select` — and
// the re-filtered roster comes back on the *existing* `sm://rooms/diff`
// channel as a `Reset` at the next sequence number, folded in by the room
// store's `DiffTracker` like any other batch. So there is nothing here that
// resyncs, nothing that re-arms a tracker, and nothing that touches
// `roomsStore` or `timelineStore` at all:
//
// - **Resyncing** after a space switch would race the core's own re-emission
//   and, worse, is the entry point to the sequence hazard below.
// - **Re-arming** the room-list tracker tells it to expect a fresh sequence
//   starting at 1 from a stream that is not restarting. `DiffTracker.apply`
//   only detects gaps *forward*, so the next envelope — carrying the number
//   after the last one, exactly as it should — reads as an already-applied
//   duplicate and is silently dropped. No resync is triggered, and once the
//   sequence climbs past the stale expectation its ops fold onto whatever
//   was left on screen. Silent corruption, not staleness. `rooms.svelte.ts`'s
//   module doc comment tells the same story for login/restore, where this
//   has actually shipped.
// - **Re-subscribing the timeline** is forbidden outright (spaces-rail
//   design §7), and follows from the same rule: a space switch changes which
//   rooms the roster *lists*, never which room is open. If the room the
//   reader is reading is filtered out, the room pane keeps showing it — the
//   roster is a navigation surface, and filtering it must not close what
//   someone is in the middle of.
//
// The one thing this store does do beyond issuing the command is recover
// from `unknownSpace`; see `select` below.

import {
  spaceSelect as defaultSpaceSelect,
  spacesList as defaultSpacesList,
  type CoreError,
  type SpaceSummary,
} from "$lib/ipc";

export interface SpacesStoreDeps {
  spacesList: typeof defaultSpacesList;
  spaceSelect: typeof defaultSpaceSelect;
}

const defaultDeps: SpacesStoreDeps = {
  spacesList: defaultSpacesList,
  spaceSelect: defaultSpaceSelect,
};

/** Whether a rejected command is the core's "that space is gone" refusal. */
function isUnknownSpace(err: unknown): boolean {
  return (err as CoreError | undefined)?.kind === "unknownSpace";
}

export function createSpacesStore(deps: SpacesStoreDeps = defaultDeps) {
  let spaces = $state<SpaceSummary[]>([]);
  /** `null` is "All rooms" — the default, and the only state with no filter. */
  let selectedId = $state<string | null>(null);

  /**
   * Fetches the joined spaces. Called on session start, and again as the
   * first half of the `unknownSpace` recovery.
   *
   * A failure empties the list rather than leaving the last one on screen:
   * the common failure here is `notReady` (no session), and a rail listing
   * an account's spaces after that account is gone is worse than no rail.
   * It is not rethrown — nothing above this can act on it, and the rail
   * simply not appearing is the honest outcome.
   *
   * Reconciles the selection against what came back: a selected space that
   * is no longer in the list cannot stay highlighted. Only the local
   * highlight is cleared here, never by issuing a command — the two callers
   * are session start (where the core's filter is fresh, so there is nothing
   * to clear) and `select`'s recovery path, which issues its own
   * `spaceSelect(null)` immediately afterwards precisely because there the
   * roster *does* need moving.
   */
  async function load(): Promise<void> {
    try {
      spaces = await deps.spacesList();
    } catch (err) {
      console.error("failed to list spaces", err);
      spaces = [];
    }
    if (selectedId !== null && !spaces.some((space) => space.id === selectedId)) {
      selectedId = null;
    }
  }

  /**
   * Scopes the roster to `spaceId`, or restores every room for `null`.
   *
   * The highlight moves first and the command follows, because the roster it
   * scopes arrives asynchronously on the diff channel anyway — a rail that
   * waited for the round trip would lag every click by a network hop for no
   * added truth. What matters is that the highlight and the roster end up
   * agreeing, which is what the two failure paths are for:
   *
   * - **`unknownSpace`** — the space has gone (left, or never joined). The
   *   core refused *without filtering anything*, so the roster is still
   *   scoped to whatever it was before this click. Re-fetch the list, so the
   *   dead entry stops being offered, and then genuinely select "All rooms",
   *   command and all. Clearing only the highlight would leave a reader
   *   looking at "All rooms" above a roster still filtered to the space they
   *   were on before — the exact disagreement the core refused in order to
   *   prevent.
   * - **Anything else** (`notReady`, `protocol`) — nothing was filtered
   *   either, and there is no recovery to attempt, so the highlight goes
   *   back where it was. Reverting is the honest move: the rail must never
   *   claim a scope the roster does not have.
   *
   * The recursive call cannot loop: it is guarded on `spaceId !== null`, and
   * the only branch that recurses passes `null`.
   */
  async function select(spaceId: string | null): Promise<void> {
    // An invitation is in this list but is not a filter. Selecting it would
    // earn an `unknownSpace` refusal — correctly, since we hold none of the
    // space's state — and the recovery below would then reset the roster to
    // "All rooms", clearing a filter the reader never asked to clear. The
    // rail offers Accept / Decline for these instead; this is the guard
    // behind that, so no other caller can select one by accident.
    if (spaceId !== null && pending.some((space) => space.id === spaceId)) return;

    const previous = selectedId;
    selectedId = spaceId;
    try {
      await deps.spaceSelect(spaceId);
    } catch (err) {
      if (spaceId !== null && isUnknownSpace(err)) {
        await load();
        await select(null);
        return;
      }
      console.error("failed to select space", spaceId, err);
      selectedId = previous;
    }
  }

  /**
   * Drops everything, for logout.
   *
   * Not the same thing as `load()` failing into an empty list: this runs
   * when there is no core call left to make, and it exists so the *next*
   * account never briefly sees the previous one's rail in the frames between
   * mounting and its own `load()` resolving. `roomsStore.logout` clears the
   * room list for the same reason.
   */
  function clear(): void {
    spaces = [];
    selectedId = null;
  }

  /**
   * The spaces we have been *invited* to, in the order the core sorted them
   * (after the joined ones).
   *
   * A separate view rather than a flag the rail filters on, because two
   * surfaces need it: the rail draws these as pending entries, and the panel
   * that offers Accept / Decline looks the chosen one up here by id.
   */
  const pending = $derived(spaces.filter((space) => space.membership === "invited"));

  return {
    get spaces(): SpaceSummary[] {
      return spaces;
    },
    get pending(): SpaceSummary[] {
      return pending;
    },
    get selectedId(): string | null {
      return selectedId;
    },
    load,
    select,
    clear,
  };
}

export const spacesStore = createSpacesStore();
