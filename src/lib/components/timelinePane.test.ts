// What the message pane should be showing, and — the part that was wrong —
// what it should be showing *before it knows*.
//
// Measured on 2026-08-17, switching from one room to another. Four distinct
// visual states in 145ms:
//
//   ms=1    the outgoing room, 17 rows, settled
//   ms=10   "Nothing here yet."          <- the empty state, for a room with
//                                           1937px of history in it
//   ms=66   the list back, 0 rows, 911px of bare scroller
//   ms=126  13 rows mounted, all 13 invisible (virtua had not measured them)
//   ms=145  settled
//
// The first of those is the one that reads as a bug rather than a wait: the
// pane asserts a room is empty on the strength of never having asked.

import { describe, expect, it } from "vitest";
import { LOADING_AFTER_MS, paneState } from "./timelinePane";

describe("paneState", () => {
  it("shows the messages once there are any", () => {
    expect(paneState({ loaded: true, rowCount: 12, waitingMs: 0 })).toBe("rows");
  });

  it("shows the messages even while a room is still filling in behind them", () => {
    // Back-pagination and a re-seed both keep arriving after the first batch.
    // Whatever else is true, rows on screen beat any placeholder.
    expect(paneState({ loaded: false, rowCount: 12, waitingMs: 5_000 })).toBe("rows");
  });

  it("says a room is empty only once it has answered and had a moment", () => {
    expect(paneState({ loaded: true, rowCount: 0, waitingMs: LOADING_AFTER_MS })).toBe("empty");
  });

  it("does not call a room empty the instant it answers", () => {
    // The second half of the same bug, and the one measurement caught after
    // the first fix was in: the core opens every subscription with an empty
    // `Reset` and only then paginates history in, so "answered" lands a beat
    // before "has anything in it". With `loaded` alone deciding, "Nothing here
    // yet." still flashed 18ms into a switch. Emptiness is the only claim this
    // predicate makes that can be wrong, so it is the one that waits.
    expect(paneState({ loaded: true, rowCount: 0, waitingMs: 0 })).toBe("settling");
  });

  it("never says a room is empty before it has answered", () => {
    // The regression. This is the "Nothing here yet." that flashed over a room
    // holding 1937px of history.
    expect(paneState({ loaded: false, rowCount: 0, waitingMs: 0 })).not.toBe("empty");
  });

  it("waits quietly at first, because most switches land inside the flinch", () => {
    // The measured switch settled at 145ms. A spinner that appears and leaves
    // inside that window is itself the flicker — two more states, not fewer.
    expect(paneState({ loaded: false, rowCount: 0, waitingMs: 0 })).toBe("settling");
    expect(paneState({ loaded: false, rowCount: 0, waitingMs: LOADING_AFTER_MS - 1 })).toBe(
      "settling",
    );
    // Quiet whether or not the room has answered — before the threshold there
    // is nothing worth saying either way.
    expect(paneState({ loaded: true, rowCount: 0, waitingMs: LOADING_AFTER_MS - 1 })).toBe(
      "settling",
    );
  });

  it("admits it is loading once the wait is long enough to notice", () => {
    expect(paneState({ loaded: false, rowCount: 0, waitingMs: LOADING_AFTER_MS })).toBe("loading");
    expect(paneState({ loaded: false, rowCount: 0, waitingMs: 4_000 })).toBe("loading");
  });

  it("keeps quiet past the threshold once the answer is that the room is empty", () => {
    // A slow *and* empty room resolves to empty, not to a spinner that never
    // stops.
    expect(paneState({ loaded: true, rowCount: 0, waitingMs: 10_000 })).toBe("empty");
  });

  it("puts the threshold past a switch that lands promptly", () => {
    // Guards the constant itself against being tuned below what was measured.
    expect(LOADING_AFTER_MS).toBeGreaterThan(145);
  });
});
