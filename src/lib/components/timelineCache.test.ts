import { beforeEach, describe, expect, test } from "vitest";
import {
  forgetAllCaches,
  MAX_REMEMBERED_ROOMS,
  recallCache,
  rememberCache,
} from "./timelineCache";

/** Stand-in for virtua's opaque `CacheSnapshot`. */
const snap = (tag: string) => [tag] as unknown as never;

beforeEach(() => forgetAllCaches());

describe("remembering a room's measurements", () => {
  test("a room gets its own measurements back", () => {
    rememberCache("!a:x.org", snap("a"), 40);
    expect(recallCache("!a:x.org", 40)).toEqual(snap("a"));
  });

  test("a room that was never left has nothing to restore", () => {
    expect(recallCache("!never:x.org", 40)).toBeUndefined();
  });

  test("rooms do not borrow each other's measurements", () => {
    // Restoring room B's sizes into room A would mis-measure every row and
    // scroll to the wrong place — worse than measuring from scratch.
    rememberCache("!a:x.org", snap("a"), 40);
    expect(recallCache("!b:x.org", 40)).toBeUndefined();
  });
});

describe("only restoring a snapshot that still fits", () => {
  test("a room that grew while away measures itself again", () => {
    // virtua is explicit that a snapshot is only valid for the item count it
    // was taken at: "The length of items should be the same as when you take
    // the snapshot, otherwise restoration may not work as expected." Messages
    // arriving while the reader is in another room change that count, and a
    // stale snapshot would place rows at offsets that no longer exist.
    rememberCache("!a:x.org", snap("a"), 40);
    expect(recallCache("!a:x.org", 43)).toBeUndefined();
  });

  test("a room that shrank while away measures itself again", () => {
    rememberCache("!a:x.org", snap("a"), 40);
    expect(recallCache("!a:x.org", 12)).toBeUndefined();
  });
});

describe("staying bounded", () => {
  test("only the most recently left rooms are kept", () => {
    // A snapshot holds one entry per measured row, so an unbounded map would
    // grow with every room the reader has ever opened and never shrink.
    for (let i = 0; i < MAX_REMEMBERED_ROOMS + 3; i += 1) {
      rememberCache(`!room${i}:x.org`, snap(`s${i}`), 10);
    }

    // The three oldest were evicted; the newest survive.
    expect(recallCache("!room0:x.org", 10)).toBeUndefined();
    expect(recallCache("!room2:x.org", 10)).toBeUndefined();
    const newest = MAX_REMEMBERED_ROOMS + 2;
    expect(recallCache(`!room${newest}:x.org`, 10)).toEqual(snap(`s${newest}`));
  });

  test("re-remembering a room refreshes its place in the queue", () => {
    // The room a reader keeps returning to is the one worth keeping.
    rememberCache("!keep:x.org", snap("old"), 10);
    for (let i = 0; i < MAX_REMEMBERED_ROOMS - 1; i += 1) {
      rememberCache(`!filler${i}:x.org`, snap(`f${i}`), 10);
    }
    rememberCache("!keep:x.org", snap("new"), 10);
    rememberCache("!one-more:x.org", snap("extra"), 10);

    expect(recallCache("!keep:x.org", 10)).toEqual(snap("new"));
  });
});
