// What the live mirror holds, and when it lets go. The failure this guards
// against is a duplicate the reader would notice: streamed text sitting under
// the room's own copy of the same answer.
//
// Fakes only — no Tauri runtime.

import { describe, expect, it } from "vitest";
import { createLiveStore } from "./live.svelte";
import type { LivePayload, TurnSnapshot, TurnSummary, TurnUpdate } from "$lib/ipc";

function channel<T>() {
  let handler: ((p: T) => void) | null = null;
  return {
    on: (h: (p: T) => void) => {
      handler = h;
      return Promise.resolve(() => {});
    },
    emit: (p: T) => handler?.(p),
  };
}

const ROOM = "!krishna:id.agentpod.dev";
const OTHER = "!ganesha:id.agentpod.dev";

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

function stepOf(id: string): TurnSnapshot["steps"][number] {
  return {
    id,
    title: id,
    kind: null,
    status: "completed",
    phase: "done",
    statusLabel: "Done",
    locations: [],
    input: null,
    output: null,
    seq: 1,
    hasDetail: false,
  };
}

function setup(turn: (roomId: string) => Promise<TurnSnapshot | null> = async () => null) {
  const live = channel<LivePayload>();
  const thought = channel<LivePayload>();
  const turns = channel<TurnUpdate>();
  const store = createLiveStore({ onLive: live.on, onThought: thought.on, onTurn: turns.on, turn });
  return { store, live, thought, turns };
}

const text = (roomId: string, seq: number, t: string, done = false): LivePayload => ({ roomId, seq, text: t, done });

describe("liveStore", () => {
  it("has nothing to say before a room is focused", () => {
    const { store } = setup();
    expect(store.isLive).toBe(false);
    expect(store.answer).toBeNull();
    expect(store.inProgress).toBe(false);
    expect(store.finished).toBe(false);
  });

  it("focus restores the room's turn from the core", async () => {
    const snap: TurnSnapshot = {
      summary: summary({ revision: 4, inProgress: false, endedBy: "done" }),
      steps: [],
      thought: "I thought.",
    };
    const { store } = setup(async () => snap);
    await store.focus(ROOM);
    expect(store.summary?.revision).toBe(4);
    expect(store.thought).toBe("I thought.");
    expect(store.finished).toBe(true);
    expect(store.inProgress).toBe(false);
  });

  it("a restore for a room left meanwhile is dropped", async () => {
    let release!: (s: TurnSnapshot | null) => void;
    const slow = new Promise<TurnSnapshot | null>((r) => (release = r));
    const { store } = setup((id) => (id === ROOM ? slow : Promise.resolve(null)));
    const first = store.focus(ROOM);
    await store.focus(OTHER);
    release({ summary: summary(), steps: [], thought: "stale" });
    await first;
    expect(store.roomId).toBe(OTHER);
    expect(store.summary).toBeNull();
    expect(store.thought).toBeNull();
  });

  it("the streamed answer comes from the live channel and goes on done", async () => {
    const { store, live } = setup();
    await store.focus(ROOM);
    live.emit(text(ROOM, 1, "I looked."));
    expect(store.answer).toBe("I looked.");
    expect(store.isLive).toBe(true);
    live.emit(text(ROOM, 2, "I looked. It is up.", true));
    expect(store.answer).toBeNull();
  });

  it("ignores text and updates for a room that is not focused", async () => {
    const { store, live, thought, turns } = setup();
    await store.focus(ROOM);
    live.emit(text(OTHER, 1, "Ganesha."));
    thought.emit(text(OTHER, 1, "Ganesha thinks."));
    turns.emit({ summary: summary({ roomId: OTHER }), step: null });
    expect(store.answer).toBeNull();
    expect(store.thought).toBeNull();
    expect(store.summary).toBeNull();
  });

  it("mirrors turn updates and the reasoning for the focused room", async () => {
    const { store, thought, turns } = setup();
    await store.focus(ROOM);
    turns.emit({ summary: summary({ revision: 2 }), step: null });
    thought.emit(text(ROOM, 1, "Hmm."));
    expect(store.inProgress).toBe(true);
    expect(store.thought).toBe("Hmm.");
    thought.emit(text(ROOM, 2, "Hmm.", true));
    expect(store.thought).toBeNull();
  });

  it("an older revision does not rewind the mirror", async () => {
    const { store, turns } = setup();
    await store.focus(ROOM);
    turns.emit({ summary: summary({ revision: 7, completedSteps: 2 }), step: null });
    turns.emit({ summary: summary({ revision: 6, completedSteps: 1 }), step: null });
    expect(store.summary?.completedSteps).toBe(2);
  });

  it("the header's name is kept per room", () => {
    const { store } = setup();
    store.setAgentName("Krishna", ROOM);
    store.setAgentName("Ganesha", OTHER);
    expect(store.agentName(ROOM)).toBe("Krishna");
    expect(store.agentName(OTHER)).toBe("Ganesha");
    store.setAgentName(null, ROOM);
    expect(store.agentName(ROOM)).toBeNull();
  });

  it("a step past the end asks the core for the record instead of leaving a hole", async () => {
    const full: TurnSnapshot = {
      summary: summary({ revision: 6, totalSteps: 3 }),
      steps: [0, 1, 2].map((i) => ({ ...stepOf(`s${i}`) })),
      thought: null,
    };
    let calls = 0;
    const { store, turns } = setup(async () => (calls++ === 0 ? null : full));
    await store.focus(ROOM);
    turns.emit({ summary: summary({ revision: 5, totalSteps: 3 }), step: { index: 2, step: stepOf("s2") } });
    expect(store.steps).toHaveLength(0);
    expect(store.summary).toBeNull();
    await new Promise((r) => setTimeout(r, 0));
    expect(calls).toBe(2);
    expect(store.steps.map((x) => x.id)).toEqual(["s0", "s1", "s2"]);
  });

  it("a summary that counts more steps than are held also refreshes", async () => {
    const full: TurnSnapshot = {
      summary: summary({ revision: 4, totalSteps: 2 }),
      steps: [stepOf("s0"), stepOf("s1")],
      thought: null,
    };
    let calls = 0;
    const { store, turns } = setup(async () => (calls++ === 0 ? null : full));
    await store.focus(ROOM);
    turns.emit({ summary: summary({ revision: 3, totalSteps: 2 }), step: null });
    await new Promise((r) => setTimeout(r, 0));
    expect(store.steps).toHaveLength(2);
    expect(store.summary?.revision).toBe(4);
  });
});
