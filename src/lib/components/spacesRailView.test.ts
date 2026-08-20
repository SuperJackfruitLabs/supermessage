// The two rules the rail cannot get wrong, and one it is easy to get
// subtly wrong.
//
// The first — **no spaces, no rail** (spaces-rail design §6) — is the reason
// `railEntries` exists as a function at all rather than as an
// `{#if spaces.length}` in `SpacesRail.svelte`: with the rule inside a
// component this project's vitest setup (`environment: "node"`, no component
// tests) could not reach it, and "most accounts render an empty 56px strip
// with one inert button" is exactly the kind of regression that ships
// unnoticed because it looks deliberate.
//
// The second is that **"All rooms" is always there and always first**. A
// reader who selects a space has no other way back.
//
// The third is the label. The rail is icon-only, so the label *is* the
// entry: it is the accessible name and the hover title, and nothing else on
// screen says which space a circle stands for.

import { describe, expect, it } from "vitest";
import type { SpaceSummary } from "$lib/ipc";
import { railEntries, spaceEntryLabel } from "./spacesRailView";

/** The subset of the core's parse these tests actually depend on. */
function identityOf(name: string): SpaceSummary["identity"] {
  const [head, role] = name.split(/\s+—\s*/, 2);
  const parts = (head ?? "").split(" ");
  const glyph = parts.length > 1 && /^[^\u0000-\u007f]/.test(parts[0]!) ? parts[0]! : null;
  const label = glyph === null ? (head ?? "") : parts.slice(1).join(" ");
  return {
    glyph,
    name: label,
    role: role ?? null,
    initial: glyph ?? (label.slice(0, 1).toUpperCase() || "?"),
  };
}

/**
 * A space as the core delivers one, with its name already parsed.
 *
 * The parse itself is `core::room_identity`'s and is tested there — including
 * the glyph, the em-dash split and the bounds. What is under test here is what
 * the rail *does* with an identity: which halves it joins, in what order, and
 * what it says about a count. So the fixture parses just enough to feed that,
 * rather than restating a grammar this file does not own.
 */
function space(overrides: Partial<SpaceSummary> = {}): SpaceSummary {
  const name = overrides.name ?? "Fleet";
  return {
    id: "!space:x.org",
    name,
    identity: identityOf(name),
    avatarUrl: null,
    childCount: 3,
    membership: "joined",
    ...overrides,
  };
}

describe("railEntries", () => {
  it("renders no rail at all for an account with no spaces", () => {
    // Not "a rail containing only All rooms": an empty list is how the
    // component is told not to render. If this ever returns the All-rooms
    // entry for an empty account, every account without spaces grows a
    // permanent strip holding one button that filters nothing.
    expect(railEntries([])).toEqual([]);
  });

  it("puts All rooms first, carrying the null selection spaceSelect takes", () => {
    const entries = railEntries([space({ id: "!a:x.org", name: "Alpha" })]);

    expect(entries).toHaveLength(2);
    expect(entries[0]).toEqual({
      spaceId: null,
      label: "All rooms",
      initial: "All",
      pending: false,
    });
    expect(entries[1]!.spaceId).toBe("!a:x.org");
  });

  it("keeps the spaces in the order the core sorted them, after All rooms", () => {
    const entries = railEntries([
      space({ id: "!a:x.org", name: "Alpha" }),
      space({ id: "!b:x.org", name: "Beta" }),
      space({ id: "!c:x.org", name: "Gamma" }),
    ]);

    expect(entries.map((entry) => entry.spaceId)).toEqual([
      null,
      "!a:x.org",
      "!b:x.org",
      "!c:x.org",
    ]);
  });

  it("falls back to the parsed glyph, then the parsed initial — never the raw first character", () => {
    // "🚀 Launch — Ops" must not yield "🚀" from a naive `name[0]`, which is
    // half a surrogate pair; and the un-glyphed space's initial comes from
    // the parsed name, so a leading glyph never becomes the letter.
    const entries = railEntries([
      space({ id: "!g:x.org", name: "🚀 Launch — Ops" }),
      space({ id: "!p:x.org", name: "platform" }),
    ]);

    expect(entries[1]!.initial).toBe("🚀");
    expect(entries[2]!.initial).toBe("P");
  });
});

describe("spaceEntryLabel", () => {
  it("names the space, its role and how many rooms selecting it shows", () => {
    expect(spaceEntryLabel(space({ name: "🚀 Launch — Ops", childCount: 4 }))).toBe(
      "Launch, Ops, 4 rooms",
    );
  });

  it("omits the role for a space whose name has no em-dash structure", () => {
    expect(spaceEntryLabel(space({ name: "platform", childCount: 2 }))).toBe("platform, 2 rooms");
  });

  it("says an empty space is empty rather than staying silent about it", () => {
    // `childCount: 0` is a real, expected value — a space whose joined
    // children are all gone. Saying nothing would leave the reader to
    // discover it by selecting the space and finding an empty roster.
    expect(spaceEntryLabel(space({ name: "Archive", childCount: 0 }))).toBe("Archive, No rooms");
  });

  it("does not pluralize a single room", () => {
    expect(spaceEntryLabel(space({ name: "Solo", childCount: 1 }))).toBe("Solo, 1 room");
  });

  it("omits the count entirely for a number the core could not have sent", () => {
    // Defence in depth: "No rooms" is a specific claim, and a wrong specific
    // claim is worse than silence about a count nobody can trust.
    expect(spaceEntryLabel(space({ name: "Broken", childCount: Number.NaN }))).toBe("Broken");
    expect(spaceEntryLabel(space({ name: "Broken", childCount: -1 }))).toBe("Broken");
  });

  it("labels from the parsed identity, never from the raw name", () => {
    // The bound itself moved to `core::room_identity`, which caps the name at
    // 120 code points and the role at 40 and has its own test for it. What
    // still matters here — and what this asserts — is that the label is built
    // out of the *parsed* halves rather than interpolating `space.name`,
    // because a space name is server-controlled text and this label lands in
    // both `aria-label` and `title`.
    const label = spaceEntryLabel(
      space({
        name: "raw name that must not appear",
        identity: { glyph: null, name: "Parsed", role: "Ops", initial: "P" },
        childCount: 1,
      }),
    );

    expect(label).toBe("Parsed, Ops, 1 room");
    expect(label).not.toContain("raw name");
  });
});

describe("an invitation in the rail", () => {
  it("is marked pending, so a click can offer Accept instead of filtering", () => {
    // Selecting a space you have not joined cannot work — the core answers
    // `unknownSpace`, correctly, because there is no subtree to scope the
    // roster to. The entry has to say so, or the rail's one click handler
    // has no way to tell the two kinds apart.
    const entries = railEntries([
      space({ id: "!joined:x.org", name: "Fleet" }),
      space({ id: "!invited:x.org", name: "guild", childCount: 0, membership: "invited" }),
    ]);

    expect(entries.map((entry) => [entry.spaceId, entry.pending])).toEqual([
      [null, false],
      ["!joined:x.org", false],
      ["!invited:x.org", true],
    ]);
  });

  it("says it is an invitation instead of counting rooms it cannot see", () => {
    // "No rooms" would be a claim about the space's contents. We are not in
    // it; we do not know its contents. What we know is that we were asked.
    const label = spaceEntryLabel(
      space({ name: "guild", childCount: 0, membership: "invited" }),
    );

    expect(label).toBe("guild, Invitation");
  });

  it("still names a role the space carries", () => {
    const label = spaceEntryLabel(
      space({ name: "\u2699 guild — Work", childCount: 0, membership: "invited" }),
    );

    expect(label).toBe("guild, Work, Invitation");
  });
});
