// Ported from iOS SupermessageKitTests/SafetyStoreTests.swift.
import { describe, expect, it } from "vitest";
import { blockable } from "$lib/safety";
import { createSafetyStore, HIDDEN_EVENTS_KEY } from "./safety.svelte";

function fakeStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    removeItem: (k: string) => void data.delete(k),
  };
}

describe("safety store", () => {
  it("hidesWhatWasReported", () => {
    const store = createSafetyStore(null);
    store.hide("$b");
    expect([...store.hidden]).toEqual(["$b"]);
  });

  it("pushes the whole set to the core after every hide", async () => {
    const pushed: string[][] = [];
    const store = createSafetyStore(null, async (ids) => void pushed.push(ids));
    store.hide("$a");
    store.hide("$b");
    await store.pushHidden();
    expect(pushed.slice(0, 2)).toEqual([["$a"], ["$a", "$b"]]);
  });

  it("pushHidden hands the core the stored set, for session start", async () => {
    const pushed: string[][] = [];
    const store = createSafetyStore(fakeStorage({ [HIDDEN_EVENTS_KEY]: '["$a","$b"]' }), async (ids) => void pushed.push(ids));
    await store.pushHidden();
    expect(pushed).toEqual([["$a", "$b"]]);
  });

  it("pushes land in the order they were made, even when an earlier one is slow", async () => {
    const landed: string[][] = [];
    let releaseFirst!: () => void;
    let calls = 0;
    const store = createSafetyStore(null, (ids) => {
      calls += 1;
      if (calls === 1) {
        return new Promise<void>((resolve) => (releaseFirst = () => (landed.push(ids), resolve())));
      }
      landed.push(ids);
      return Promise.resolve();
    });
    store.hide("$a");
    store.hide("$b");
    await new Promise((resolve) => setTimeout(resolve, 0));
    // The second push must wait for the first: a stale set landing last would unhide "$b".
    expect(calls).toBe(1);
    expect(landed).toEqual([]);
    releaseFirst();
    await store.pushHidden();
    expect(landed.slice(0, 2)).toEqual([["$a"], ["$a", "$b"]]);
  });

  it("a failed push is swallowed and the next one still goes", async () => {
    const pushed: string[][] = [];
    let first = true;
    const store = createSafetyStore(null, async (ids) => {
      if (first) {
        first = false;
        throw { kind: "notReady", message: "" };
      }
      pushed.push(ids);
    });
    store.hide("$a");
    store.hide("$b");
    await expect(store.pushHidden()).resolves.toBeUndefined();
    expect(pushed[0]).toEqual(["$a", "$b"]);
  });

  it("theCoresListWins", () => {
    const store = createSafetyStore(null);
    store.noteBlocked("@x");
    store.apply(["@y"]);
    expect(store.isBlocked("@x")).toBe(false);
    expect(store.isBlocked("@y")).toBe(true);
  });

  it("noteUnblocked forgets one id", () => {
    const store = createSafetyStore(null);
    store.apply(["@x", "@y"]);
    store.noteUnblocked("@x");
    expect([...store.blocked]).toEqual(["@y"]);
  });

  it("every change is a new set", () => {
    const store = createSafetyStore(null);
    const before = store.blocked;
    store.noteBlocked("@x");
    expect(store.blocked).not.toBe(before);
    const hiddenBefore = store.hidden;
    store.hide("$a");
    expect(store.hidden).not.toBe(hiddenBefore);
  });

  it("persistsUntilSignOut", () => {
    const storage = fakeStorage();
    createSafetyStore(storage).hide("$b");
    expect(storage.data.get(HIDDEN_EVENTS_KEY)).toBe('["$b"]');
    const second = createSafetyStore(storage);
    expect([...second.hidden]).toEqual(["$b"]);
    second.clear();
    expect(storage.data.has(HIDDEN_EVENTS_KEY)).toBe(false);
    expect(createSafetyStore(storage).hidden.size).toBe(0);
  });

  it("clear forgets the blocked list too", () => {
    const store = createSafetyStore(null);
    store.apply(["@x"]);
    store.hide("$a");
    store.clear();
    expect(store.blocked.size).toBe(0);
    expect(store.hidden.size).toBe(0);
  });

  it("whoCanBeBlocked", () => {
    const message = { kind: "message", roomId: "!r:x", eventId: "$e", senderId: "@s:x", senderName: "S", isAgent: true } as const;
    expect(blockable(message)).toEqual({ userId: "@s:x", name: "S", isAgent: true });
    expect(blockable({ kind: "room", roomId: "!r:x", name: "R" })).toBeNull();
    expect(blockable({ kind: "user", userId: "@u:x", name: "U", isAgent: false })).toEqual({
      userId: "@u:x", name: "U", isAgent: false,
    });
  });

  it("aSeedNeverOverwritesALaterEvent", async () => {
    const store = createSafetyStore(null);
    let finish!: (ids: string[]) => void;
    const seeding = store.seed(() => new Promise<string[]>((resolve) => (finish = resolve)));
    store.apply(["@new"]);
    finish(["@old"]);
    await seeding;
    expect([...store.blocked]).toEqual(["@new"]);
  });

  it("a seed with no event in between applies", async () => {
    const store = createSafetyStore(null);
    await store.seed(async () => ["@old"]);
    expect([...store.blocked]).toEqual(["@old"]);
  });

  it("a failed seed is swallowed", async () => {
    const store = createSafetyStore(null);
    await expect(store.seed(async () => { throw { kind: "notReady", message: "" }; })).resolves.toBeUndefined();
    expect(store.blocked.size).toBe(0);
  });

  it("aStoreWhoseStorageThrowsStillHides", () => {
    const boom = () => { throw new Error("denied"); };
    const store = createSafetyStore({ getItem: boom, setItem: boom, removeItem: boom });
    store.hide("$b");
    expect([...store.hidden]).toEqual(["$b"]);
    expect(() => store.clear()).not.toThrow();
  });

  it("corruptStorageReadsAsEmpty", () => {
    for (const bad of ["{", '{"a":1}', "[1,2]", "null"]) {
      const store = createSafetyStore(fakeStorage({ [HIDDEN_EVENTS_KEY]: bad }));
      expect(store.hidden.size).toBe(0);
    }
  });
});
