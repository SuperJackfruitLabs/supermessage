// What the live view holds, and — more importantly — when it lets go.
//
// The failure this guards against is a duplicate the reader would actually
// notice: the streamed text sitting on screen underneath the room's own copy
// of the same answer, because nobody cleared it when the turn ended.
//
// Fakes only — no Tauri runtime.

import { describe, expect, it } from "vitest";
import { createLiveStore } from "./live.svelte";
import type { LivePayload, ToolPayload } from "$lib/ipc";

/**
 * Fake to-device channel; captures the handler synchronously.
 *
 * Generic over the payload so the same fake stands in for all three channels —
 * they differ in meaning, not in plumbing.
 */
function makeChannel<T = LivePayload>() {
  let handler: ((payload: T) => void) | null = null;
  return {
    onLive: (onPayload: (payload: T) => void) => {
      handler = onPayload;
      return Promise.resolve(() => {
        handler = null;
      });
    },
    emit: (payload: T) => handler?.(payload),
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

// An agent's reasoning, on its own channel.
//
// The same payload as the answer and a different meaning: this is never in the
// room, on either side of the bridge. It is watchable while it happens and then
// gone.
describe("liveStore: reasoning", () => {
  it("keeps reasoning apart from the answer for the same room", () => {
    const channel = makeChannel();
    const thoughts = makeChannel();
    const store = createLiveStore({ onLive: channel.onLive, onThought: thoughts.onLive });

    channel.emit({ roomId: ROOM, seq: 1, text: "The answer.", done: false });
    thoughts.emit({ roomId: ROOM, seq: 1, text: "The reasoning.", done: false });

    expect(store.get(ROOM)).toBe("The answer.");
    expect(store.thought(ROOM)).toBe("The reasoning.");
  });

  it("lets go of reasoning when the turn ends", () => {
    const channel = makeChannel();
    const thoughts = makeChannel();
    const store = createLiveStore({ onLive: channel.onLive, onThought: thoughts.onLive });

    thoughts.emit({ roomId: ROOM, seq: 1, text: "Thinking.", done: false });
    thoughts.emit({ roomId: ROOM, seq: 2, text: "Thought.", done: true });

    expect(store.thought(ROOM)).toBeNull();
  });
});

// Tool calls.
//
// The ordering rule lives here rather than in the core: a tool update carries no
// `done`, so the core cannot tell when a turn ended and would drop the next
// turn's updates as stale. Here it sits next to the clear that bounds it.
describe("liveStore: tool calls", () => {
  const tool = (over: Partial<ToolPayload> = {}): ToolPayload => ({
    roomId: ROOM,
    seq: 1,
    toolCallId: "c1",
    title: "Read src/main.ts",
    kind: "read",
    input: null,
    output: null,
    status: "in_progress",
    locations: ["src/main.ts"],
    ...over,
  });

  it("has nothing to show for a room where nothing is running", () => {
    const store = createLiveStore({ onLive: makeChannel().onLive });
    expect(store.tools(ROOM)).toEqual([]);
    expect(store.tools(null)).toEqual([]);
  });

  it("shows a tool call, and merges an update onto it", () => {
    const tools = makeChannel<ToolPayload>();
    const store = createLiveStore({ onLive: makeChannel().onLive, onTool: tools.onLive });

    tools.emit(tool());
    tools.emit(tool({ seq: 2, status: "completed" }));

    expect(store.tools(ROOM)).toEqual([
      { toolCallId: "c1", title: "Read src/main.ts", kind: "read", status: "completed", locations: ["src/main.ts"] },
    ]);
  });

  it("keeps the order tools were first seen in", () => {
    const tools = makeChannel<ToolPayload>();
    const store = createLiveStore({ onLive: makeChannel().onLive, onTool: tools.onLive });

    tools.emit(tool({ toolCallId: "first", seq: 1 }));
    tools.emit(tool({ toolCallId: "second", seq: 2 }));
    tools.emit(tool({ toolCallId: "first", seq: 3, status: "completed" }));

    expect(store.tools(ROOM).map((t) => t.toolCallId)).toEqual(["first", "second"]);
  });

  it("ignores an update the network delivered late", () => {
    // The reason this rule exists at all: a `completed` overtaken by an
    // earlier `in_progress` would leave a finished tool looking busy forever.
    const tools = makeChannel<ToolPayload>();
    const store = createLiveStore({ onLive: makeChannel().onLive, onTool: tools.onLive });

    tools.emit(tool({ seq: 5, status: "completed" }));
    tools.emit(tool({ seq: 4, status: "in_progress" }));

    expect(store.tools(ROOM)[0]!.status).toBe("completed");
  });

  it("forgets a room's tools when its turn ends", () => {
    // Bounded by the answer's `done`, which is the only signal that says a turn
    // is over — and the reason the core cannot apply this rule itself.
    const channel = makeChannel();
    const tools = makeChannel<ToolPayload>();
    const store = createLiveStore({ onLive: channel.onLive, onTool: tools.onLive });

    tools.emit(tool());
    expect(store.tools(ROOM)).toHaveLength(1);

    channel.emit({ roomId: ROOM, seq: 1, text: "Done.", done: true });
    expect(store.tools(ROOM)).toEqual([]);
  });

  it("keeps two rooms' tools apart", () => {
    const tools = makeChannel<ToolPayload>();
    const store = createLiveStore({ onLive: makeChannel().onLive, onTool: tools.onLive });

    tools.emit(tool({ roomId: ROOM, toolCallId: "a" }));
    tools.emit(tool({ roomId: OTHER, toolCallId: "b" }));

    expect(store.tools(ROOM).map((t) => t.toolCallId)).toEqual(["a"]);
    expect(store.tools(OTHER).map((t) => t.toolCallId)).toEqual(["b"]);
  });
});
