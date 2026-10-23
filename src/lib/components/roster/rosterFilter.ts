// The chip row's state (iOS `RoomListView` + `RosterFilterChips`): which chip is in force, the
// sections it leaves, the chips with their counts, and the words for a chip that leaves nothing.
//
// Every rule is the core's. Which rooms a chip admits, which chips are offered, what a stored key
// means and what an empty chip says all come from `core::roster` through the injected functions;
// this module only decides who is asked, with what, and keeps a slow answer from landing late.

import {
  rosterFilterChips as defaultChips,
  rosterFilterEmptyState as defaultEmptyState,
  rosterFilterFromKey as defaultFromKey,
  rosterFilterKey as defaultKey,
  rosterFilterSections as defaultSections,
  type RosterEmptyState,
  type RosterFilter,
  type RosterFilterChip,
  type RosterSection,
} from "$lib/ipc";
import { createLatestOnly } from "../searchView";

/** The desktop's prefix on iOS's `roster.filter`, as `supermessage.roster.view`. */
export const FILTER_STORAGE_KEY = "supermessage.roster.filter";

export interface RosterFilterDeps {
  rosterFilterSections: typeof defaultSections;
  rosterFilterChips: typeof defaultChips;
  rosterFilterFromKey: typeof defaultFromKey;
  rosterFilterKey: typeof defaultKey;
  rosterFilterEmptyState: typeof defaultEmptyState;
  storage: Pick<Storage, "getItem" | "setItem">;
}

/** What the roster draws for one arrangement. */
export interface FilteredRoster {
  sections: RosterSection[];
  chips: RosterFilterChip[];
  /** Why there is nothing to list, when there is a reason to say; else `null`. */
  empty: RosterEmptyState | null;
}

/**
 * iOS's "Nothing but invitations": every room was filtered away and the invitations are not
 * showing. `null` when none are hidden. The words are iOS's (`RoomListView.swift:196-199`).
 */
export function nothingButInvitations(hidden: number): RosterEmptyState | null {
  if (hidden <= 0) return null;
  return { title: "Nothing but invitations", description: `${hidden} waiting — open Invitations above.` };
}

function browserStorage(): Pick<Storage, "getItem" | "setItem"> {
  return localStorage;
}

export function createRosterFilter(deps: Partial<RosterFilterDeps> = {}) {
  const d = {
    rosterFilterSections: defaultSections,
    rosterFilterChips: defaultChips,
    rosterFilterFromKey: defaultFromKey,
    rosterFilterKey: defaultKey,
    rosterFilterEmptyState: defaultEmptyState,
    ...deps,
  };
  const store = (): Pick<Storage, "getItem" | "setItem"> => deps.storage ?? browserStorage();
  let selected: RosterFilter = "all";
  const latest = createLatestOnly();

  return {
    get selected(): RosterFilter {
      return selected;
    },

    /** Reads the stored key back through the core, which owns what a key means. */
    async load(): Promise<void> {
      let stored = "";
      try {
        stored = store().getItem(FILTER_STORAGE_KEY) ?? "";
      } catch {
        // Storage can be unavailable; the chip is a convenience.
      }
      selected = await d.rosterFilterFromKey(stored);
    },

    /** Chooses a chip and remembers the core's key for it. */
    async select(filter: RosterFilter): Promise<void> {
      selected = filter;
      const key = await d.rosterFilterKey(filter);
      try {
        store().setItem(FILTER_STORAGE_KEY, key);
      } catch {
        // As above.
      }
    },

    /**
     * Narrows `arranged` by the chip in force and counts the chips over the whole arrangement.
     * Latest-only: an answer that finishes after a newer `apply` began is `null`. Nothing of an
     * earlier arrangement is kept, so another account's roster never shows this one's counts.
     */
    async apply(arranged: RosterSection[], hiddenInvitations = 0): Promise<FilteredRoster | null> {
      const ticket = latest.begin();
      const chosen = selected;
      const hasRooms = arranged.some((s) => s.rows.length > 0);
      const [sections, chips] = await Promise.all([
        d.rosterFilterSections(arranged, chosen),
        d.rosterFilterChips(arranged),
      ]);
      const none = !sections.some((s) => s.rows.length > 0);
      let empty: RosterEmptyState | null = null;
      if (none && chosen !== "all" && hasRooms) {
        empty = await d.rosterFilterEmptyState(chosen);
      } else if (none && chosen === "all") {
        empty = nothingButInvitations(hiddenInvitations);
      }
      if (!latest.isCurrent(ticket)) return null;
      return { sections, chips, empty };
    },

    /** Each distinct section title as a person reads it, the core asked once per title. */
    async sectionTitles(
      sections: RosterSection[],
      spaceDisplayName: (name: string) => Promise<string>,
    ): Promise<Map<string, string>> {
      const distinct = [...new Set(sections.flatMap((s) => (s.title === null ? [] : [s.title])))];
      const read = await Promise.all(distinct.map(async (t) => [t, await spaceDisplayName(t)] as const));
      return new Map(read);
    },
  };
}
