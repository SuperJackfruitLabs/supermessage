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
//   launches until sign-out. The core does the hiding: the set is pushed to it (`pushHidden`, at
//   session start and after every `hide`), the core groups the timeline as if those rows were
//   absent, and their rows come back marked `hidden`, which the timeline skips.
//
// Both sets are replaced, never mutated, so a `$derived` reader sees every change.

import { blockedUsers, setHiddenEvents } from "$lib/ipc";

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

/** Hands the core the whole hidden set (`ipc.setHiddenEvents`). */
type PushHidden = (eventIds: string[]) => Promise<void>;

export function createSafetyStore(storage: SafetyStorage | null, push: PushHidden | null = null) {
  let blocked = $state<ReadonlySet<string>>(new Set());
  let hidden = $state<ReadonlySet<string>>(readHidden(storage));
  /** Bumped by every `apply`, so a slow `seed` can tell it was overtaken. */
  let generation = 0;
  /**
   * The last push of the hidden set; the next waits for it, so two quick
   * reports reach the core in the order they were made (each push is the
   * whole set, and a stale one landing last would unhide a row). iOS
   * `Session.hiddenPush`.
   */
  let pushing: Promise<void> = Promise.resolve();

  function pushHidden(): Promise<void> {
    if (push === null) return pushing;
    const ids = [...hidden];
    const send = push;
    pushing = pushing.then(() => send(ids)).catch((err: unknown) => {
      console.error("could not hand the hidden events to the core", err);
    });
    return pushing;
  }

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

    /** Hides a reported event on this device, remembers it, and tells the core. */
    hide(eventId: string): void {
      hidden = new Set(hidden).add(eventId);
      try {
        storage?.setItem(HIDDEN_EVENTS_KEY, JSON.stringify([...hidden]));
      } catch {
        // Still hidden for this run.
      }
      void pushHidden();
    },
    /**
     * Hands the core the whole hidden set. At every session start (the core
     * forgets the set when an account starts) and, from `hide`, whenever it
     * grows. Resolves once this push (and every earlier one) has landed; a
     * failed push is logged, not thrown.
     */
    pushHidden,

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

export const safetyStore = createSafetyStore(appStorage(), setHiddenEvents);
