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
import { shouldRepin } from "./timelineFollow";

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
