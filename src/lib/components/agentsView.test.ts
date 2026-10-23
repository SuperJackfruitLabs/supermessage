import { describe, expect, it } from "vitest";
import type { AgentState, RoomStatus, RosterRow } from "$lib/ipc";
import { roomRow, roomSummary } from "$lib/fixtures/rooms";
import { createAgentStatuses } from "./agentsView";

function agent(id: string, state: AgentState = "active"): RosterRow {
  return { row: roomRow({ room: roomSummary({ id, name: id }) }), state, describesAgent: true };
}

const WORDS: Record<RoomStatus, string> = {
  working: "Working",
  needsYou: "Needs you",
  active: "Active",
  idle: "Idle",
  quiet: "Quiet",
};

describe("createAgentStatuses", () => {
  it("only the open room can say Working", async () => {
    const calls: { state: AgentState; describesAgent: boolean; turnInProgress: boolean }[] = [];
    const statuses = createAgentStatuses({
      roomStatus: async (state, describesAgent, turnInProgress) => {
        calls.push({ state, describesAgent, turnInProgress });
        return turnInProgress ? "working" : "active";
      },
      roomStatusWord: async (s) => WORDS[s],
    });
    await statuses.refresh([agent("!a"), agent("!b")], "!a", true);
    expect(calls.map((c) => c.turnInProgress)).toEqual([true, false]);
    expect(calls.every((c) => c.describesAgent)).toBe(true);
    expect(statuses.statusOf("!a")).toEqual({ status: "working", word: "Working" });
    expect(statuses.statusOf("!b")).toEqual({ status: "active", word: "Active" });
    expect(statuses.statusOf("!missing")).toBeNull();
  });

  it("a status the core has nothing to say about is null", async () => {
    const statuses = createAgentStatuses({ roomStatus: async () => null, roomStatusWord: async (s) => WORDS[s] });
    await statuses.refresh([agent("!a")], null, false);
    expect(statuses.statusOf("!a")).toBeNull();
  });

  it("an older refresh never overwrites a newer one", async () => {
    for (let run = 0; run < 3; run++) {
      let release!: () => void;
      const gate = new Promise<void>((r) => (release = r));
      let n = 0;
      const statuses = createAgentStatuses({
        roomStatus: async () => {
          if (n++ === 0) {
            await gate;
            return "idle";
          }
          return "active";
        },
        roomStatusWord: async (s) => WORDS[s],
      });
      const first = statuses.refresh([agent("!a")], null, false);
      await statuses.refresh([agent("!a")], null, false);
      release();
      await first;
      expect(statuses.statusOf("!a")).toEqual({ status: "active", word: "Active" });
    }
  });
});
