// What an agent is saying, thinking and doing in the focused room, before the
// room has any of it.
//
// AgentPod pushes a turn's text to this account's devices as it is written and
// sends the finished answer to the room once. The core folds those into a turn
// record (`core::turns`) and sends it on `sm://turn`; this store mirrors that
// record for the room being looked at (`liveTurn.ts` holds the rules) and adds
// the answer text, which is not part of the record and streams on its own
// channel.
//
// **Nothing here is history.** It is not persisted or searchable, and a device
// that was asleep never sees it. The timeline remains the only account of what
// was said. The core drops stale and duplicate deltas and stale tool reports
// before they cross IPC, so what arrives is applied as given; the one guard
// kept here is the revision, because events and the `turn` snapshot race.
//
// There is no `onTool` subscription: steps arrive on `sm://turn`.

import {
  onLive as defaultOnLive,
  onThought as defaultOnThought,
  onTurn as defaultOnTurn,
  turn as defaultTurn,
  type LivePayload,
  type ToolPhase,
  type TurnUpdate,
} from "$lib/ipc";
import { untrack } from "svelte";
import { applyTurnUpdate, emptyTurn, restoreTurn, turnHasGap, type LiveTurnState } from "./liveTurn";

/** One tool call, as the story fixtures write it (`$lib/fixtures/live`). */
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
  onThought: typeof defaultOnThought;
  onTurn: typeof defaultOnTurn;
  turn: typeof defaultTurn;
}

const defaultDeps: LiveStoreDeps = {
  onLive: defaultOnLive,
  onThought: defaultOnThought,
  onTurn: defaultOnTurn,
  turn: defaultTurn,
};

export function createLiveStore(deps: LiveStoreDeps = defaultDeps) {
  let state = $state<LiveTurnState>(emptyTurn(null));
  let agentNames = $state<Record<string, string>>({});
  // Bumped by every focus() and clear(): a restore that finds it moved is for a room left.
  let generation = 0;

  const guard = (what: string) => (err: unknown) => {
    console.error(`liveStore: failed to subscribe to ${what}`, err);
  };

  // Asks the core for the whole record again: an update arrived that cannot be
  // applied without leaving a hole, or the record is still short of its count.
  // Steps are never appended past a gap; the core's snapshot is the only repair.
  const refetch = async () => {
    const roomId = state.roomId;
    if (roomId === null) return;
    const mine = generation;
    try {
      const snap = await deps.turn(roomId);
      if (mine !== generation) return;
      state = restoreTurn(state, snap);
    } catch (err) {
      console.error("liveStore: could not refresh the turn", err);
    }
  };

  deps
    .onTurn((u: TurnUpdate) => {
      const gap = turnHasGap(state, u);
      state = applyTurnUpdate(state, u);
      if (gap || (state.summary !== null && state.steps.length < state.summary.totalSteps)) void refetch();
    })
    .catch(guard("turn updates"));

  deps
    .onLive((payload: LivePayload) => {
      if (payload.roomId !== state.roomId) return;
      // done: the room has the real message now. Dropping the text here is what
      // stops it sitting underneath its own permanent copy.
      state = { ...state, answer: payload.done ? null : payload.text };
    })
    .catch(guard("live turns"));

  deps
    .onThought((payload: LivePayload) => {
      if (payload.roomId !== state.roomId) return;
      // A thought's own `done` keeps the text (iOS `LiveStore.handleThought`):
      // it is the record's reasoning, and the answer's row shows it once the
      // turn has ended. A new turn clears it (`applyTurnUpdate`).
      if (payload.done) return;
      state = { ...state, thought: payload.text };
    })
    .catch(guard("agent reasoning"));

  return {
    get summary() {
      return state.summary;
    },
    get steps() {
      return state.steps;
    },
    get thought() {
      return state.thought;
    },
    get answer() {
      return state.answer;
    },
    get inProgress(): boolean {
      return state.summary?.inProgress ?? false;
    },
    get finished(): boolean {
      return state.summary?.endedBy != null;
    },
    get isLive(): boolean {
      return state.summary !== null || state.answer !== null;
    },
    /** The room the mirror is looking at. */
    get roomId(): string | null {
      return state.roomId;
    },

    /** Looks at `roomId`: forgets the last room's turn and restores this one's from the core. */
    async focus(roomId: string | null): Promise<void> {
      const mine = ++generation;
      state = emptyTurn(roomId);
      if (roomId === null) return;
      try {
        const snap = await deps.turn(roomId);
        if (mine !== generation) return;
        state = restoreTurn(state, snap);
      } catch (err) {
        console.error("liveStore: could not restore the turn", err);
      }
    },

    // Reads the map untracked: a caller inside an `$effect` (the page's name
    // effect) would otherwise depend on the state it writes and loop until
    // `effect_update_depth_exceeded`. Unchanged names write nothing.
    setAgentName(name: string | null, roomId: string): void {
      const current = untrack(() => agentNames);
      if (name === null) {
        if (!(roomId in current)) return;
        const { [roomId]: _gone, ...rest } = current;
        agentNames = rest;
      } else {
        if (current[roomId] === name) return;
        agentNames = { ...current, [roomId]: name };
      }
    },
    agentName(roomId: string | null): string | null {
      return roomId === null ? null : (agentNames[roomId] ?? null);
    },

    clear(): void {
      generation++;
      state = emptyTurn(null);
      agentNames = {};
    },
  };
}

export type LiveStore = ReturnType<typeof createLiveStore>;

export const liveStore = createLiveStore();
