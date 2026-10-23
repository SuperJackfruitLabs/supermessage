// The chip row's state, with the core's answers faked: what is tested is who is asked, with
// what, and in what order — never the rule for which rooms a chip admits (that is the core's).

import { describe, expect, it, vi } from "vitest";
import type { RosterEmptyState, RosterFilter, RosterFilterChip, RosterSection } from "$lib/ipc";
import { createRosterFilter, nothingButInvitations, type RosterFilterDeps } from "./rosterFilter";

const section = (id: string, rooms: string[], title: string | null = null): RosterSection => ({
  id,
  title,
  detail: null,
  attention: false,
  rows: rooms.map((r) => ({ row: { room: { id: r } }, state: "quiet", describesAgent: false }) as never),
});

function memory(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial));
  return {
    map,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
  };
}

/** A core that keeps only rooms whose id starts with "u" for Unread. */
function fakeDeps(storage = memory(), over: Partial<RosterFilterDeps> = {}) {
  const calls = {
    chips: [] as RosterSection[][],
    fromKey: [] as string[],
    key: [] as RosterFilter[],
  };
  const deps: RosterFilterDeps = {
    rosterFilterSections: async (sections, filter) =>
      filter === "all"
        ? sections
        : sections
            .map((s) => ({ ...s, rows: s.rows.filter((r) => r.row.room.id.startsWith("u")) }))
            .filter((s) => s.rows.length > 0),
    rosterFilterChips: async (sections) => {
      calls.chips.push(sections);
      const all = sections.flatMap((s) => s.rows);
      return [
        { filter: "all", title: "All", count: all.length },
        { filter: "unread", title: "Unread", count: all.filter((r) => r.row.room.id.startsWith("u")).length },
      ] satisfies RosterFilterChip[];
    },
    rosterFilterFromKey: async (key) => {
      calls.fromKey.push(key);
      return key === "unread" ? "unread" : "all";
    },
    rosterFilterKey: async (filter) => {
      calls.key.push(filter);
      return `core:${filter}`;
    },
    rosterFilterEmptyState: async (filter): Promise<RosterEmptyState> => ({
      title: `core title for ${filter}`,
      description: `core description for ${filter}`,
    }),
    storage,
    ...over,
  };
  return { deps, calls, storage };
}

describe("createRosterFilter", () => {
  it("the stored chip is read through the core", async () => {
    const { deps, calls } = fakeDeps(memory({ "supermessage.roster.filter": "agents" }));
    const filter = createRosterFilter(deps);
    await filter.load();
    expect(calls.fromKey).toEqual(["agents"]);
    // The fake core reads an unoffered key as "all"; the tracker believes the core.
    expect(filter.selected).toBe("all");
  });

  it("a stored chip the core offers is the one in force", async () => {
    const { deps } = fakeDeps(memory({ "supermessage.roster.filter": "unread" }));
    const filter = createRosterFilter(deps);
    await filter.load();
    expect(filter.selected).toBe("unread");
  });

  it("nothing stored reads as the core's reading of nothing", async () => {
    const { deps, calls } = fakeDeps();
    const filter = createRosterFilter(deps);
    await filter.load();
    expect(calls.fromKey).toEqual([""]);
    expect(filter.selected).toBe("all");
  });

  it("choosing a chip stores the core's key", async () => {
    const { deps, calls, storage } = fakeDeps();
    const filter = createRosterFilter(deps);
    await filter.select("unread");
    expect(calls.key).toEqual(["unread"]);
    expect(storage.map.get("supermessage.roster.filter")).toBe("core:unread");
    expect(filter.selected).toBe("unread");
  });

  it("counts come from the whole arrangement", async () => {
    const { deps, calls } = fakeDeps();
    const filter = createRosterFilter(deps);
    await filter.select("unread");
    const arranged = [section("a", ["u1", "r2", "r3"])];
    const out = await filter.apply(arranged);
    expect(calls.chips).toEqual([arranged]);
    expect(out!.chips.map((c) => [c.filter, c.count])).toEqual([
      ["all", 3],
      ["unread", 1],
    ]);
    expect(out!.sections[0]!.rows).toHaveLength(1);
    expect(out!.empty).toBeNull();
  });

  it("a chip that leaves nothing has the core's words", async () => {
    const { deps } = fakeDeps();
    const filter = createRosterFilter(deps);
    await filter.select("unread");
    const out = await filter.apply([section("a", ["r1", "r2"])]);
    expect(out!.sections).toEqual([]);
    expect(out!.empty).toEqual({
      title: "core title for unread",
      description: "core description for unread",
    });
  });

  it("no rooms at all is not a chip's empty state", async () => {
    const { deps } = fakeDeps();
    const filter = createRosterFilter(deps);
    await filter.select("unread");
    expect((await filter.apply([]))!.empty).toBeNull();
  });

  it("an older apply never overwrites a newer one", async () => {
    for (let run = 0; run < 3; run++) {
      let release!: () => void;
      const gate = new Promise<void>((r) => (release = r));
      let first = true;
      const { deps } = fakeDeps(memory(), {
        rosterFilterChips: async (sections) => {
          if (first) {
            first = false;
            await gate;
          }
          return [{ filter: "all", title: "All", count: sections.flatMap((s) => s.rows).length }];
        },
      });
      const filter = createRosterFilter(deps);
      const older = filter.apply([section("a", ["r1"])]);
      const newer = await filter.apply([section("a", ["r1", "r2"])]);
      release();
      expect(await older).toBeNull();
      expect(newer!.chips[0]!.count).toBe(2);
    }
  });

  it("the chip survives sign-out but its counts do not", async () => {
    const { deps } = fakeDeps();
    const filter = createRosterFilter(deps);
    await filter.select("unread");
    const mine = await filter.apply([section("a", ["u1", "u2", "u3"])]);
    expect(mine!.chips.find((c) => c.filter === "unread")!.count).toBe(3);
    const theirs = await filter.apply([section("b", ["r1"])]);
    expect(filter.selected).toBe("unread");
    expect(theirs!.chips.find((c) => c.filter === "unread")!.count).toBe(0);
    expect(theirs!.chips.find((c) => c.filter === "all")!.count).toBe(1);
  });
});

describe("sectionTitles", () => {
  it("a .local host reads without it", async () => {
    const { deps } = fakeDeps();
    const ask = vi.fn(async (name: string) => name.replace(/\.local$/, ""));
    const titles = await createRosterFilter(deps).sectionTitles(
      [
        section("m1", ["a"], "Rakeshs-MacBook-Pro.local"),
        section("m2", ["b"], "Rakeshs-MacBook-Pro.local"),
        section("m3", ["c"], "foundry"),
        section("m4", ["d"], null),
      ],
      ask,
    );
    expect(ask).toHaveBeenCalledTimes(2);
    expect(titles.get("Rakeshs-MacBook-Pro.local")).toBe("Rakeshs-MacBook-Pro");
    expect(titles.get("foundry")).toBe("foundry");
    expect(titles.size).toBe(2);
  });
});

describe("nothingButInvitations", () => {
  it("says how many are waiting and where", () => {
    expect(nothingButInvitations(3)).toEqual({
      title: "Nothing but invitations",
      description: "3 waiting — open Invitations above.",
    });
  });
  it("is silent when none are hidden", () => {
    expect(nothingButInvitations(0)).toBeNull();
  });
});

describe("apply with hidden invitations", () => {
  it("every room filtered away with invitations hidden", async () => {
    const { deps } = fakeDeps();
    const filter = createRosterFilter(deps);
    const out = await filter.apply([], 2);
    expect(out!.empty).toEqual({
      title: "Nothing but invitations",
      description: "2 waiting — open Invitations above.",
    });
  });
});
