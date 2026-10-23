import { describe, expect, it, vi } from "vitest";
import type { RoomCast } from "$lib/ipc";
import { createCastStore } from "./cast.svelte";

const cast = (roomId: string): RoomCast => ({
  roomId,
  people: [],
  counterpart: null,
  agentIds: ["@k:x.org"],
  addressee: null,
  mentionables: [],
});

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

describe("castStore", () => {
  it("loads the cast for the room", async () => {
    const store = createCastStore({ roomCast: async (id) => cast(id) });
    await store.load("!a:x");
    expect(store.cast?.roomId).toBe("!a:x");
    expect(store.castFor("!a:x")?.agentIds).toEqual(["@k:x.org"]);
  });

  it("a load for a room left meanwhile is dropped", async () => {
    const slow = deferred<RoomCast>();
    const store = createCastStore({
      roomCast: (id) => (id === "!a:x" ? slow.promise : Promise.resolve(cast(id))),
    });
    const first = store.load("!a:x");
    await store.load("!b:x");
    slow.resolve(cast("!a:x"));
    await first;
    expect(store.cast?.roomId).toBe("!b:x");
  });

  it("a failed load is no cast", async () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const store = createCastStore({ roomCast: () => Promise.reject({ kind: "x", message: "no" }) });
    await store.load("!a:x");
    expect(store.cast).toBeNull();
    spy.mockRestore();
  });

  it("castFor another room is null", async () => {
    const store = createCastStore({ roomCast: async (id) => cast(id) });
    await store.load("!a:x");
    expect(store.castFor("!b:x")).toBeNull();
    expect(store.castFor(null)).toBeNull();
  });

  it("a load that lands after clear() is dropped", async () => {
    const slow = deferred<RoomCast>();
    const store = createCastStore({ roomCast: () => slow.promise });
    const p = store.load("!a:x");
    store.clear();
    slow.resolve(cast("!a:x"));
    await p;
    expect(store.cast).toBeNull();
  });
});
