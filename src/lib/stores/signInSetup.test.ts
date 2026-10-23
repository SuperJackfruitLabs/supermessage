import { beforeEach, describe, expect, it } from "vitest";
import { clearFreshSignIn, markFreshSignIn } from "./freshSignIn";
import { setUpAfterSignIn } from "./signInSetup";

describe("setUpAfterSignIn", () => {
  beforeEach(() => clearFreshSignIn());

  it("a fresh sign-in asks for notifications once and still sets up recovery", () => {
    const asked: boolean[] = [];
    markFreshSignIn();
    const fresh = setUpAfterSignIn({ notificationPermission: async (p) => (asked.push(p), "granted") });
    // The caller sets recovery up from this: the mark must survive being read for the ask.
    expect(fresh).toBe(true);
    expect(asked).toEqual([true]);
  });

  it("a restore only reads the permission, and sets no recovery up", () => {
    const asked: boolean[] = [];
    const fresh = setUpAfterSignIn({ notificationPermission: async (p) => (asked.push(p), "granted") });
    expect(fresh).toBe(false);
    expect(asked).toEqual([false]);
  });

  it("a failed permission check is silent", () => {
    markFreshSignIn();
    expect(() => setUpAfterSignIn({ notificationPermission: async () => { throw new Error("no"); } })).not.toThrow();
  });
});
