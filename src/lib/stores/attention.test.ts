import { afterEach, describe, expect, it, vi } from "vitest";
import type { NeedsYouInbox, RoomRow, RoomStatus, RosterRow } from "$lib/ipc";
import { createAttention, TICK_MS, type AttentionInput } from "./attention.svelte";

function inbox(decisions: number, invitations: number): NeedsYouInbox {
  // The core's own shape: `count` includes invitations, `badgeCount` is decisions only.
  return {
    decisions: Array.from({ length: decisions }, () => ({})),
    invitations: Array.from({ length: invitations }, () => ({})),
    count: decisions + invitations,
    badgeCount: decisions,
  } as unknown as NeedsYouInbox;
}

/** An agent row whose `state` field carries the status the fake core will answer. */
function agent(id: string, status: RoomStatus): RosterRow {
  return { row: { room: { id } }, state: status } as unknown as RosterRow;
}

const ROWS = [] as RoomRow[];

function page(over: Partial<AttentionInput> = {}): AttentionInput {
  return { inbox: inbox(0, 0), rows: ROWS, agents: [], openRoomId: null, turnInProgress: false, ...over };
}

function harness(directory: () => RosterRow[] = () => []) {
  const sent: [number, number][] = [];
  const ticks: (() => void)[] = [];
  let cleared = 0;
  const reads: number[] = [];
  let clock = 1000;
  const attention = createAttention({
    setAttention: async (n, w) => void sent.push([n, w]),
    // The fake core: a turn in progress makes an agent Working, otherwise its own state stands.
    roomStatus: async (state, _agent, turn) => (turn ? "working" : (state as unknown as RoomStatus)),
    rosterAgentDirectory: async (_rows, now) => {
      reads.push(now);
      return directory();
    },
    now: () => clock,
    setInterval: (fn) => (ticks.push(fn), ticks.length),
    clearInterval: () => void cleared++,
  });
  return { sent, ticks, attention, reads, cleared: () => cleared, advance: (ms: number) => (clock += ms) };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

afterEach(() => vi.restoreAllMocks());

describe("attention", () => {
  it("the title counts decisions, not invitations", async () => {
    const { sent, attention } = harness();
    await attention.follow(page({ inbox: inbox(2, 1) }));
    expect(sent).toEqual([[2, 0]]);
  });

  it("an invitation never lights the dot", async () => {
    const { sent, attention } = harness();
    await attention.follow(page({ inbox: inbox(0, 3) }));
    expect(sent).toEqual([]);
    await attention.follow(page({ inbox: inbox(1, 3) }));
    expect(sent).toEqual([[1, 0]]);
  });

  it("working counts only agents whose status is Working", async () => {
    const { sent, attention } = harness();
    await attention.follow(
      page({
        agents: [agent("a", "working"), agent("b", "active"), agent("c", "idle"), agent("d", "needsYou"), agent("e", "working")],
      }),
    );
    expect(sent).toEqual([[0, 2]]);
  });

  it("a turn in progress makes its agent working", async () => {
    const { sent, attention } = harness();
    const agents = [agent("a", "active"), agent("b", "active")];
    await attention.follow(page({ agents, openRoomId: "b", turnInProgress: true }));
    expect(sent).toEqual([[0, 1]]);
    // A turn in another room does not make this one work.
    await attention.follow(page({ agents, openRoomId: null, turnInProgress: true }));
    expect(sent).toEqual([[0, 1], [0, 0]]);
  });

  it("the counts are sent only when they change", async () => {
    const { sent, attention } = harness();
    await attention.follow(page({ inbox: inbox(2, 0) }));
    await attention.follow(page({ inbox: inbox(2, 1) }));
    await attention.follow(page({ inbox: inbox(3, 1) }));
    await attention.follow(page({ inbox: null }));
    await attention.follow(page({ inbox: null }));
    expect(sent).toEqual([[2, 0], [3, 0], [0, 0]]);
  });

  it("the counts are re-read every minute", async () => {
    let now: RosterRow[] = [agent("a", "idle")];
    const { sent, ticks, attention, reads, advance } = harness(() => now);
    attention.startTicking();
    await attention.follow(page({ agents: now }));
    expect(sent).toEqual([]);
    // A minute later the core, asked with the new clock, finds the agent Working.
    now = [agent("a", "working")];
    advance(TICK_MS);
    ticks[0]();
    await flush();
    expect(reads).toEqual([1000 + TICK_MS]);
    expect(sent).toEqual([[0, 1]]);
  });

  it("the tick stops when the page is torn down", () => {
    const { attention, cleared } = harness();
    const stop = attention.startTicking();
    expect(cleared()).toBe(0);
    stop();
    expect(cleared()).toBe(1);
  });

  it("signing out sends zero and zero", async () => {
    const { sent, attention } = harness();
    await attention.follow(page({ inbox: inbox(2, 0), agents: [agent("a", "working")] }));
    attention.reset();
    expect(sent).toEqual([[2, 1], [0, 0]]);
  });

  it("a tick after sign-out does not bring the last account's counts back", async () => {
    const { sent, ticks, attention } = harness(() => [agent("a", "working")]);
    attention.startTicking();
    await attention.follow(page({ inbox: inbox(2, 0) }));
    attention.reset();
    ticks[0]();
    await flush();
    expect(sent).toEqual([[2, 0], [0, 0]]);
  });

  it("after a sign-out the same counts are sent again for the next account", async () => {
    const { sent, attention } = harness();
    await attention.follow(page({ inbox: inbox(2, 0) }));
    attention.reset();
    await attention.follow(page({ inbox: inbox(2, 0) }));
    expect(sent).toEqual([[2, 0], [0, 0], [2, 0]]);
  });

  it("a rejected write is retried on the next follow", async () => {
    const sent: [number, number][] = [];
    let fail = true;
    vi.spyOn(console, "error").mockImplementation(() => {});
    const attention = createAttention({
      setAttention: async (n, w) => {
        sent.push([n, w]);
        if (fail) throw new Error("window gone");
      },
      roomStatus: async () => null,
    });
    await attention.follow(page({ inbox: inbox(2, 0) }));
    await flush();
    fail = false;
    await attention.follow(page({ inbox: inbox(2, 0) }));
    expect(sent).toEqual([[2, 0], [2, 0]]);
  });
});
