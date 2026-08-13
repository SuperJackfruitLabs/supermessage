import { describe, expect, it } from "vitest";
import { parseRoomIdentity, relativeTime, roomInitial } from "./roomIdentity";

describe("parseRoomIdentity", () => {
  it("splits glyph, name and role on an em dash", () => {
    expect(parseRoomIdentity("🧠 Buddhimaan — Squad Lead")).toEqual({
      glyph: "🧠",
      name: "Buddhimaan",
      role: "Squad Lead",
    });
  });

  it("leaves a hyphenated name alone", () => {
    expect(parseRoomIdentity("aether-dispatches")).toEqual({
      glyph: null,
      name: "aether-dispatches",
      role: null,
    });
  });

  it("splits on the first em dash only", () => {
    expect(parseRoomIdentity("Coder Kai — Code — Build")).toEqual({
      glyph: null,
      name: "Coder Kai",
      role: "Code — Build",
    });
  });

  it("takes a whole astral glyph, never half a surrogate pair", () => {
    // The `initials()` bug this codebase already shipped: `raw[0]` on an
    // emoji-named room yields a lone surrogate and renders as tofu.
    const parsed = parseRoomIdentity("🛡️ Threat Hunter Theo — Security");
    expect(parsed.name).toBe("Threat Hunter Theo");
    expect(parsed.glyph).not.toBeNull();
    expect([...parsed.glyph!].length).toBeGreaterThan(0);
    expect(parsed.glyph!.codePointAt(0)).toBe(0x1f6e1);
  });

  it("does not treat a leading ASCII word as a glyph", () => {
    expect(parseRoomIdentity("Ops Room — Alerts").glyph).toBeNull();
  });

  it("does not treat a leading grapheme as a glyph without a following space", () => {
    expect(parseRoomIdentity("🧠Buddhimaan").glyph).toBeNull();
    expect(parseRoomIdentity("🧠Buddhimaan").name).toBe("🧠Buddhimaan");
  });

  it("yields null for an empty half rather than an empty string", () => {
    // A trailing dash with nothing after it still splits — the separator
    // needs whitespace *before* the dash, not after.
    expect(parseRoomIdentity("Buddhimaan —")).toEqual({
      glyph: null,
      name: "Buddhimaan",
      role: null,
    });
    // No whitespace before the dash, so this is not a separator at all.
    expect(parseRoomIdentity("— Squad Lead").name).toBe("— Squad Lead");
  });

  it("bounds a hostile role and name", () => {
    const long = "x".repeat(500);
    const parsed = parseRoomIdentity(`${long} — ${long}`);
    expect(parsed.name.length).toBeLessThanOrEqual(120);
    expect(parsed.role!.length).toBeLessThanOrEqual(40);
  });

  it("never returns an empty name", () => {
    expect(parseRoomIdentity("   ").name).toBe("Unnamed room");
    expect(parseRoomIdentity("").name).toBe("Unnamed room");
    expect(parseRoomIdentity(" — ").name).toBe("Unnamed room");
  });

  it("bounds a name/role at an odd offset without splitting a surrogate pair", () => {
    // `MAX_NAME_CHARS` (120) and `MAX_ROLE_CHARS` (40) are both even, and
    // every 𝕏 is a 2-code-unit surrogate pair — so an *all-astral* run cuts
    // cleanly at those lengths even under a naive `s.slice(0, max)` (which
    // counts UTF-16 code units), by pure coincidence of parity. That
    // coincidence would hide a code-unit-based regression completely. A
    // single leading BMP character ("a") shifts every following pair onto
    // an odd offset, so a naive slice lands its cut *inside* a pair instead
    // of between two: `"a" + "𝕏".repeat(200)` naively sliced to 120 code
    // units keeps "a" plus 119 units of astral content — 59 whole pairs and
    // one lone leading (high) surrogate.
    const name = `a${"𝕏".repeat(200)}`;
    const role = `a${"𝕏".repeat(60)}`;
    const parsed = parseRoomIdentity(`${name} — ${role}`);

    expect([...parsed.name].length).toBeLessThanOrEqual(120);
    expect([...parsed.role!].length).toBeLessThanOrEqual(40);

    // The concrete, direct symptom of a code-unit cut: iterating with
    // `[...s]` yields a lone surrogate as its own single-element
    // "character" once it's no longer paired. Assert every element is a
    // real code point outside the surrogate range, rather than a
    // round-trip check (`[...s].join("") === s` holds for *any* string,
    // including one containing a lone surrogate, so it proves nothing).
    for (const ch of parsed.name) {
      const cp = ch.codePointAt(0)!;
      expect(cp < 0xd800 || cp > 0xdfff).toBe(true);
    }
    for (const ch of parsed.role!) {
      const cp = ch.codePointAt(0)!;
      expect(cp < 0xd800 || cp > 0xdfff).toBe(true);
    }
  });

  it("keeps an 8-code-point ZWJ sequence as a glyph, at the boundary", () => {
    // "👩‍❤️‍💋‍👨" (the "kiss: woman, man" ZWJ sequence) is exactly 8 code
    // points: WOMAN, ZWJ, HEAVY BLACK HEART, VARIATION SELECTOR-16, ZWJ,
    // KISS MARK, ZWJ, MAN. It must still parse as a single glyph.
    const kiss = "👩‍❤️‍💋‍👨";
    expect([...kiss].length).toBe(8);
    const parsed = parseRoomIdentity(`${kiss} Newlyweds — Support`);
    expect(parsed.glyph).toBe(kiss);
    expect(parsed.name).toBe("Newlyweds");
  });

  it("rejects a long astral run as a glyph — it's a word, not a symbol", () => {
    // Nothing about "outside ASCII, followed by whitespace" otherwise stops
    // an enormous astral-only run with no internal whitespace from reading
    // as one giant "glyph" token. It must fall back to being read as (part
    // of) the name instead, whole and untruncated by the glyph path.
    const word = "𝕏".repeat(50_000);
    const parsed = parseRoomIdentity(`${word} Something`);
    expect(parsed.glyph).toBeNull();
    // Bounded by MAX_NAME_CHARS like any other long name — not silently
    // dropped, and not truncated to a handful of characters as a "glyph".
    expect([...parsed.name].length).toBeLessThanOrEqual(120);
  });
});

describe("roomInitial", () => {
  it("prefers the glyph", () => {
    expect(roomInitial({ glyph: "🧠", name: "Buddhimaan", role: null })).toBe("🧠");
  });

  it("falls back to the first code point of the name, uppercased", () => {
    expect(roomInitial({ glyph: null, name: "aether-dispatches", role: null })).toBe("A");
  });
});

describe("relativeTime", () => {
  const now = Date.UTC(2026, 7, 13, 12, 0, 0);

  it("returns null with no timestamp", () => {
    expect(relativeTime(null, now)).toBeNull();
  });

  it("reads the recent past in coarsening units", () => {
    expect(relativeTime(now - 20_000, now)).toBe("now");
    expect(relativeTime(now - 4 * 60_000, now)).toBe("4m");
    expect(relativeTime(now - 2 * 3_600_000, now)).toBe("2h");
    expect(relativeTime(now - 3 * 86_400_000, now)).toBe("3d");
  });

  it("falls back to a date beyond a week", () => {
    expect(relativeTime(now - 30 * 86_400_000, now)).toMatch(/\d/);
    expect(relativeTime(now - 30 * 86_400_000, now)).not.toMatch(/[dhm]$/);
  });

  it("does not print a negative age for a clock-skewed future timestamp", () => {
    expect(relativeTime(now + 60_000, now)).toBe("now");
  });
});
