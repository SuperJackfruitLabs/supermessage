import { describe, expect, it, vi } from "vitest";
import type { NotificationMode, PlannedNotification, RoomCast, TurnCard, TurnEnding, TurnSummary } from "$lib/ipc";
import { createTurnEndedNotifier, type TurnEndedDeps } from "./turnEnded";

const ROOM = "!a:x";

function summary(over: Partial<TurnSummary> = {}): TurnSummary {
  return {
    roomId: ROOM, turnId: 1, revision: 1, inProgress: false, endedBy: "done", startedAtMs: 1000, endedAtMs: 65000,
    lastActivityMs: 65000, answering: false, hasThought: false, totalSteps: 3, completedSteps: 3, failedStep: null,
    currentStepTitle: "", stepIsTool: false, recordLabel: null, ...over,
  };
}
const running = (over: Partial<TurnSummary> = {}) =>
  summary({ inProgress: true, endedBy: null, endedAtMs: null, ...over });
const upd = (s: TurnSummary) => ({ summary: s, step: null });
/** A turn that ended with a failed step: the one ending this notifier posts. */
const failed = (over: Partial<TurnSummary> = {}) =>
  summary({ completedSteps: 2, failedStep: "git push", ...over });

/** The core's words, faked: finished, failed, or nothing for a timed-out turn. */
function fakeCard(s: TurnSummary, name: string): TurnCard | null {
  if (s.endedBy === ("timedOut" satisfies TurnEnding)) return null;
  if (s.failedStep) return { headline: `${name} failed`, subtitle: "At step 2 of 3 · after 1m 04s", inProgress: false, failed: true };
  return { headline: `${name} finished`, subtitle: "Done in 1m 04s · 3 steps", inProgress: false, failed: false };
}

function setup(over: { cast?: Partial<RoomCast> | null; mode?: NotificationMode } = {}) {
  const post = vi.fn(async (_n: PlannedNotification) => {});
  const turnCard = vi.fn(async (s: TurnSummary, name: string) => fakeCard(s, name));
  const cast = over.cast === undefined
    ? ({ roomId: ROOM, people: [{ userId: "@lyra:x", name: "Lyra" }], counterpart: { userId: "@atlas:x", name: "Atlas" }, agentIds: ["@atlas:x", "@lyra:x"] } as unknown as RoomCast)
    : (over.cast as RoomCast | null);
  const deps: TurnEndedDeps = {
    turnCard: turnCard as never,
    notificationPost: post as never,
    notificationDelivers: (async (_n: PlannedNotification, m: NotificationMode | null) => m !== "muted") as never,
    roomInfo: (async () => ({ notifications: over.mode ?? "default" })) as never,
    roomCast: (async () => {
      if (cast === null) throw new Error("no cast");
      return cast;
    }) as never,
    roomName: () => "Design room",
  };
  const n = createTurnEndedNotifier(deps);
  return { n, post, turnCard };
}
const flush = () => new Promise((r) => setTimeout(r, 0));

async function run(n: ReturnType<typeof setup>["n"], ...ss: TurnSummary[]) {
  for (const s of ss) n.turnChanged(upd(s));
  await flush();
}

describe("turn-ended notification", () => {
  // The core ends a turn as `done` when the agent's stream closes (`turns.rs` `TurnInput::Answer
  // { done: true }`), which is how an ordinary answered turn ends; the answer then lands as a
  // message and notifies as one. Only a failure is news the message does not carry.
  it("an answered turn ending in done posts nothing; a failed one posts once", async () => {
    const { n, post } = setup();
    await run(n, running({ answering: true }), summary({ answering: true }));
    expect(post).not.toHaveBeenCalled();
    await run(n, running({ turnId: 2 }), failed({ turnId: 2 }));
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]![0]).toEqual({
      id: `turn:${ROOM}:2`, roomId: ROOM, eventId: null, title: "Atlas failed",
      subtitle: null, body: "At step 2 of 3 · after 1m 04s", category: "message", permission: null, gate: null,
    });
  });

  it("a thought stream's own done posts nothing", async () => {
    const { n, post } = setup();
    await run(n, running({ hasThought: true }), summary({ hasThought: true }));
    expect(post).not.toHaveBeenCalled();
  });

  it("a turn that ends by reply posts nothing", async () => {
    const { n, post } = setup();
    await run(n, running(), summary({ endedBy: "reply" }));
    expect(post).not.toHaveBeenCalled();
  });

  it("a failed step posts failed even after a reply", async () => {
    const { n, post } = setup();
    await run(n, running(), summary({ endedBy: "reply", failedStep: "git push" }));
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0]![0].title).toBe("Atlas failed");
  });

  it("a timed-out turn posts nothing", async () => {
    const { n, post } = setup();
    await run(n, running(), summary({ endedBy: "timedOut" }));
    expect(post).not.toHaveBeenCalled();
  });

  it("the room on screen in a focused window posts nothing", async () => {
    const { n, post } = setup();
    n.focusChanged(true);
    n.selectedChanged(ROOM);
    await run(n, running(), failed());
    expect(post).not.toHaveBeenCalled();
  });

  it("the same room in an unfocused window posts", async () => {
    const { n, post } = setup();
    n.focusChanged(false);
    n.selectedChanged(ROOM);
    await run(n, running(), failed());
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("another room in a focused window posts", async () => {
    const { n, post } = setup();
    n.focusChanged(true);
    n.selectedChanged("!other:x");
    await run(n, running(), failed());
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("a muted room posts nothing", async () => {
    const { n, post } = setup({ mode: "muted" });
    await run(n, running(), failed());
    expect(post).not.toHaveBeenCalled();
  });

  it("a turn posts at most once", async () => {
    const { n, post } = setup();
    await run(n, running(), failed(), failed());
    // A late in-progress update of the same turn, then its end again.
    await run(n, running({ revision: 2 }), failed({ revision: 3 }));
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("a turn first seen already ended posts nothing", async () => {
    const { n, post } = setup();
    await run(n, failed());
    expect(post).not.toHaveBeenCalled();
  });

  it("the next turn posts on its own id", async () => {
    const { n, post } = setup();
    await run(n, running(), failed(), running({ turnId: 2 }), failed({ turnId: 2 }));
    expect(post.mock.calls.map((c) => c[0].id)).toEqual([`turn:${ROOM}:1`, `turn:${ROOM}:2`]);
  });

  it("the card names the agent doing the turn", async () => {
    const { n, post, turnCard } = setup();
    await run(n, running({ agentId: "@lyra:x" }), failed({ agentId: "@lyra:x" }));
    expect(turnCard.mock.calls[0]![1]).toBe("Lyra");
    expect(post.mock.calls[0]![0].title).toBe("Lyra failed");
  });

  it("with no cast the card names the room", async () => {
    const { n, turnCard } = setup({ cast: null });
    await run(n, running(), failed());
    expect(turnCard.mock.calls[0]![1]).toBe("Design room");
  });

  it("an edited-progress turn ends like any other", async () => {
    const { n, post } = setup();
    // The core re-emits the same turn with a higher revision as the progress message is edited,
    // then ends it by the reply.
    await run(n, running(), running({ revision: 2, completedSteps: 1 }), running({ revision: 3, completedSteps: 2 }), summary({ revision: 4, endedBy: "reply" }));
    expect(post).not.toHaveBeenCalled();
    // And the room is not stuck: its next turn still posts.
    await run(n, running({ turnId: 2 }), failed({ turnId: 2 }));
    expect(post).toHaveBeenCalledTimes(1);
  });

  it("signing out forgets every room's state", async () => {
    const { n, post } = setup();
    n.turnChanged(upd(running()));
    n.reset();
    await run(n, failed());
    expect(post).not.toHaveBeenCalled();
  });

  it("an end that was being worded when the reader signed out posts nothing", async () => {
    const { n, post } = setup();
    n.turnChanged(upd(running()));
    n.turnChanged(upd(failed()));
    n.reset();
    await flush();
    expect(post).not.toHaveBeenCalled();
  });
});
