// Covers `roomInfoView.ts`'s pure display helpers.

import { describe, expect, it } from "vitest";
import { initial, memberDisplayName, roomDisplayName, splitSigil } from "./roomInfoView";

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

describe("splitSigil", () => {
  it("returns a null sigil for a string with no recognized leading sigil", () => {
    expect(splitSigil("example.org")).toEqual({ sigil: null, rest: "example.org" });
  });

  it("splits a user id on the leading @", () => {
    expect(splitSigil("@alice:example.org")).toEqual({
      sigil: "@",
      rest: "alice:example.org",
    });
  });

  it("splits a room alias on the leading #", () => {
    expect(splitSigil("#ops:example.org")).toEqual({ sigil: "#", rest: "ops:example.org" });
  });

  it("splits a room id on the leading !", () => {
    expect(splitSigil("!abc123:example.org")).toEqual({
      sigil: "!",
      rest: "abc123:example.org",
    });
  });

  it("splits an event id on the leading $", () => {
    expect(splitSigil("$eventabc:example.org")).toEqual({
      sigil: "$",
      rest: "eventabc:example.org",
    });
  });

  it("splits a space id on the leading +", () => {
    expect(splitSigil("+space:example.org")).toEqual({
      sigil: "+",
      rest: "space:example.org",
    });
  });

  it("returns a null sigil for an empty string", () => {
    expect(splitSigil("")).toEqual({ sigil: null, rest: "" });
  });

  it("returns an empty rest for a string that is only a sigil", () => {
    expect(splitSigil("#")).toEqual({ sigil: "#", rest: "" });
  });
});
