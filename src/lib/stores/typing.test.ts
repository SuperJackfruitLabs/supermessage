// Regression coverage for the room-scoping race described in
// `typing.svelte.ts`'s module doc comment: a stale typing event from the
// room the reader just left, arriving after `focus` has already switched to
// the new room, must never be shown under the new room's identity.
//
// Fakes only — no Tauri runtime.

import { afterEach, describe, expect, it, vi } from "vitest";
import { createTypingStore, TYPING_TTL_MS } from "./typing.svelte";
import type { TypingPayload, TypingUser } from "$lib/ipc";

const ROOM_A = "!a:example.org";
const ROOM_B = "!b:example.org";

function user(userId: string): TypingUser {
  return { userId, displayName: null };
}

/** Fake `sm://typing` channel; captures the handler synchronously. */
function makeChannel() {
  let handler: ((payload: TypingPayload) => void) | null = null;
  return {
    onTyping: (onPayload: (payload: TypingPayload) => void) => {
      handler = onPayload;
      return Promise.resolve(() => {
        handler = null;
      });
    },
    emit: (payload: TypingPayload) => handler?.(payload),
  };
}

describe("typingStore", () => {
  it("starts empty and ignores an event for a room nothing has focused yet", () => {
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });

    channel.emit({ roomId: ROOM_A, users: [user("@alice:example.org")] });

    expect(store.users).toEqual([]);
  });

  it("shows typing users for the focused room", () => {
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });

    store.focus(ROOM_A);
    channel.emit({ roomId: ROOM_A, users: [user("@alice:example.org")] });

    expect(store.users).toEqual([user("@alice:example.org")]);
  });

  it("ignores an event for a room other than the one focused", () => {
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });

    store.focus(ROOM_A);
    channel.emit({ roomId: ROOM_B, users: [user("@bob:example.org")] });

    expect(store.users).toEqual([]);
  });

  it("clears whatever was shown when focus moves to a new room", () => {
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });

    store.focus(ROOM_A);
    channel.emit({ roomId: ROOM_A, users: [user("@alice:example.org")] });
    expect(store.users).toEqual([user("@alice:example.org")]);

    store.focus(ROOM_B);
    expect(store.users).toEqual([]);
  });

  it("rejects a stale event from the room just left, even after focus has moved on", () => {
    // The exact race this module's doc comment describes: room A's
    // core-side subscription is still live for a moment after the reader
    // switches to room B (the core's teardown/rebuild is async), and could
    // still emit once more for room A before it's torn down.
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });

    store.focus(ROOM_A);
    store.focus(ROOM_B);
    channel.emit({ roomId: ROOM_A, users: [user("@alice:example.org")] });

    expect(store.users).toEqual([]);
  });
});

describe("a typing notice whose ending never arrives", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("expires on its own instead of sitting there forever", () => {
    // The failure this closes: an agent answered, the bridge sent its stop and
    // the homeserver broadcast `user_ids: []`, and the indicator still sat
    // under the composer until the reader left the room and came back —
    // `focus` was the only thing that ever cleared it. Ephemeral events are
    // the one class Matrix never retransmits, so "we were told" cannot be the
    // only way this ends.
    vi.useFakeTimers();
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });
    store.focus(ROOM_A);

    channel.emit({ roomId: ROOM_A, users: [user("@agent:example.org")] });
    expect(store.users).toHaveLength(1);

    vi.advanceTimersByTime(TYPING_TTL_MS - 1);
    expect(store.users).toHaveLength(1);

    vi.advanceTimersByTime(1);
    expect(store.users).toEqual([]);
  });

  it("keeps showing a typist who renews inside the window", () => {
    // A long turn renews every 20s. Expiring one mid-thought would be a
    // different bug wearing the same clothes.
    vi.useFakeTimers();
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });
    store.focus(ROOM_A);

    channel.emit({ roomId: ROOM_A, users: [user("@agent:example.org")] });
    for (let i = 0; i < 5; i += 1) {
      vi.advanceTimersByTime(20_000);
      channel.emit({ roomId: ROOM_A, users: [user("@agent:example.org")] });
    }

    expect(store.users).toHaveLength(1);
  });

  it("clears at once when the ending does arrive, without waiting out the clock", () => {
    vi.useFakeTimers();
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });
    store.focus(ROOM_A);

    channel.emit({ roomId: ROOM_A, users: [user("@agent:example.org")] });
    channel.emit({ roomId: ROOM_A, users: [] });

    expect(store.users).toEqual([]);
  });

  it("does not resurrect a typist after the room changed", () => {
    // The expiry must not fire against whatever room is focused later — it
    // would clear a live typist in a different conversation.
    vi.useFakeTimers();
    const channel = makeChannel();
    const store = createTypingStore({ onTyping: channel.onTyping });
    store.focus(ROOM_A);
    channel.emit({ roomId: ROOM_A, users: [user("@agent:example.org")] });

    store.focus(ROOM_B);
    channel.emit({ roomId: ROOM_B, users: [user("@other:example.org")] });
    vi.advanceTimersByTime(TYPING_TTL_MS - 1);

    expect(store.users).toHaveLength(1);
  });
});
