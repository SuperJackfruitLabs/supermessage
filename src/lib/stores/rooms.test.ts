// Regression test for the room-list tracker re-arming hazard: the core
// restarts its `sm://rooms/diff` sequence counter from scratch on every
// `login`/`restoreSession` (`SeqCounter::default()` inside
// `spawn_room_list`, see `rooms.svelte.ts`'s module doc comment). If
// `roomsStore.login`/`restoreSession` didn't re-arm the tracker first, the
// new session's `seq: 1` envelope would look like an already-applied
// duplicate of the previous session's history and get silently dropped —
// `DiffTracker.apply` only detects gaps forward (`seq > expected`), never
// backward, so nothing would ever trigger a resync to correct it.

import { describe, expect, it, vi } from "vitest";
import { createRoomsStore } from "./rooms.svelte";
import type { DiffEnvelope } from "./diff";
import type { RoomSummary } from "$lib/ipc";

function room(id: string): RoomSummary {
  return { id, name: id, avatarUrl: null, unread: 0, lastMessage: null, lastActivityMs: null };
}

function env(seq: number, ops: DiffEnvelope<RoomSummary>["ops"]): DiffEnvelope<RoomSummary> {
  return { channel: "rooms", subject: "", seq, ops };
}

/** Fake `sm://rooms/diff` channel: `onRoomsDiff` captures the handler synchronously. */
function makeChannel() {
  let handler: ((env: DiffEnvelope<RoomSummary>) => void) | null = null;
  return {
    onRoomsDiff: (onEnvelope: (env: DiffEnvelope<RoomSummary>) => void) => {
      handler = onEnvelope;
      return Promise.resolve(() => {
        handler = null;
      });
    },
    emit: (envelope: DiffEnvelope<RoomSummary>) => handler?.(envelope),
  };
}

/**
 * Fake `makeSessionCommands`, mirroring the real `ipc.ts` factory's
 * contract exactly: it returns `login`/`restoreSession` that call `onArm`
 * before doing anything else, and there is no other way to get a working
 * `login`/`restoreSession` out of it. This is what lets these tests verify
 * `roomsStore` actually wires its `resetForNewSubscription` through as
 * `onArm` — see `ipc.test.ts` for the real factory's own onArm-before-invoke
 * ordering.
 */
function makeFakeSessionCommands(onArm: () => void) {
  return {
    login: vi.fn(async (_homeserver: string, _username: string, _password: string) => {
      onArm();
    }),
    restoreSession: vi.fn(async () => {
      onArm();
      return true;
    }),
  };
}

function makeStore(channel: ReturnType<typeof makeChannel>) {
  return createRoomsStore({
    onRoomsDiff: channel.onRoomsDiff,
    roomsResync: vi.fn(),
    makeSessionCommands: makeFakeSessionCommands,
    logout: vi.fn().mockResolvedValue(undefined),
  });
}

describe("roomsStore: re-arming the tracker on a new session", () => {
  it("applies a fresh session's seq:1 envelope after login, instead of dropping it as a stale duplicate", async () => {
    const channel = makeChannel();
    const store = makeStore(channel);

    // First session advances the tracker's expected sequence well past 1.
    channel.emit(env(1, [{ op: "reset", values: [room("!a:x"), room("!b:x")] }]));
    channel.emit(env(2, [{ op: "pushBack", value: room("!c:x") }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!a:x", "!b:x", "!c:x"]);

    await store.login("https://example.org", "alice", "hunter2");

    // The new session's core-side room-list task restarts at seq 1. If the
    // tracker weren't re-armed, this would be treated as `seq < expected`
    // (a duplicate) and silently ignored, leaving the previous session's
    // rooms on screen.
    channel.emit(env(1, [{ op: "reset", values: [room("!fresh:y")] }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!fresh:y"]);

    // And the new session's stream continues to apply normally afterward.
    channel.emit(env(2, [{ op: "pushBack", value: room("!another:y") }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!fresh:y", "!another:y"]);
  });

  it("applies a fresh session's seq:1 envelope after restoreSession too", async () => {
    const channel = makeChannel();
    const store = makeStore(channel);

    channel.emit(env(1, [{ op: "reset", values: [room("!old:x")] }]));
    channel.emit(env(2, [{ op: "pushBack", value: room("!old2:x") }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!old:x", "!old2:x"]);

    await store.restoreSession();

    channel.emit(env(1, [{ op: "reset", values: [room("!restored:y")] }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!restored:y"]);
  });

  it("clears local state on logout", async () => {
    const channel = makeChannel();
    const store = makeStore(channel);

    channel.emit(env(1, [{ op: "reset", values: [room("!a:x")] }]));
    store.select("!a:x");
    expect(store.selectedId).toBe("!a:x");

    await store.logout();

    expect(store.rooms).toEqual([]);
    expect(store.selectedId).toBeNull();

    // And the tracker is re-armed for whatever session logs in next.
    channel.emit(env(1, [{ op: "reset", values: [room("!next:y")] }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!next:y"]);
  });
});
