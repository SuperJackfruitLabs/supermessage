import { describe, expect, it } from "vitest";

import { connectionSubtitle } from "./connectionWords";

describe("connectionSubtitle", () => {
  it("uses iOS's words, with the ellipsis character (U+2026)", () => {
    expect(connectionSubtitle("syncing")).toBe("Connecting…");
    expect(connectionSubtitle("offline")).toBe("Offline");
    expect(connectionSubtitle("error")).toBe("Reconnecting…");
  });

  it("says nothing when live", () => {
    expect(connectionSubtitle("live")).toBeNull();
  });
});
