// Whether a reader who was following the tail gets carried back to it when the
// pane changes shape under them.
//
// Both regressions these guard were measured in the running app on 2026-08-17,
// in one room against a live agent. See `shouldRepin`'s doc comment for the
// full numbers; in short:
//
//   viewport 911 -> 565   live panel opens, tail slides 393px under the fold,
//                         and one pixel of trackpad twitch then strands the
//                         finished reply at fromBottom 1558
//   content 6043 -> 7747  a 1721px reply arrives and lands entirely below the
//                         fold, because the one-shot scrollToIndex aimed at an
//                         estimate before virtua had measured the row

import { describe, expect, it } from "vitest";
import {
  isReaderScroll,
  nextFollowBottom,
  READER_INPUT_WINDOW_MS,
  shouldRequestOlder,
  shouldShiftNow,
  shouldRepin,
  shouldRepinAfterScroll,
} from "./timelineFollow";

/** Shorthand for the two numbers that decide where the tail is. */
function pane(viewport: number, content: number) {
  return { viewport, content };
}

describe("shouldRepin", () => {
  it("carries a following reader when the viewport shrinks", () => {
    // The live panel opening: 911 -> 565, content unchanged.
    expect(shouldRepin(pane(911, 6043), pane(565, 6043), true)).toBe(true);
  });

  it("carries a following reader when the content grows", () => {
    // The 1721px reply landing, viewport unchanged.
    expect(shouldRepin(pane(911, 6043), pane(911, 7747), true)).toBe(true);
  });

  it("carries a following reader when virtua remeasures a row taller", () => {
    // The correction that the one-shot scroll never waited for. Small, and
    // exactly as worth following as the arrival itself.
    expect(shouldRepin(pane(911, 7414), pane(911, 7431), true)).toBe(true);
  });

  it("leaves a reader who scrolled away exactly where they are", () => {
    // The whole point of `followBottom`, and it outranks both triggers.
    expect(shouldRepin(pane(911, 6043), pane(565, 7747), false)).toBe(false);
  });

  it("does nothing when the viewport grows back", () => {
    // The live panel closing. The tail is already coming back into view.
    expect(shouldRepin(pane(565, 6043), pane(911, 6043), true)).toBe(false);
  });

  it("does nothing when the content shrinks", () => {
    // A redaction, or virtua measuring a row shorter than it estimated. The
    // bottom moves towards the reader, not away.
    expect(shouldRepin(pane(911, 7747), pane(911, 6043), true)).toBe(false);
  });

  it("does nothing when neither measurement changed", () => {
    // A ResizeObserver fires for width changes too; only these two matter.
    expect(shouldRepin(pane(911, 6043), pane(911, 6043), true)).toBe(false);
  });

  it("does nothing on the very first observation", () => {
    // Nothing has been measured yet, so every real pane looks like content
    // growth against zero — which would scroll a reader who opened a room
    // part-way up.
    expect(shouldRepin(pane(0, 0), pane(911, 6043), true)).toBe(false);
  });

  it("still fires once a real measurement exists, even at zero content", () => {
    // Guards against the first-observation check being written as "either is
    // zero", which would swallow the first arriving message in an empty room.
    expect(shouldRepin(pane(911, 0), pane(911, 240), true)).toBe(true);
  });

  it("treats a one-pixel change as a change, since the threshold is elsewhere", () => {
    expect(shouldRepin(pane(911, 6043), pane(910, 6043), true)).toBe(true);
    expect(shouldRepin(pane(911, 6043), pane(911, 6044), true)).toBe(true);
  });

  it("does not need a prepend case, and would not fire on one anyway", () => {
    // Back-pagination grows content and `scrollTop` by the same amount
    // (virtua's `shift`), so the distance to the tail is unchanged and the
    // reader holds position. This fires — harmlessly, since a reader who is
    // following the tail is at the tail — but only because `followBottom` says
    // they were there to begin with.
    expect(shouldRepin(pane(911, 6043), pane(911, 8088), false)).toBe(false);
  });
});

// Whose scroll it was.
//
// Measured on 2026-10-03 opening "Buddhimaan" (a room of long agent answers)
// with a probe on `handleScroll` and the resize observer: 172ms after the
// room mounted, virtua corrected its own offset to 125 while 5189px of history
// landed. `handleScroll` read that as the reader being 4138px from the tail and
// cleared `followBottom` before the observer could re-pin, then the offset
// being under `TOP_THRESHOLD` fetched older history, which prepended more, four
// times in 200ms. The room opened at `scrollTop` 0 of 13018. Saraswati and
// Super Chotu opened 1505px and 2177px short the same way.
describe("isReaderScroll", () => {
  it("counts a scroll that follows the reader's own input", () => {
    expect(isReaderScroll(1000, 1000 + READER_INPUT_WINDOW_MS - 1)).toBe(true);
  });

  it("does not count a scroll long after the last input", () => {
    // The 172ms correction above came with no input at all.
    expect(isReaderScroll(1000, 1000 + READER_INPUT_WINDOW_MS + 1)).toBe(false);
  });

  it("does not count a scroll when the reader has not touched the pane", () => {
    expect(isReaderScroll(null, 5000)).toBe(false);
  });
});

describe("nextFollowBottom", () => {
  it("lets the reader leave the tail", () => {
    expect(nextFollowBottom(true, 4138, true)).toBe(false);
  });

  it("does not let a layout-driven scroll leave the tail", () => {
    // The Buddhimaan landing: 4138px away, and nobody scrolled.
    expect(nextFollowBottom(true, 4138, false)).toBe(true);
  });

  it("lets any scroll that reaches the tail resume following", () => {
    expect(nextFollowBottom(false, 10, false)).toBe(true);
    expect(nextFollowBottom(false, 10, true)).toBe(true);
  });

  it("keeps a reader who scrolled away where they are", () => {
    expect(nextFollowBottom(false, 4138, false)).toBe(false);
  });
});

describe("shouldRequestOlder", () => {
  const base = { offset: 50, readerDriven: true, scrollable: true, paginating: false, reachedStart: false };

  it("fetches history when the reader scrolls near the top", () => {
    expect(shouldRequestOlder(base)).toBe(true);
  });

  it("does not fetch on a layout-driven offset near the top", () => {
    // Each of the four fetches above came from a correction, not the reader.
    expect(shouldRequestOlder({ ...base, readerDriven: false })).toBe(false);
  });

  it("fills a pane the history does not yet cover, without waiting for a scroll", () => {
    // A short room cannot be scrolled, so waiting for the reader would never end.
    expect(shouldRequestOlder({ ...base, readerDriven: false, scrollable: false, offset: 0 })).toBe(true);
  });

  it("asks once at a time, and never past the start", () => {
    expect(shouldRequestOlder({ ...base, paginating: true })).toBe(false);
    expect(shouldRequestOlder({ ...base, reachedStart: true })).toBe(false);
    expect(shouldRequestOlder({ ...base, scrollable: false, reachedStart: true })).toBe(false);
  });

  it("leaves a reader far from the top alone", () => {
    expect(shouldRequestOlder({ ...base, offset: 900 })).toBe(false);
  });
});

// virtua freezes its rendered range after a `shift` update until the next
// scroll event. A list too short to scroll never sends one, so the range stayed
// at [0, -1]: on 2026-10-03 "Data Diana" had 25 items loaded and painted none,
// and one synthetic scroll event brought all 16 rows back.
describe("shouldShiftNow", () => {
  it("holds position for a head change in a list that scrolls", () => {
    expect(shouldShiftNow(true, true)).toBe(true);
  });

  it("never shifts a list that cannot scroll", () => {
    expect(shouldShiftNow(true, false)).toBe(false);
  });

  it("never shifts for a change at the tail", () => {
    expect(shouldShiftNow(false, true)).toBe(false);
  });
});

// The second half of the Buddhimaan landing, after the reader-scroll fix: the
// re-pin fired when 7314px arrived, then virtua measured rows shorter than its
// estimates and its jump compensation moved the offset to 0 while the content
// shrank to 7121. `shouldRepin` rightly ignores shrinking content, so nothing
// carried the still-following reader back.
describe("shouldRepinAfterScroll", () => {
  it("carries a following reader back after a scroll they did not make", () => {
    expect(shouldRepinAfterScroll(true, 6196, false)).toBe(true);
  });

  it("never fights the reader", () => {
    expect(shouldRepinAfterScroll(true, 6196, true)).toBe(false);
  });

  it("leaves a reader who is not following alone", () => {
    expect(shouldRepinAfterScroll(false, 6196, false)).toBe(false);
  });

  it("does nothing at the tail", () => {
    expect(shouldRepinAfterScroll(true, 0, false)).toBe(false);
  });
});
