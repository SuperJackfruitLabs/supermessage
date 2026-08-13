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

  it("bounds an astral-heavy name without splitting a surrogate pair", () => {
    // An emoji-heavy hostile name reaches the 120/40-code-unit boundary far
    // sooner than an ASCII one does — each 🧠 is two UTF-16 code units, so a
    // naive `.slice(0, max)` lands mid-pair long before 120 *characters* of
    // ASCII would. `bound()` must count code points, not code units.
    const astral = "🧠".repeat(200);
    const parsed = parseRoomIdentity(`${astral} — ${astral}`);
    expect([...parsed.name].length).toBeLessThanOrEqual(120);
    expect([...parsed.role!].length).toBeLessThanOrEqual(40);
    // A lone (unpaired) surrogate is the concrete symptom of a code-unit
    // cut. Rebuilding each value from its own code points and comparing
    // back is a stronger check than a regex: if the string round-trips
    // unchanged, every code point in it is intact.
    expect([...parsed.name].join("")).toBe(parsed.name);
    expect([...parsed.role!].join("")).toBe(parsed.role);
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
