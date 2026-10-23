import { describe, expect, it } from "vitest";
import type { RoomCast, TurnSnapshot, TurnStep, TurnSummary, TurnUpdate } from "$lib/ipc";
import { applyTurnUpdate, emptyTurn, restoreTurn, turnAgentName, turnHasGap } from "./liveTurn";

const ROOM = "!a:x.org";

function summary(over: Partial<TurnSummary> = {}): TurnSummary {
  return {
    roomId: ROOM,
    turnId: 1,
    revision: 1,
    inProgress: true,
    endedBy: null,
    startedAtMs: 0,
    endedAtMs: null,
    lastActivityMs: 0,
    answering: false,
    hasThought: false,
    totalSteps: 0,
    completedSteps: 0,
    failedStep: null,
    currentStepTitle: "",
    stepIsTool: false,
    recordLabel: null,
    ...over,
  };
}

function step(id: string, over: Partial<TurnStep> = {}): TurnStep {
  return {
    id,
    title: id,
    kind: null,
    status: "in_progress",
    phase: "running",
    statusLabel: "Running",
    locations: [],
    input: null,
    output: null,
    seq: 1,
    hasDetail: false,
    ...over,
  };
}

function update(s: Partial<TurnSummary>, st?: { index: number; step: TurnStep }): TurnUpdate {
  return { summary: summary(s), step: st ?? null };
}

describe("applyTurnUpdate", () => {
  it("an update for another room changes nothing", () => {
    const s = emptyTurn(ROOM);
    expect(applyTurnUpdate(s, update({ roomId: "!b:x.org" }))).toBe(s);
  });

  it("an older revision does not rewind the card", () => {
    let s = applyTurnUpdate(emptyTurn(ROOM), update({ revision: 5, totalSteps: 3 }));
    s = applyTurnUpdate(s, update({ revision: 4, totalSteps: 1 }));
    expect(s.summary?.revision).toBe(5);
    expect(s.summary?.totalSteps).toBe(3);
  });

  it("a new turn drops the last one's steps, reasoning and answer", () => {
    let s = applyTurnUpdate(emptyTurn(ROOM), update({ turnId: 1, revision: 1 }, { index: 0, step: step("a") }));
    s = { ...s, thought: "hmm", answer: "so far" };
    s = applyTurnUpdate(s, update({ turnId: 2, revision: 2 }));
    expect(s.steps).toEqual([]);
    expect(s.thought).toBeNull();
    expect(s.answer).toBeNull();
    expect(s.summary?.turnId).toBe(2);
  });

  it("a step change lands at the index the core gave", () => {
    let s = applyTurnUpdate(emptyTurn(ROOM), update({ revision: 1 }, { index: 0, step: step("a") }));
    s = applyTurnUpdate(s, update({ revision: 2 }, { index: 1, step: step("b") }));
    s = applyTurnUpdate(s, update({ revision: 3 }, { index: 0, step: step("a", { phase: "done" }) }));
    expect(s.steps.map((x) => [x.id, x.phase])).toEqual([
      ["a", "done"],
      ["b", "running"],
    ]);
  });
});

describe("a step past the end", () => {
  it("is not applied, and the revision does not advance", () => {
    const held = applyTurnUpdate(emptyTurn(ROOM), update({ revision: 1 }));
    const next = applyTurnUpdate(held, update({ revision: 5, totalSteps: 3 }, { index: 2, step: step("c") }));
    expect(next).toBe(held);
    expect(next.summary?.revision).toBe(1);
    expect(turnHasGap(held, update({ revision: 5 }, { index: 2, step: step("c") }))).toBe(true);
    expect(turnHasGap(held, update({ revision: 5 }, { index: 0, step: step("a") }))).toBe(false);
  });

  it("leaves the snapshot that repairs it acceptable", () => {
    const held = applyTurnUpdate(emptyTurn(ROOM), update({ revision: 1 }));
    const gapped = applyTurnUpdate(held, update({ revision: 5 }, { index: 2, step: step("c") }));
    const snap: TurnSnapshot = {
      summary: summary({ revision: 4, totalSteps: 3 }),
      steps: [step("a"), step("b"), step("c")],
      thought: null,
    };
    expect(restoreTurn(gapped, snap).steps).toHaveLength(3);
  });
});

describe("restoreTurn", () => {
  const snap = (rev: number, thought: string | null = null): TurnSnapshot => ({
    summary: summary({ revision: rev, inProgress: false, endedBy: "done" }),
    steps: [step("a", { phase: "done" })],
    thought,
  });

  it("a restore older than what is held is dropped", () => {
    const held = applyTurnUpdate(emptyTurn(ROOM), update({ revision: 9 }));
    expect(restoreTurn(held, snap(3))).toBe(held);
  });

  it("a restore brings back a finished turn's steps and thought, and no answer", () => {
    const s = restoreTurn(emptyTurn(ROOM), snap(3, "why"));
    expect(s.steps).toHaveLength(1);
    expect(s.thought).toBe("why");
    expect(s.answer).toBeNull();
    expect(s.summary?.endedBy).toBe("done");
  });
});

describe("turnAgentName", () => {
  const cast = (over: Partial<RoomCast> = {}): RoomCast => ({
    roomId: ROOM,
    people: [
      { userId: "@k:x.org", name: "Krishna", initial: "K", runtime: null, avatarUrl: null },
      { userId: "@g:x.org", name: "Ganesha", initial: "G", runtime: null, avatarUrl: null },
    ],
    counterpart: { userId: "@k:x.org", name: "Krishna" },
    agentIds: ["@k:x.org", "@g:x.org"],
    addressee: null,
    mentionables: [],
    ...over,
  });

  it("names the agent writing the turn", () => {
    expect(turnAgentName(summary({ agentId: "@g:x.org" }), cast())).toBe("Ganesha");
  });

  it("a turn by the room's own agent keeps the header's name", () => {
    const c = cast({
      people: [{ userId: "@k:x.org", name: "krishna-bot", initial: "K", runtime: null, avatarUrl: null }],
      counterpart: { userId: "@k:x.org", name: "Krishna" },
      agentIds: ["@k:x.org"],
    });
    expect(turnAgentName(summary({ agentId: "@k:x.org" }), c)).toBe("Krishna");
  });

  it("falls back to the room's agent when the writer is not in the cast", () => {
    expect(turnAgentName(summary({ agentId: "@evil:x.org" }), cast())).toBe("Krishna");
  });

  it("falls back to the room's agent when no writer is known, and to null with no cast", () => {
    expect(turnAgentName(summary(), cast())).toBe("Krishna");
    expect(turnAgentName(summary({ agentId: "@g:x.org" }), null)).toBeNull();
  });

  it("does not trust an id that is a person but not an agent", () => {
    expect(turnAgentName(summary({ agentId: "@g:x.org" }), cast({ agentIds: ["@k:x.org"] }))).toBe("Krishna");
  });
});
