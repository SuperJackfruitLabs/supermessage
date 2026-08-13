import { describe, expect, it } from "vitest";
import { shouldMarkRead, type ReadStateInput } from "./readTracking";

function input(overrides: Partial<ReadStateInput> = {}): ReadStateInput {
  return {
    followBottom: true,
    windowFocused: true,
    lastItemId: "$new",
    lastMarkedId: null,
    ...overrides,
  };
}

describe("shouldMarkRead", () => {
  it("is true when at the live end, focused, with a new item to mark", () => {
    expect(shouldMarkRead(input())).toBe(true);
  });

  it("is false when scrolled up into history", () => {
    expect(shouldMarkRead(input({ followBottom: false }))).toBe(false);
  });

  it("is false when the window is not focused, even at the live end", () => {
    // The exact case the brief warns against: a background window must not
    // mark a room read just because it happens to be scrolled to the bottom.
    expect(shouldMarkRead(input({ windowFocused: false }))).toBe(false);
  });

  it("is false when there is no item to mark", () => {
    expect(shouldMarkRead(input({ lastItemId: null }))).toBe(false);
  });

  it("is false once the latest item has already been marked", () => {
    expect(shouldMarkRead(input({ lastItemId: "$a", lastMarkedId: "$a" }))).toBe(false);
  });

  it("is true again once a newer item arrives after the last mark", () => {
    expect(shouldMarkRead(input({ lastItemId: "$b", lastMarkedId: "$a" }))).toBe(true);
  });

  it("requires every condition at once, not any single one", () => {
    expect(
      shouldMarkRead({
        followBottom: false,
        windowFocused: false,
        lastItemId: null,
        lastMarkedId: null,
      }),
    ).toBe(false);
  });
});
