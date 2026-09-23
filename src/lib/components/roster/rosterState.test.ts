import { describe, expect, it } from "vitest";

import { shownState } from "./rosterState";

describe("shownState", () => {
  it("says an agent's activity word", () => {
    expect(shownState("active", true)).toBe("active");
    expect(shownState("idle", true)).toBe("idle");
    expect(shownState("quiet", true)).toBe("quiet");
  });

  it("does not describe a room of people as an idle agent", () => {
    expect(shownState("active", false)).toBeNull();
    expect(shownState("idle", false)).toBeNull();
    expect(shownState("quiet", false)).toBeNull();
  });

  it("always says a room needs you, agent or not", () => {
    expect(shownState("needsYou", true)).toBe("needsYou");
    expect(shownState("needsYou", false)).toBe("needsYou");
  });
});
