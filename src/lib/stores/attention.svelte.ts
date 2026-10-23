// The Needs you count in the window title (parity M6, G4) and, with how many agents are working,
// in the tray (M7). The Needs you count is the core's `badgeCount` (decisions only; the number
// behind the sidebar badge), read from the inbox the page already holds, never recounted here.
// "Working" is the core's own `room_status` over the agent directory, the call the Agents view and
// the header pill make. Rust is asked only when a count changes, not on every timeline delta.
//
// Statuses age with time, so the directory is re-read with a fresh clock every minute.

import {
  roomStatus as defaultRoomStatus,
  rosterAgentDirectory as defaultRosterAgentDirectory,
  setAttention as defaultSetAttention,
  type NeedsYouInbox,
  type RoomRow,
  type RosterRow,
} from "$lib/ipc";

export const TICK_MS = 60_000;

export interface AttentionDeps {
  setAttention: (needsYou: number, working: number) => Promise<void>;
  roomStatus: typeof defaultRoomStatus;
  rosterAgentDirectory: typeof defaultRosterAgentDirectory;
  now: () => number;
  setInterval: (fn: () => void, ms: number) => unknown;
  clearInterval: (handle: unknown) => void;
}

const defaultDeps: AttentionDeps = {
  setAttention: defaultSetAttention,
  roomStatus: defaultRoomStatus,
  rosterAgentDirectory: defaultRosterAgentDirectory,
  now: () => Date.now(),
  setInterval: (fn, ms) => globalThis.setInterval(fn, ms),
  clearInterval: (h) => globalThis.clearInterval(h as ReturnType<typeof setInterval>),
};

/** What the page knows that the counts come from. */
export interface AttentionInput {
  inbox: NeedsYouInbox | null;
  /** The roster the directory was read from, for the minute tick to re-read. */
  rows: RoomRow[];
  agents: RosterRow[];
  /** The open room and whether it has a turn running: only its turn is streamed. */
  openRoomId: string | null;
  turnInProgress: boolean;
}

export function createAttention(overrides: Partial<AttentionDeps> = {}) {
  const deps: AttentionDeps = { ...defaultDeps, ...overrides };
  let shown = "0/0";
  let input: AttentionInput | null = null;
  let generation = 0;

  function show(needsYou: number, working: number): void {
    const key = `${needsYou}/${working}`;
    shown = key;
    void deps.setAttention(needsYou, working).catch((e) => {
      console.error("set attention failed", e);
      // The tray is stale: forget what was shown so the next follow retries it
      // (unless newer counts have been sent since).
      if (shown === key) shown = "";
    });
  }

  async function countWorking(agents: RosterRow[], openRoomId: string | null, inProgress: boolean): Promise<number> {
    const statuses = await Promise.all(
      agents.map((a) => deps.roomStatus(a.state, true, a.row.room.id === openRoomId && inProgress)),
    );
    return statuses.filter((s) => s === "working").length;
  }

  async function recompute(next: AttentionInput): Promise<void> {
    const mine = ++generation;
    try {
      const working = await countWorking(next.agents, next.openRoomId, next.turnInProgress);
      if (mine !== generation) return;
      const needsYou = next.inbox?.badgeCount ?? 0;
      if (`${needsYou}/${working}` !== shown) show(needsYou, working);
    } catch (e) {
      console.error("attention failed", e);
    }
  }

  async function tick(): Promise<void> {
    const held = input;
    if (held === null) return;
    const mine = generation;
    try {
      const agents = await deps.rosterAgentDirectory(held.rows, deps.now());
      if (mine !== generation || input !== held) return;
      input = { ...held, agents };
      await recompute(input);
    } catch (e) {
      console.error("attention tick failed", e);
    }
  }

  return {
    /** Follows the page; a null inbox (nothing read yet) counts as zero. */
    async follow(next: AttentionInput): Promise<void> {
      input = next;
      await recompute(next);
    },
    /** Re-reads the statuses every minute until the returned function is called. */
    startTicking(): () => void {
      const handle = deps.setInterval(() => void tick(), TICK_MS);
      return () => deps.clearInterval(handle);
    },
    /** Sign-out or account switch: back to the plain title and a quiet tray, whatever was showing. */
    reset(): void {
      input = null;
      generation++;
      show(0, 0);
    },
  };
}

export const attention = createAttention();
