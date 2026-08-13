// Regression coverage for the room-scoping race described in
// `typing.svelte.ts`'s module doc comment: a stale typing event from the
// room the reader just left, arriving after `focus` has already switched to
// the new room, must never be shown under the new room's identity.
//
// Fakes only — no Tauri runtime.

import { describe, expect, it } from "vitest";
import { createTypingStore } from "./typing.svelte";
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
