// What the room header's second line says: a status pill for an agent's room,
// else how many people are in the room (iOS `RoomScreen.swift:74-123`). The
// rules are the core's (`rosterRow`, `roomStatus`, `roomStatusWord`); this only
// asks, and drops the answer of an ask the reader has moved on from.

import {
  roomInfo as defaultRoomInfo,
  roomStatus as defaultRoomStatus,
  roomStatusWord as defaultRoomStatusWord,
  rosterRow as defaultRosterRow,
  type RoomRow,
  type RoomStatus,
} from "$lib/ipc";

export interface HeaderStatusDeps {
  rosterRow: typeof defaultRosterRow;
  roomStatus: typeof defaultRoomStatus;
  roomStatusWord: typeof defaultRoomStatusWord;
  roomInfo: typeof defaultRoomInfo;
}

const DEFAULT_DEPS: HeaderStatusDeps = {
  rosterRow: defaultRosterRow,
  roomStatus: defaultRoomStatus,
  roomStatusWord: defaultRoomStatusWord,
  roomInfo: defaultRoomInfo,
};

/** "1 member" / "N members"; `null` for none or unknown, which the header leaves out. */
export function memberCountLabel(n: number | null | undefined): string | null {
  if (n === null || n === undefined || !Number.isFinite(n) || n < 1) return null;
  return n === 1 ? "1 member" : `${n} members`;
}

/** The pill's dot colour (iOS `RoomScreen.swift:222-266`). */
export function statusDotClass(status: RoomStatus): string {
  switch (status) {
    case "working":
    case "active":
      return "bg-ok";
    case "needsYou":
      return "bg-signal";
    case "idle":
      return "bg-content-faint";
    case "quiet":
      return "bg-border";
  }
}

/** The pill's ground and word: signal only for a debt, otherwise quiet chrome. */
export function statusPillClass(status: RoomStatus): { ground: string; word: string } {
  return status === "needsYou"
    ? { ground: "bg-signal-soft", word: "text-signal" }
    : { ground: "bg-surface-raised", word: "text-content-muted" };
}

export function createHeaderStatus(deps: HeaderStatusDeps = DEFAULT_DEPS, onChange?: () => void) {
  let status: RoomStatus | null = null;
  let word: string | null = null;
  let members: number | null = null;
  let statusRoom: string | null = null;
  let membersRoom: string | null = null;
  // Bumped by every ask: an answer that finds it moved is stale.
  let statusAsk = 0;
  let membersAsk = 0;

  return {
    get status(): RoomStatus | null {
      return status;
    },
    get word(): string | null {
      return word;
    },
    get members(): number | null {
      return members;
    },
    /**
     * Ask for the room's status from the roster rows the app already holds: one
     * `rosterRow` call, then `roomStatus` and its word. Moving to another room
     * forgets the old status at once; the same room keeps showing its status
     * until the new answer lands, so a refresh does not flicker.
     */
    async refresh(
      roomId: string,
      rows: RoomRow[],
      nowMs: number,
      inProgress: boolean,
    ): Promise<void> {
      const mine = ++statusAsk;
      if (statusRoom !== roomId) {
        statusRoom = roomId;
        if (status !== null || word !== null) {
          status = null;
          word = null;
          onChange?.();
        }
      }
      let next: RoomStatus | null = null;
      let nextWord: string | null = null;
      try {
        const entry = await deps.rosterRow(rows, roomId, nowMs);
        if (mine !== statusAsk) return;
        if (entry !== null) {
          next = await deps.roomStatus(entry.state, entry.describesAgent, inProgress);
          if (mine !== statusAsk) return;
          if (next !== null) {
            nextWord = await deps.roomStatusWord(next);
            if (mine !== statusAsk) return;
          }
        }
      } catch (err) {
        if (mine !== statusAsk) return;
        console.error("headerStatus: could not read the room's status", err);
        next = null;
        nextWord = null;
      }
      if (status === next && word === nextWord) return;
      status = next;
      word = nextWord;
      onChange?.();
    },
    /** Ask for the room's active member count; a room left meanwhile is dropped. */
    async refreshMembers(roomId: string): Promise<void> {
      const mine = ++membersAsk;
      if (membersRoom !== roomId) {
        membersRoom = roomId;
        if (members !== null) {
          members = null;
          onChange?.();
        }
      }
      let next: number | null = null;
      try {
        next = (await deps.roomInfo(roomId)).activeMemberCount;
      } catch (err) {
        if (mine !== membersAsk) return;
        console.error("headerStatus: could not read the member count", err);
      }
      if (mine !== membersAsk || members === next) return;
      members = next;
      onChange?.();
    },
    /** Forget everything (no room selected, or the session ended). */
    clear(): void {
      statusAsk++;
      membersAsk++;
      statusRoom = null;
      membersRoom = null;
      status = null;
      word = null;
      members = null;
      onChange?.();
    },
  };
}
