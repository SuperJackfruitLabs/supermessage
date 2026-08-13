// Covers `roomInfoView.ts`'s pure display helpers.

import { describe, expect, it } from "vitest";
import { initial, memberDisplayName, roomDisplayName } from "./roomInfoView";

describe("roomDisplayName", () => {
  it("prefers the room's own name", () => {
    expect(roomDisplayName({ name: "Ops", roomId: "!abc:example.org" })).toBe("Ops");
  });

  it("falls back to the room id when unnamed", () => {
    expect(roomDisplayName({ name: null, roomId: "!abc:example.org" })).toBe("!abc:example.org");
  });

  it("falls back to the room id when the name is blank/whitespace-only", () => {
    expect(roomDisplayName({ name: "   ", roomId: "!abc:example.org" })).toBe("!abc:example.org");
  });
});

describe("memberDisplayName", () => {
  it("prefers the member's own display name", () => {
    expect(memberDisplayName({ displayName: "Alice", userId: "@alice:example.org" })).toBe(
      "Alice",
    );
  });

  it("falls back to the user id when unset", () => {
    expect(memberDisplayName({ displayName: null, userId: "@alice:example.org" })).toBe(
      "@alice:example.org",
    );
  });

  it("falls back to the user id when the display name is blank", () => {
    expect(memberDisplayName({ displayName: "  ", userId: "@alice:example.org" })).toBe(
      "@alice:example.org",
    );
  });
});

describe("initial", () => {
  it("returns the first character, uppercased", () => {
    expect(initial("ops room")).toBe("O");
  });

  it("returns ? for an empty or whitespace-only label", () => {
    expect(initial("")).toBe("?");
    expect(initial("   ")).toBe("?");
  });

  it("handles an astral-plane leading character (e.g. an emoji) as one whole code point", () => {
    // Indexing label[0] directly would take only half of the surrogate
    // pair — a broken glyph, not the emoji. See RoomList.svelte's identical
    // fix for the same bug.
    expect(initial("🧠 Buddhimaan")).toBe("🧠");
  });
});
