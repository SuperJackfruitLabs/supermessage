// What the live view holds, and — more importantly — when it lets go.
//
// The failure this guards against is a duplicate the reader would actually
// notice: the streamed text sitting on screen underneath the room's own copy
// of the same answer, because nobody cleared it when the turn ended.
//
// Fakes only — no Tauri runtime.

import { describe, expect, it } from "vitest";
import { createLiveStore } from "./live.svelte";
import type { LivePayload } from "$lib/ipc";

/** Fake `sm://live` channel; captures the handler synchronously. */
function makeChannel() {
  let handler: ((payload: LivePayload) => void) | null = null;
  return {
    onLive: (onPayload: (payload: LivePayload) => void) => {
      handler = onPayload;
      return Promise.resolve(() => {
        handler = null;
      });
    },
    emit: (payload: LivePayload) => handler?.(payload),
  };
}

const ROOM = "!krishna:id.agentpod.dev";
const OTHER = "!ganesha:id.agentpod.dev";

describe("liveStore", () => {
  it("has nothing to say about a room with no agent writing", () => {
    const channel = makeChannel();
    const store = createLiveStore({ onLive: channel.onLive });

    expect(store.get(ROOM)).toBeNull();
    expect(store.get(null)).toBeNull();
  });

  it("shows the answer so far, replacing it as it grows", () => {
    const channel = makeChannel();
    const store = createLiveStore({ onLive: channel.onLive });

    // Each delta carries the whole answer, not the increment — see the store's
    // module comment for why the wire format is cumulative.
    channel.emit({ roomId: ROOM, seq: 1, text: "I looked at the node.", done: false });
    expect(store.get(ROOM)).toBe("I looked at the node.");

    channel.emit({
      roomId: ROOM,
      seq: 2,
      text: "I looked at the node. It is online.",
      done: false,
    });
    expect(store.get(ROOM)).toBe("I looked at the node. It is online.");
  });

  it("lets go the moment the turn ends, so the room's own copy stands alone", () => {
    const channel = makeChannel();
    const store = createLiveStore({ onLive: channel.onLive });

    channel.emit({ roomId: ROOM, seq: 1, text: "Working on it.", done: false });
    channel.emit({ roomId: ROOM, seq: 2, text: "Working on it. Done.", done: true });

    expect(store.get(ROOM)).toBeNull();
  });

  it("keeps two rooms apart, because two agents can write at once", () => {
    const channel = makeChannel();
    const store = createLiveStore({ onLive: channel.onLive });

    channel.emit({ roomId: ROOM, seq: 1, text: "Krishna is thinking.", done: false });
    channel.emit({ roomId: OTHER, seq: 1, text: "Ganesha is thinking.", done: false });

    expect(store.get(ROOM)).toBe("Krishna is thinking.");
    expect(store.get(OTHER)).toBe("Ganesha is thinking.");

    channel.emit({ roomId: ROOM, seq: 2, text: "Krishna is done.", done: true });

    expect(store.get(ROOM)).toBeNull();
    expect(store.get(OTHER)).toBe("Ganesha is thinking.");
  });
});
