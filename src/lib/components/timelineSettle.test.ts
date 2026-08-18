import { describe, expect, test } from "vitest";
import { shouldSettleAtBottom } from "./timelineFollow";

describe("landing on the newest row when a room opens", () => {
  test("the first content to arrive settles the list at the bottom", () => {
    // The regression this exists to stop: a room's whole history arrives as one
    // `insert` batch, `shouldRepin` discards it as the first observation, and
    // the reader is left at offset 0 looking at the oldest message loaded.
    expect(shouldSettleAtBottom({ viewport: 911, content: 0 }, { viewport: 911, content: 2283 }, false)).toBe(true);
  });

  test("it fires once — after that the tail rules own the list", () => {
    // Firing again would drag a reader who has scrolled up back to the bottom
    // every time a row is measured.
    expect(shouldSettleAtBottom({ viewport: 911, content: 2283 }, { viewport: 911, content: 4000 }, true)).toBe(false);
  });

  test("an empty room does not count as arrival", () => {
    expect(shouldSettleAtBottom({ viewport: 911, content: 0 }, { viewport: 911, content: 0 }, false)).toBe(false);
  });

  test("content that was already there is growth, not arrival", () => {
    // `shouldRepin` handles this case, and it consults `followBottom` — which
    // this deliberately does not, because a room opening has no prior scroll
    // position for the reader to have chosen.
    expect(shouldSettleAtBottom({ viewport: 911, content: 800 }, { viewport: 911, content: 2283 }, false)).toBe(false);
  });

  test("a pane that has height but no rows yet is still empty", () => {
    expect(shouldSettleAtBottom({ viewport: 911, content: 0 }, { viewport: 400, content: 1200 }, false)).toBe(true);
  });
});
