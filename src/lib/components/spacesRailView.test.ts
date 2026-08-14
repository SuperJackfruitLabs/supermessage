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

function space(overrides: Partial<SpaceSummary> = {}): SpaceSummary {
  return {
    id: "!space:x.org",
    name: "Fleet",
    avatarUrl: null,
    childCount: 3,
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
    expect(entries[0]).toEqual({ spaceId: null, label: "All rooms", initial: "All" });
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

  it("bounds a hostile space name instead of putting it in an aria-label whole", () => {
    // A space name is server-controlled text, and this label lands in both
    // `aria-label` and `title`. `parseRoomIdentity` caps the name at 120
    // code points and the role at 40; this asserts the label actually goes
    // through that parse rather than interpolating `space.name` directly.
    const label = spaceEntryLabel(
      space({ name: `${"a".repeat(500)} — ${"b".repeat(500)}`, childCount: 1 }),
    );

    expect(label).toBe(`${"a".repeat(120)}, ${"b".repeat(40)}, 1 room`);
  });
});
