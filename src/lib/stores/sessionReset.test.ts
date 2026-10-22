import { describe, expect, it } from "vitest";
import { createAccountStore } from "./account.svelte";
import { createCastStore } from "./cast.svelte";
import { createEditTargetStore } from "./editTarget.svelte";
import { createLiveStore } from "./live.svelte";
import { createReplyTargetStore } from "./replyTarget.svelte";
import { createSafetyStore } from "./safety.svelte";
import type { TurnUpdate } from "$lib/ipc";
import { resetSessionState } from "./sessionReset";

const noop = () => Promise.resolve(() => {});
function blankTurnStores() {
  return {
    liveStore: createLiveStore({ onLive: noop, onThought: noop, onTurn: noop, turn: async () => null }),
    castStore: createCastStore({ roomCast: async () => { throw new Error("unused"); } }),
  };
}

describe("resetSessionState", () => {
  it("resetSessionState clears edit and reply targets", () => {
    const editTargetStore = createEditTargetStore();
    const replyTargetStore = createReplyTargetStore();
    const safetyStore = createSafetyStore(null);
    const accountStore = createAccountStore();
    for (const room of ["!a:x", "!b:x"]) {
      editTargetStore.set(room, { eventId: "$e", body: "hi" });
      replyTargetStore.set(room, { eventId: "$r", sender: "Ann", excerpt: null });
    }
    resetSessionState({ editTargetStore, replyTargetStore, safetyStore, accountStore, ...blankTurnStores() });
    for (const room of ["!a:x", "!b:x"]) {
      expect(editTargetStore.get(room)).toBeNull();
      expect(replyTargetStore.get(room)).toBeNull();
    }
  });

  it("resetSessionState forgets the account and its safety state", async () => {
    const storage = new Map<string, string>();
    const safetyStore = createSafetyStore({
      getItem: (k) => storage.get(k) ?? null,
      setItem: (k, v) => void storage.set(k, v),
      removeItem: (k) => void storage.delete(k),
    });
    safetyStore.noteBlocked("@a:x");
    safetyStore.hide("$e1");
    const accountStore = createAccountStore({
      account: async () => ({ userId: "@me:x", homeserver: "https://x/" }),
      peopleLabel: async () => "Me",
    });
    await accountStore.load();
    resetSessionState({
      editTargetStore: createEditTargetStore(),
      replyTargetStore: createReplyTargetStore(),
      safetyStore,
      accountStore,
      ...blankTurnStores(),
    });
    expect(safetyStore.isBlocked("@a:x")).toBe(false);
    expect(safetyStore.visible([{ eventId: "$e1" }])).toHaveLength(1);
    expect(storage.size).toBe(0);
    expect(accountStore.account).toBeNull();
  });

  it("resetSessionState forgets the turn and the cast", async () => {
    let emitTurn: (u: TurnUpdate) => void = () => {};
    const liveStore = createLiveStore({
      onLive: noop,
      onThought: noop,
      onTurn: (h) => {
        emitTurn = h;
        return Promise.resolve(() => {});
      },
      turn: async () => null,
    });
    const castStore = createCastStore({
      roomCast: async (roomId) => ({ roomId, people: [], counterpart: null, agentIds: [], addressee: null, mentionables: [] }),
    });
    await liveStore.focus("!a:x");
    emitTurn({ summary: { roomId: "!a:x", turnId: 1, revision: 1 } as TurnUpdate["summary"], step: null });
    liveStore.setAgentName("Krishna", "!a:x");
    await castStore.load("!a:x");
    expect(liveStore.isLive).toBe(true);
    expect(castStore.cast).not.toBeNull();

    resetSessionState({
      editTargetStore: createEditTargetStore(),
      replyTargetStore: createReplyTargetStore(),
      safetyStore: createSafetyStore(null),
      accountStore: createAccountStore(),
      liveStore,
      castStore,
    });
    expect(liveStore.isLive).toBe(false);
    expect(liveStore.summary).toBeNull();
    expect(liveStore.agentName("!a:x")).toBeNull();
    expect(castStore.cast).toBeNull();
  });
});
