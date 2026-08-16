import { describe, expect, it } from "vitest";
import { EMOJI, QUICK_REACTIONS, searchEmoji, SEARCH_LIMIT } from "./emojiPicker";

describe("searchEmoji", () => {
  it("offers the whole list for an empty query, so the picker can be browsed", () => {
    // An empty result for an empty box would make the picker unusable by
    // anyone who does not already know the name of what they want.
    expect(searchEmoji("")).toHaveLength(Math.min(EMOJI.length, SEARCH_LIMIT));
    expect(searchEmoji("   ")).toHaveLength(Math.min(EMOJI.length, SEARCH_LIMIT));
  });

  it("puts a name that starts with the query first", () => {
    // The whole reason ranking exists: "fire" must find 🔥 before anything
    // that merely contains those letters somewhere.
    expect(searchEmoji("fire")[0]!.char).toBe("🔥");
    expect(searchEmoji("rocket")[0]!.char).toBe("🚀");
  });

  it("finds by keyword when the name would not", () => {
    // Nobody types "check mark" when they mean done.
    expect(searchEmoji("done").map((e) => e.char)).toContain("✅");
    expect(searchEmoji("lgtm").map((e) => e.char)).toContain("👍");
    expect(searchEmoji("deploy").map((e) => e.char)).toContain("🚀");
  });

  it("ignores case and surrounding space", () => {
    expect(searchEmoji("  BUG ").map((e) => e.char)).toContain("🐛");
  });

  it("finds an emoji pasted as itself", () => {
    // Pasting the character is a real way people reach for one, and it is the
    // one query that is not a word.
    expect(searchEmoji("🧠")).toEqual([EMOJI.find((e) => e.char === "🧠")]);
  });

  it("returns nothing rather than everything for a query that matches nothing", () => {
    // Falling back to the full list would look like the search silently
    // failed, which is worse than an empty grid.
    expect(searchEmoji("zzzzzz")).toEqual([]);
  });

  it("honours the limit", () => {
    expect(searchEmoji("", 5)).toHaveLength(5);
  });
});

describe("the emoji list itself", () => {
  it("has no duplicate characters", () => {
    // A duplicate would render twice and toggle the same reaction twice.
    const chars = EMOJI.map((e) => e.char);
    expect(new Set(chars).size).toBe(chars.length);
  });

  it("offers every quick reaction in the picker too", () => {
    // The inline six are a shortcut, not a separate vocabulary: a reader who
    // opens the picker looking for 👍 must find it there.
    const chars = new Set(EMOJI.map((e) => e.char));
    for (const quick of QUICK_REACTIONS) {
      expect(chars.has(quick)).toBe(true);
    }
  });

  it("gives every entry a name and at least one keyword", () => {
    for (const emoji of EMOJI) {
      expect(emoji.name.trim()).not.toBe("");
      expect(emoji.keywords.length).toBeGreaterThan(0);
    }
  });
});
