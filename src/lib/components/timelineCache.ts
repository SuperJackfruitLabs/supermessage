// The row measurements a room had when the reader last left it.
//
// `VList` measures every row it renders, and throws all of it away when the
// component unmounts. `+page.svelte` remounts the whole timeline on a room
// switch (`{#key roomsStore.selectedId}`), so returning to a room means
// re-measuring it from nothing: rows land at estimated heights, then jump as
// their real ones arrive. virtua's answer is `CacheSnapshot` — taken from
// `VirtualizerHandle.getCache()` and handed back through the `cache` prop,
// which it reads *on mount*. That mount is the room switch, which is why this
// store is keyed by room and read exactly once per remount.
//
// **A snapshot is only valid for the item count it was taken at.** virtua says
// so plainly: "The length of items should be the same as when you take the
// snapshot, otherwise restoration may not work as expected." A room that
// received messages while the reader was elsewhere has a different count, and
// restoring into it would place rows at offsets that no longer describe them —
// which is worse than measuring afresh, because a wrong measurement scrolls to
// a wrong place with confidence. So the count travels with the snapshot and is
// checked on the way out.

import type { CacheSnapshot } from "virtua";

/**
 * How many rooms' measurements to keep.
 *
 * A snapshot holds an entry per measured row, so this map would otherwise grow
 * with every room the reader has ever opened and never shrink. The value is a
 * guess at how many rooms someone moves between in a session — enough that the
 * rooms they are actually working in stay warm.
 */
export const MAX_REMEMBERED_ROOMS = 8;

interface Remembered {
  snapshot: CacheSnapshot;
  /** The row count the snapshot describes. See the module comment. */
  rowCount: number;
}

/**
 * Insertion order is the eviction order, and `Map` preserves it — so
 * re-remembering a room deletes first, to move it back to the end.
 */
const remembered = new Map<string, Remembered>();

/** Keep this room's measurements for when the reader comes back. */
export function rememberCache(
  roomId: string,
  snapshot: CacheSnapshot,
  rowCount: number,
): void {
  remembered.delete(roomId);
  remembered.set(roomId, { snapshot, rowCount });

  while (remembered.size > MAX_REMEMBERED_ROOMS) {
    const oldest = remembered.keys().next();
    if (oldest.done) break;
    remembered.delete(oldest.value);
  }
}

/**
 * This room's measurements, if they still describe the list being mounted.
 *
 * Returns `undefined` whenever restoring would be a guess — an unknown room,
 * or one whose length moved while the reader was away.
 */
export function recallCache(
  roomId: string,
  rowCount: number,
): CacheSnapshot | undefined {
  const hit = remembered.get(roomId);
  if (hit === undefined || hit.rowCount !== rowCount) return undefined;
  return hit.snapshot;
}

/** Drop everything. For tests, and for signing out. */
export function forgetAllCaches(): void {
  remembered.clear();
}
