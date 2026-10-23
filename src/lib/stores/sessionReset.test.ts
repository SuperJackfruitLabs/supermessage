import { describe, expect, it } from "vitest";
import { createAccountStore } from "./account.svelte";
import { createAcknowledgementStore } from "./acknowledgement.svelte";
import { createCastStore } from "./cast.svelte";
import { createDestinations } from "./destinations.svelte";
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
    acknowledgementStore: createAcknowledgementStore({ acknowledgement: async () => null }),
    destinations: blankDestinations(),
  };
}
function blankDestinations() {
  return createDestinations({
    rosterNeedsYouInbox: async () => ({ decisions: [], invitations: [], count: 0, badgeCount: 0 }),
    rosterAgentDirectory: async () => [],
    roomStatus: async () => null,
    roomStatusWord: async () => "",
  });
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
    expect(safetyStore.hidden.has("$e1")).toBe(false);
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
      acknowledgementStore: createAcknowledgementStore({ acknowledgement: async () => null }),
      destinations: blankDestinations(),
    });
    expect(liveStore.isLive).toBe(false);
    expect(liveStore.summary).toBeNull();
    expect(liveStore.agentName("!a:x")).toBeNull();
    expect(castStore.cast).toBeNull();
  });
});

describe("resetSessionState and the dock", () => {
  it("reset forgets the dock", async () => {
    const acknowledgementStore = createAcknowledgementStore({
      acknowledgement: async () => ({ kind: "sent", names: ["Atlas"], text: "Sent to Atlas" }),
    });
    await acknowledgementStore.ask({ roomId: "!a:x", cast: null, typingAgents: [], inProgress: false });
    expect(acknowledgementStore.current?.text).toBe("Sent to Atlas");
    resetSessionState({
      editTargetStore: createEditTargetStore(),
      replyTargetStore: createReplyTargetStore(),
      safetyStore: createSafetyStore(null),
      accountStore: createAccountStore(),
      ...blankTurnStores(),
      acknowledgementStore,
    });
    expect(acknowledgementStore.current).toBeNull();
  });
});

describe("resetSessionState and the destination", () => {
  it("one reset covers the destination", async () => {
    const inbox = { decisions: [], invitations: [], count: 1, badgeCount: 1 };
    const destinations = createDestinations({
      rosterNeedsYouInbox: async () => ({ ...inbox, decisions: [{ row: {} as never, state: "needsYou", describesAgent: true }] }),
      rosterAgentDirectory: async () => [],
      roomStatus: async () => null,
      roomStatusWord: async () => "",
    });
    destinations.select("needsYou");
    await destinations.refresh([], 1, null, false);
    expect(destinations.badgeCount).toBe(1);
    resetSessionState({
      editTargetStore: createEditTargetStore(),
      replyTargetStore: createReplyTargetStore(),
      safetyStore: createSafetyStore(null),
      accountStore: createAccountStore(),
      ...blankTurnStores(),
      destinations,
    });
    expect(destinations.selected).toBe("chats");
    expect(destinations.inbox).toBeNull();
    expect(destinations.badgeCount).toBe(0);
  });
});
