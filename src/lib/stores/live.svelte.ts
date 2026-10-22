// What an agent is saying, thinking and doing right now, before the room has
// any of it.
//
// AgentPod pushes a turn's text to this account's devices as it is written and
// sends the finished answer to the room once (see the hub's `matrix-as/live.ts`
// for why those are separate channels). This store holds the ephemeral half:
// the answer so far, the reasoning behind it, and the tool calls in flight.
//
// **Nothing here is history.** It is not persisted, not paginated, not
// searchable, and a device that was asleep never sees it. The timeline remains
// the only account of what was said; this is a view of a turn that has not
// landed yet, and it is discarded the moment it does.
//
// The core drops stale and duplicate *text* deltas before they cross IPC
// (`core::live::LiveState`), and stale tool reports too (`core::turns`: a
// report at or below the call's last sequence is refused), so both are applied
// as given.

import {
  onLive as defaultOnLive,
  onThought as defaultOnThought,
  onTool as defaultOnTool,
  type LivePayload,
  type ToolPayload,
  type ToolPhase,
} from "$lib/ipc";

/** One tool call, as the reader sees it. */
export interface LiveTool {
  toolCallId: string;
  title: string;
  kind: string | null;
  /** ACP's raw status. Ordering and debugging only — never shown. */
  status: string;
  /** What a host switches on: decided once, in the core. */
  phase: ToolPhase;
  /** The core's word for `phase`; what a host shows. */
  statusLabel: string;
  locations: string[];
}

export interface LiveStoreDeps {
  onLive: typeof defaultOnLive;
  onThought?: typeof defaultOnThought;
  onTool?: typeof defaultOnTool;
}

const defaultDeps: LiveStoreDeps = {
  onLive: defaultOnLive,
  onThought: defaultOnThought,
  onTool: defaultOnTool,
};

export function createLiveStore(deps: LiveStoreDeps = defaultDeps) {
  // Keyed by room: two rooms can have agents working at once, and the reader
  // may be looking at either.
  let turns = $state<Record<string, string>>({});
  let thoughts = $state<Record<string, string>>({});
  let running = $state<Record<string, LiveTool[]>>({});

  /** Everything ephemeral about a room, dropped together when its turn ends. */
  function forget(roomId: string): void {
    const { [roomId]: _answer, ...restTurns } = turns;
    turns = restTurns;
    const { [roomId]: _thought, ...restThoughts } = thoughts;
    thoughts = restThoughts;
    const { [roomId]: _tools, ...restRunning } = running;
    running = restRunning;
  }

  deps
    .onLive((payload: LivePayload) => {
      if (payload.done) {
        // The room has the real message now. Dropping the live view here is
        // what stops it sitting underneath its own permanent copy — the one
        // duplicate a reader would actually notice. The reasoning and the tool
        // list go with it: they described the turn that just ended.
        forget(payload.roomId);
        return;
      }
      turns = { ...turns, [payload.roomId]: payload.text };
    })
    .catch((err: unknown) => {
      console.error("liveStore: failed to subscribe to live turns", err);
    });

  deps
    .onThought?.((payload: LivePayload) => {
      if (payload.done) {
        const { [payload.roomId]: _gone, ...rest } = thoughts;
        thoughts = rest;
        return;
      }
      thoughts = { ...thoughts, [payload.roomId]: payload.text };
    })
    .catch((err: unknown) => {
      console.error("liveStore: failed to subscribe to agent reasoning", err);
    });

  deps
    .onTool?.((payload: ToolPayload) => {
      // No ordering rule here: the core drops a report the network delivered
      // late (`core::turns`, per tool call and turn) and forwards nothing for it.
      const next: LiveTool = {
        toolCallId: payload.toolCallId,
        title: payload.title,
        kind: payload.kind,
        status: payload.status,
        phase: payload.phase,
        statusLabel: payload.statusLabel,
        locations: payload.locations,
      };
      const current = running[payload.roomId] ?? [];
      const at = current.findIndex((t) => t.toolCallId === payload.toolCallId);
      // Replaced in place rather than moved to the end: the list reads as the
      // order the work happened in, and a tool finishing should not reorder it.
      const updated =
        at === -1
          ? [...current, next]
          : [...current.slice(0, at), next, ...current.slice(at + 1)];
      running = { ...running, [payload.roomId]: updated };
    })
    .catch((err: unknown) => {
      console.error("liveStore: failed to subscribe to tool calls", err);
    });

  return {
    /** What the agent in this room has said so far, or null if none is writing. */
    get(roomId: string | null): string | null {
      if (roomId === null) return null;
      return turns[roomId] ?? null;
    },
    /** What the agent in this room is thinking, or null if it is not saying. */
    thought(roomId: string | null): string | null {
      if (roomId === null) return null;
      return thoughts[roomId] ?? null;
    },
    /** The tool calls this room's agent has made this turn, oldest first. */
    tools(roomId: string | null): LiveTool[] {
      if (roomId === null) return [];
      return running[roomId] ?? [];
    },
  };
}

export const liveStore = createLiveStore();
