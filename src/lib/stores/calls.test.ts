import { describe, expect, it } from "vitest";
import type { CallSnapshot } from "$lib/ipc";
import { CALLS_ENABLED_KEY, createCallsStore } from "./calls.svelte";

function fakeStorage(initial: Record<string, string> = {}): Storage {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => void data.set(k, String(v)),
    removeItem: (k) => void data.delete(k),
    clear: () => data.clear(),
    key: () => null,
    get length() {
      return data.size;
    },
  };
}

const off: CallSnapshot = { enabled: false, call: null, notice: null };

function harness(storage: Storage | null) {
  const sent: boolean[] = [];
  const started: string[][] = [];
  const store = createCallsStore({
    storage: () => storage,
    setEnabled: async (enabled) => {
      sent.push(enabled);
      return { ...off, enabled };
    },
    start: async (roomId, agentUserId, agentName) => {
      started.push([roomId, agentUserId, agentName]);
      return {
        enabled: true,
        call: {
          roomId,
          agentUserId,
          agentName,
          phase: "live",
          agentState: null,
          startedAtMs: 1,
          muted: false,
        },
        notice: null,
      };
    },
    hangUp: async () => ({ ...off, enabled: true }),
    setMuted: async () => off,
    dismiss: async () => ({ ...off, enabled: true }),
  });
  return { sent, started, store };
}

describe("calls store", () => {
  it("is off by default, and says so to Rust at install", async () => {
    const { store, sent } = harness(fakeStorage());
    expect(store.enabled).toBe(false);
    await store.install();
    expect(sent).toEqual([false]);
    expect(store.snapshot.enabled).toBe(false);
  });

  it("reads the stored choice and sends it at install", async () => {
    const { store, sent } = harness(fakeStorage({ [CALLS_ENABLED_KEY]: "true" }));
    expect(store.enabled).toBe(true);
    await store.install();
    expect(sent).toEqual([true]);
    expect(store.snapshot.enabled).toBe(true);
  });

  it("set stores and sends the choice; off clears the call", async () => {
    const storage = fakeStorage();
    const { store, sent } = harness(storage);
    await store.set(true);
    expect(storage.getItem(CALLS_ENABLED_KEY)).toBe("true");
    await store.start("!r:s", { userId: "@agent_x:s", name: "Ada" });
    expect(store.snapshot.call?.roomId).toBe("!r:s");
    await store.set(false);
    expect(storage.getItem(CALLS_ENABLED_KEY)).toBe("false");
    expect(sent).toEqual([true, false]);
    expect(store.snapshot.call).toBeNull();
  });

  it("an unreadable storage reads as off", () => {
    const blocked = new Proxy({} as Storage, {
      get() {
        throw new Error("blocked");
      },
    });
    expect(harness(blocked).store.enabled).toBe(false);
  });

  it("starts with the counterpart's id and name", async () => {
    const { store, started } = harness(fakeStorage());
    await store.set(true);
    await store.start("!r:s", { userId: "@agent_x:s", name: "Ada — Researcher" });
    expect(started).toEqual([["!r:s", "@agent_x:s", "Ada — Researcher"]]);
  });

  it("an event's snapshot replaces the state", () => {
    const { store } = harness(fakeStorage());
    store.apply({ enabled: true, call: null, notice: { roomId: "!r:s", text: "The call ended" } });
    expect(store.snapshot.notice?.text).toBe("The call ended");
  });
});
