// What an agent is saying right now, before the room has it.
//
// AgentPod pushes a turn's text to this account's devices as it is written and
// sends the finished answer to the room once (see the hub's `matrix-as/live.ts`
// for why those are separate channels). This store holds the former.
//
// **Nothing here is history.** It is not persisted, not paginated, not
// searchable, and a device that was asleep never sees it. The timeline remains
// the only account of what was said; this is a view of a message that has not
// landed yet, and it is discarded the moment it does.
//
// The core drops stale and duplicate deltas before they cross IPC
// (`core::live::LiveState`), so this store applies whatever it is given.

import { onLive as defaultOnLive, type LivePayload } from "$lib/ipc";

export interface LiveStoreDeps {
  onLive: typeof defaultOnLive;
}

const defaultDeps: LiveStoreDeps = { onLive: defaultOnLive };

export function createLiveStore(deps: LiveStoreDeps = defaultDeps) {
  // Keyed by room: two rooms can have agents writing at once, and the reader
  // may be looking at either.
  let turns = $state<Record<string, string>>({});

  deps
    .onLive((payload: LivePayload) => {
      if (payload.done) {
        // The room has the real message now. Dropping the live text here is
        // what stops it sitting underneath its own permanent copy — the one
        // duplicate a reader would actually notice.
        const { [payload.roomId]: _gone, ...rest } = turns;
        turns = rest;
        return;
      }
      turns = { ...turns, [payload.roomId]: payload.text };
    })
    .catch((err: unknown) => {
      console.error("liveStore: failed to subscribe to live turns", err);
    });

  return {
    /** What the agent in this room has said so far, or null if none is writing. */
    get(roomId: string | null): string | null {
      if (roomId === null) return null;
      return turns[roomId] ?? null;
    },
  };
}

export const liveStore = createLiveStore();
