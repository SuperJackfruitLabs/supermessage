// The Agents view's status per room (iOS `AgentsView`): `roomStatus(state, true, turnInProgress)`
// then `roomStatusWord`. Only the open room can say Working, because only its turn is streamed.

import type { RoomStatus, RosterRow } from "$lib/ipc";
import { createLatestOnly } from "./searchView";

export interface AgentStatus {
  status: RoomStatus;
  word: string;
}

export interface AgentStatusDeps {
  roomStatus: (state: RosterRow["state"], describesAgent: boolean, turnInProgress: boolean) => Promise<RoomStatus | null>;
  roomStatusWord: (status: RoomStatus) => Promise<string>;
}

export function createAgentStatuses(deps: AgentStatusDeps) {
  let current = new Map<string, AgentStatus>();
  const latest = createLatestOnly();
  /** The core's word for a status never changes, so each is asked for once. */
  const words = new Map<RoomStatus, Promise<string>>();
  const wordFor = (status: RoomStatus): Promise<string> => {
    let word = words.get(status);
    if (word === undefined) {
      word = deps.roomStatusWord(status);
      words.set(status, word);
    }
    return word;
  };

  return {
    /** Latest-only: a refresh that finishes after a newer one began is dropped. */
    async refresh(agents: RosterRow[], openRoomId: string | null, inProgress: boolean): Promise<void> {
      const ticket = latest.begin();
      const next = new Map<string, AgentStatus>();
      await Promise.all(
        agents.map(async (entry) => {
          const id = entry.row.room.id;
          const status = await deps.roomStatus(entry.state, true, id === openRoomId && inProgress);
          if (status === null) return;
          next.set(id, { status, word: await wordFor(status) });
        }),
      );
      if (!latest.isCurrent(ticket)) return;
      current = next;
    },
    statusOf(roomId: string): AgentStatus | null {
      return current.get(roomId) ?? null;
    },
    /** The statuses as of now, for a store that publishes them reactively. */
    snapshot(): ReadonlyMap<string, AgentStatus> {
      return current;
    },
    /** Forgets everything and drops any refresh still in flight. */
    clear(): void {
      latest.begin();
      current = new Map();
    },
  };
}
