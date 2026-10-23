import { describe, expect, it } from "vitest";
import type { NeedsYouInbox, RoomRow, RoomStatus, RosterRow } from "$lib/ipc";
import { roomRow, roomSummary } from "$lib/fixtures/rooms";
import { createDestinations, DESTINATIONS, destinationLabel } from "./destinations.svelte";

function entry(id: string): RosterRow {
  return { row: roomRow({ room: roomSummary({ id, name: id }) }), state: "needsYou", describesAgent: true };
}
function inbox(decisions: string[], invitations: string[]): NeedsYouInbox {
  return {
    decisions: decisions.map(entry),
    invitations: invitations.map(entry),
    count: decisions.length + invitations.length,
    badgeCount: decisions.length,
  };
}
const rows = (...ids: string[]): RoomRow[] => ids.map((id) => roomRow({ room: roomSummary({ id, name: id }) }));
const WORDS: Record<RoomStatus, string> = {
  working: "Working",
  needsYou: "Needs you",
  active: "Active",
  idle: "Idle",
  quiet: "Quiet",
};

function make(overrides: Partial<Parameters<typeof createDestinations>[0]> = {}) {
  return createDestinations({
    rosterNeedsYouInbox: async () => inbox([], []),
    rosterAgentDirectory: async () => [],
    roomStatus: async () => "active",
    roomStatusWord: async (s) => WORDS[s],
    ...overrides,
  });
}

describe("destinations", () => {
  it("the four, in iOS's order, with iOS's titles", () => {
    expect(DESTINATIONS).toEqual([
      { id: "chats", title: "Chats" },
      { id: "needsYou", title: "Needs you" },
      { id: "agents", title: "Agents" },
      { id: "search", title: "Search" },
    ]);
    expect(make().selected).toBe("chats");
  });

  it("the badge counts decisions only", async () => {
    const d = make({ rosterNeedsYouInbox: async () => inbox(["!a", "!b"], ["!c"]) });
    await d.refresh(rows("!a"), 1, null, false);
    expect(d.inbox?.count).toBe(3);
    expect(d.badgeCount).toBe(2);
    expect(destinationLabel("needsYou", 2)).toBe("Needs you, 2");
    expect(destinationLabel("needsYou", 0)).toBe("Needs you");
    expect(destinationLabel("agents", 2)).toBe("Agents");
  });

  it("an invitation alone lights no badge", async () => {
    const d = make({ rosterNeedsYouInbox: async () => inbox([], ["!c"]) });
    await d.refresh(rows("!c"), 1, null, false);
    expect(d.inbox?.count).toBe(1);
    expect(d.badgeCount).toBe(0);
  });

  it("the badge goes when the decision is answered", async () => {
    let next = inbox(["!a"], []);
    const d = make({ rosterNeedsYouInbox: async () => next });
    await d.refresh(rows("!a"), 1, null, false);
    expect(d.badgeCount).toBe(1);
    next = inbox([], []);
    await d.refresh(rows("!a", "!b"), 2, null, false);
    expect(d.badgeCount).toBe(0);
  });

  it("an older roster's inbox never overwrites a newer one", async () => {
    for (let run = 0; run < 3; run++) {
      let release!: () => void;
      const gate = new Promise<void>((r) => (release = r));
      let n = 0;
      const d = make({
        rosterNeedsYouInbox: async () => {
          if (n++ === 0) {
            await gate;
            return inbox(["!old"], []);
          }
          return inbox([], []);
        },
      });
      const first = d.refresh(rows("!a"), 1, null, false);
      await d.refresh(rows("!a", "!b"), 2, null, false);
      release();
      await first;
      expect(d.badgeCount).toBe(0);
      expect(d.inbox?.count).toBe(0);
    }
  });

  it("the roster is asked once per roster, not once per turn step", async () => {
    let inboxCalls = 0;
    let directoryCalls = 0;
    const d = make({
      rosterNeedsYouInbox: async () => (inboxCalls++, inbox([], [])),
      rosterAgentDirectory: async () => (directoryCalls++, []),
    });
    const same = rows("!a");
    await d.refresh(same, 1, "!a", false);
    await d.refresh(same, 1, "!a", true);
    await d.refresh(same, 1, "!a", false);
    expect([inboxCalls, directoryCalls]).toEqual([1, 1]);
  });

  it("a failed read of the roster is asked again on the next refresh", async () => {
    let calls = 0;
    const d = make({
      rosterNeedsYouInbox: async () => {
        calls++;
        if (calls === 1) throw new Error("offline");
        return inbox(["!a"], []);
      },
    });
    const same = rows("!a");
    const log = console.error;
    console.error = () => {};
    try {
      await d.refresh(same, 1, "!a", false);
    } finally {
      console.error = log;
    }
    expect(d.badgeCount).toBe(0);
    await d.refresh(same, 1, "!a", false);
    expect(calls).toBe(2);
    expect(d.badgeCount).toBe(1);
  });

  it("statuses are asked for only while Agents shows", async () => {
    const asked: boolean[] = [];
    const d = make({
      rosterAgentDirectory: async () => [entry("!a"), entry("!b")],
      roomStatus: async (_s, _d, turn) => (asked.push(turn), turn ? "working" : "active"),
    });
    const same = rows("!a", "!b");
    await d.refresh(same, 1, "!a", true);
    expect(asked).toEqual([]);
    d.select("agents");
    await d.refresh(same, 1, "!a", true);
    // Choosing Agents reads the two once, and the refresh after it once more: two asks per read.
    expect(asked).toEqual([true, false, true, false]);
    expect(d.statusOf("!a")).toEqual({ status: "working", word: "Working" });
    expect(d.statusOf("!b")).toEqual({ status: "active", word: "Active" });
    expect(d.agents.map((a) => a.row.room.id)).toEqual(["!a", "!b"]);
  });

  it("reset returns to Chats and forgets the inbox", async () => {
    const d = make({
      rosterNeedsYouInbox: async () => inbox(["!a"], []),
      rosterAgentDirectory: async () => [entry("!a")],
    });
    d.select("agents");
    await d.refresh(rows("!a"), 1, "!a", false);
    expect(d.agents).toHaveLength(1);
    d.reset();
    expect(d.selected).toBe("chats");
    expect(d.inbox).toBeNull();
    expect(d.badgeCount).toBe(0);
    expect(d.agents).toEqual([]);
    expect(d.statusOf("!a")).toBeNull();
  });

  it("a refresh begun before reset cannot bring the old account back", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const d = make({
      rosterNeedsYouInbox: async () => {
        await gate;
        return inbox(["!old"], []);
      },
    });
    const pending = d.refresh(rows("!a"), 1, null, false);
    d.reset();
    release();
    await pending;
    expect(d.inbox).toBeNull();
    expect(d.badgeCount).toBe(0);
  });
});
