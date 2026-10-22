// The mark is one-shot: only the sign-in that set it may act on it, and
// nothing else (a session restore, a remount) may find it still set.

import { beforeEach, describe, expect, it } from "vitest";
import { clearFreshSignIn, consumeFreshSignIn, markFreshSignIn } from "./freshSignIn";

describe("freshSignIn", () => {
  beforeEach(() => clearFreshSignIn());

  it("is not set by default, so a session restore never sees it", () => {
    expect(consumeFreshSignIn()).toBe(false);
  });

  it("is set after a sign-in and consumed exactly once", () => {
    markFreshSignIn();

    expect(consumeFreshSignIn()).toBe(true);
    expect(consumeFreshSignIn()).toBe(false);
  });
});
