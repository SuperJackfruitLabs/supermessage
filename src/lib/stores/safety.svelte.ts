// What this device knows about blocking and reporting.
//
// The desktop counterpart of iOS `SafetyStore` (SupermessageKit/Stores/SafetyStore.swift).
//
// Two sets, with different owners:
// - `blocked` belongs to the homeserver (`m.ignored_user_list`). The core's list always wins:
//   `apply` replaces it wholesale, and `noteBlocked`/`noteUnblocked` only bridge the gap between a
//   call landing and the homeserver's echo arriving.
// - `hidden` belongs to this device. There is no Matrix protocol for hiding one event for one
//   account, so a reported message is hidden locally, by event id, and the set is kept across
//   launches until sign-out.
//
// Both sets are replaced, never mutated, so a `$derived` reader sees every change.

import { blockedUsers } from "$lib/ipc";

/** The key the hidden set is stored under (iOS: `safety.hiddenEvents`). */
export const HIDDEN_EVENTS_KEY = "safety.hiddenEvents";

type SafetyStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

/** Whatever was stored, as a set of ids; anything but a JSON array of strings reads as empty. */
function readHidden(storage: SafetyStorage | null): Set<string> {
  try {
    const raw = storage?.getItem(HIDDEN_EVENTS_KEY);
    if (!raw) return new Set();
    const parsed: unknown = JSON.parse(raw);
    if (Array.isArray(parsed) && parsed.every((id) => typeof id === "string")) {
      return new Set(parsed as string[]);
    }
  } catch {
    // Unreadable storage or corrupt JSON: nothing is hidden.
  }
  return new Set();
}

export function createSafetyStore(storage: SafetyStorage | null) {
  let blocked = $state<ReadonlySet<string>>(new Set());
  let hidden = $state<ReadonlySet<string>>(readHidden(storage));
  /** Bumped by every `apply`, so a slow `seed` can tell it was overtaken. */
  let generation = 0;

  return {
    get blocked(): ReadonlySet<string> {
      return blocked;
    },
    get hidden(): ReadonlySet<string> {
      return hidden;
    },

    /** Replaces the blocked list with the core's. */
    apply(userIds: string[]): void {
      generation += 1;
      blocked = new Set(userIds);
    },
    isBlocked(userId: string): boolean {
      return blocked.has(userId);
    },
    /** For after a block call succeeded, until the core's list arrives. */
    noteBlocked(userId: string): void {
      blocked = new Set(blocked).add(userId);
    },
    /** For after an unblock call succeeded, until the core's list arrives. */
    noteUnblocked(userId: string): void {
      const next = new Set(blocked);
      next.delete(userId);
      blocked = next;
    },

    /** Hides a reported event on this device, and remembers it. */
    hide(eventId: string): void {
      hidden = new Set(hidden).add(eventId);
      try {
        storage?.setItem(HIDDEN_EVENTS_KEY, JSON.stringify([...hidden]));
      } catch {
        // Still hidden for this run.
      }
    },
    /** Drops rows whose event was hidden. A local echo (no event id) always passes. */
    visible<T extends { eventId: string | null }>(items: readonly T[]): T[] {
      return items.filter((item) => item.eventId === null || !hidden.has(item.eventId));
    },

    /**
     * Fills the blocked list from the core, unless an `apply` happened while
     * it was loading: the core emits the list once at session start, which can
     * be before the listener exists, and a slow read must not overwrite a
     * newer event. A failed read is ignored; the next event fills the list.
     */
    async seed(load: () => Promise<string[]> = defaultLoad): Promise<void> {
      const started = generation;
      try {
        const userIds = await load();
        if (generation === started) {
          blocked = new Set(userIds);
        }
      } catch {
        // iOS `try?`.
      }
    },

    /** Forgets everything, including the stored key. For sign-out. */
    clear(): void {
      generation += 1;
      blocked = new Set();
      hidden = new Set();
      try {
        storage?.removeItem(HIDDEN_EVENTS_KEY);
      } catch {
        // Nothing stored that could be read back.
      }
    },
  };
}

async function defaultLoad(): Promise<string[]> {
  return (await blockedUsers()).map((member) => member.userId);
}

function appStorage(): SafetyStorage | null {
  try {
    return localStorage;
  } catch {
    return null;
  }
}

export const safetyStore = createSafetyStore(appStorage());
