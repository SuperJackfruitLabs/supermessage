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
import type { Membership, RoomSummary } from "$lib/ipc";

function room(id: string, membership: Membership = "joined"): RoomSummary {
  return {
    id,
    name: id,
    avatarUrl: null,
    unread: 0,
    lastMessage: null,
    lastMessageIsOwn: false,
    lastMessageNamesSender: false,
    lastEventType: null,
    lastActivityMs: null,
    membership,
  };
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

function makeStore(
  channel: ReturnType<typeof makeChannel>,
  roomsResync: () => Promise<[number, RoomSummary[]]> = vi.fn(),
  invites: {
    joinRoom?: (roomId: string) => Promise<void>;
    leaveRoom?: (roomId: string) => Promise<void>;
    reloadSpaces?: () => Promise<void>;
  } = {},
) {
  return createRoomsStore({
    onRoomsDiff: channel.onRoomsDiff,
    roomsResync,
    makeSessionCommands: makeFakeSessionCommands,
    logout: vi.fn().mockResolvedValue(undefined),
    joinRoom: invites.joinRoom ?? vi.fn().mockResolvedValue(undefined),
    leaveRoom: invites.leaveRoom ?? vi.fn().mockResolvedValue(undefined),
    reloadSpaces: invites.reloadSpaces ?? vi.fn().mockResolvedValue(undefined),
  });
}

/** Lets already-queued microtasks (a resolved resync's continuation) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
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

  it("does not re-arm for a stream that isn't restarting when a login is followed by a mount-time restore", async () => {
    // The shipped bug, end to end. `/login` calls `roomsStore.login` and
    // then `goto("/")`; `/`'s `onMount` calls `roomsStore.restoreSession`.
    // So every login was immediately followed by a restore.
    //
    // Re-arming is a hard reset that says "expect the next stream to start
    // at seq 1". That is correct when the core really is about to restart
    // its `SeqCounter`, and corrupting when it isn't: the login's own
    // room-list stream is already mid-flight at a much higher seq, so its
    // next envelope reads as a gap, the resync that follows pushes the
    // expected sequence back up to that stream's number, and any genuinely
    // fresh stream starting at 1 is then discarded as duplicates forever.
    // The room list froze at login for the rest of the session.
    const channel = makeChannel();
    const resync = vi.fn(async () => [4, [room("!stale:x")]] as [number, RoomSummary[]]);
    const store = makeStore(channel, resync);

    await store.login("https://example.org", "alice", "hunter2");
    channel.emit(env(1, [{ op: "reset", values: [room("!a:x")] }]));
    channel.emit(env(2, [{ op: "pushBack", value: room("!b:x") }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!a:x", "!b:x"]);

    // `/` mounts and asks to restore the session that login just created.
    await expect(store.restoreSession()).resolves.toBe(true);
    await flush();

    // Nothing was re-armed and no resync was provoked, because nothing
    // restarted: the login's stream is still the live one.
    expect(resync).not.toHaveBeenCalled();
    expect(store.rooms.map((r) => r.id)).toEqual(["!a:x", "!b:x"]);

    // And it keeps applying, which is the property that was lost — before
    // the fix the tracker was expecting seq 1 here, read this as a gap, and
    // never recovered.
    channel.emit(env(3, [{ op: "pushBack", value: room("!c:x") }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!a:x", "!b:x", "!c:x"]);
  });

  it("restores again after a logout, since that session really is gone", async () => {
    const channel = makeChannel();
    const store = makeStore(channel);

    await store.login("https://example.org", "alice", "hunter2");
    channel.emit(env(1, [{ op: "reset", values: [room("!a:x")] }]));

    await store.logout();

    // The skip is scoped to "a session is already established", not "we
    // have logged in once" — otherwise logging out and back in would leave
    // the tracker armed for nobody.
    await expect(store.restoreSession()).resolves.toBe(true);
    channel.emit(env(1, [{ op: "reset", values: [room("!next:y")] }]));
    expect(store.rooms.map((r) => r.id)).toEqual(["!next:y"]);
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

// The roster is a *view* of the account's rooms, and since the spaces rail
// landed the reader can narrow it. The room pane must survive that: design
// §7 requires the open room to keep showing when a space filter excludes it.
//
// The timeline half of that is free — nothing re-subscribes. The header was
// not: it looked the selected id up in `rooms` and, finding nothing, fell
// back to the raw `!id:server`. This is the store-side half of the fix.
describe("the selected room's name", () => {
  function named(id: string, name: string): RoomSummary {
    return { ...room(id), name };
  }

  it("comes from the live roster entry, so a rename lands immediately", () => {
    const channel = makeChannel();
    const store = makeStore(channel);
    channel.emit(env(1, [{ op: "reset", values: [named("!a:x", "🧠 Buddhimaan — Squad Lead")] }]));
    store.select("!a:x");

    channel.emit(env(2, [{ op: "set", index: 0, value: named("!a:x", "🧠 Buddhimaan — Fleet Lead") }]));

    expect(store.selectedRoomName).toBe("🧠 Buddhimaan — Fleet Lead");
  });

  it("survives the selected room being filtered out of the roster", () => {
    // Exactly what a space switch does: the core re-emits the roster as a
    // Reset that no longer contains the open room. The selection, the
    // timeline and the name all have to outlive it.
    const channel = makeChannel();
    const store = makeStore(channel);
    channel.emit(
      env(1, [{ op: "reset", values: [named("!a:x", "🧠 Buddhimaan — Squad Lead"), named("!b:x", "Ops")] }]),
    );
    store.select("!a:x");

    channel.emit(env(2, [{ op: "reset", values: [named("!b:x", "Ops")] }]));

    expect(store.selectedId).toBe("!a:x");
    expect(store.selectedRoomName).toBe("🧠 Buddhimaan — Squad Lead");
  });

  it("is null with nothing selected, and again after logout", async () => {
    const channel = makeChannel();
    const store = makeStore(channel);
    expect(store.selectedRoomName).toBeNull();

    channel.emit(env(1, [{ op: "reset", values: [named("!a:x", "Ops")] }]));
    store.select("!a:x");
    await store.logout();

    expect(store.selectedRoomName).toBeNull();
  });
});

describe("roomsStore: invitations (issue #1)", () => {
  it("reports the selected room's membership, so the pane can tell a room from an invitation", () => {
    const channel = makeChannel();
    const store = makeStore(channel);

    channel.emit(
      env(1, [
        { op: "reset", values: [room("!joined:x"), room("!invited:x", "invited")] },
      ]),
    );

    store.select("!invited:x");
    expect(store.selectedMembership).toBe("invited");

    store.select("!joined:x");
    expect(store.selectedMembership).toBe("joined");
  });

  it("claims no membership for a room the roster no longer lists", () => {
    // `selectedRoomName` keeps a remembered fallback for this case; membership
    // deliberately does not. A guess of "joined" would put a composer in front
    // of a room nothing can vouch for.
    const channel = makeChannel();
    const store = makeStore(channel);

    channel.emit(env(1, [{ op: "reset", values: [room("!gone:x")] }]));
    store.select("!gone:x");
    channel.emit(env(2, [{ op: "reset", values: [] }]));

    expect(store.selectedMembership).toBeNull();
  });

  it("accepts an invitation without writing the membership itself", async () => {
    // The join lands as an ordinary room-list diff. Writing `joined` here as
    // well would show the composer before the homeserver agreed — and leave it
    // showing if the join never happened.
    const channel = makeChannel();
    const joinRoom = vi.fn().mockResolvedValue(undefined);
    const store = makeStore(channel, vi.fn(), { joinRoom });

    channel.emit(env(1, [{ op: "reset", values: [room("!invited:x", "invited")] }]));
    store.select("!invited:x");
    await store.acceptInvitation("!invited:x");

    expect(joinRoom).toHaveBeenCalledWith("!invited:x");
    expect(store.selectedMembership).toBe("invited");
  });

  it("surfaces a refused join rather than resolving quietly", async () => {
    // A swallowed failure would take the invitation off screen while the
    // account is still not in the room.
    const channel = makeChannel();
    const joinRoom = vi.fn().mockRejectedValue(new Error("M_FORBIDDEN"));
    const store = makeStore(channel, vi.fn(), { joinRoom });

    await expect(store.acceptInvitation("!invited:x")).rejects.toThrow("M_FORBIDDEN");
  });

  it("declines through leave, which is the one call Matrix has for both", async () => {
    const channel = makeChannel();
    const leaveRoom = vi.fn().mockResolvedValue(undefined);
    const store = makeStore(channel, vi.fn(), { leaveRoom });

    await store.declineInvitation("!invited:x");

    expect(leaveRoom).toHaveBeenCalledWith("!invited:x");
  });
});

describe("accepting an invitation that turns out to be a space", () => {
  it("re-reads the spaces, or the space lands in neither surface", () => {
    // A space leaves the roster the moment it is joined (`roster_admits`), and
    // the rail only ever enumerated joined spaces at load time. Without this
    // refresh the row vanishes and nothing takes its place until relaunch —
    // which is exactly how AgentPod's purpose spaces would have felt.
    const channel = makeChannel();
    const reloadSpaces = vi.fn().mockResolvedValue(undefined);
    const store = makeStore(channel, vi.fn(), { reloadSpaces });

    return store.acceptInvitation("!space:x").then(() => {
      expect(reloadSpaces).toHaveBeenCalledTimes(1);
    });
  });

  it("does not re-read them when the join was refused", () => {
    const channel = makeChannel();
    const reloadSpaces = vi.fn().mockResolvedValue(undefined);
    const store = makeStore(channel, vi.fn(), {
      joinRoom: vi.fn().mockRejectedValue(new Error("M_FORBIDDEN")),
      reloadSpaces,
    });

    return store.acceptInvitation("!space:x").catch(() => {
      expect(reloadSpaces).not.toHaveBeenCalled();
    });
  });

  it("still counts the join as done when the refresh fails", () => {
    // A stale rail is cosmetic and the join already happened; rejecting here
    // would tell the operator the opposite of what occurred.
    const channel = makeChannel();
    const store = makeStore(channel, vi.fn(), {
      reloadSpaces: vi.fn().mockRejectedValue(new Error("offline")),
    });

    return expect(store.acceptInvitation("!space:x")).resolves.toBeUndefined();
  });
});
