// Where the sidebar is looking — Chats, Needs you, Agents or Search (iOS `AppDestination`) — and
// what the last two of those need: the inbox and its badge, the agent directory and each agent's
// status. They follow the roster the page hands in, which is already the selected space's (Q1).
//
// **Bounded IPC.** The inbox and the directory are asked of the core once per roster (a new
// `rows` array), never once per turn step: a status change re-reads only the statuses, and only
// while Agents is showing. M4's header sent every room on every refresh; this must not.

import {
  roomStatus as defaultRoomStatus,
  roomStatusWord as defaultRoomStatusWord,
  rosterAgentDirectory as defaultRosterAgentDirectory,
  rosterNeedsYouInbox as defaultRosterNeedsYouInbox,
  type NeedsYouInbox,
  type RoomRow,
  type RosterRow,
} from "$lib/ipc";
import { createAgentStatuses, type AgentStatus } from "$lib/components/agentsView";
import { createLatestOnly } from "$lib/components/searchView";

export type Destination = "chats" | "needsYou" | "agents" | "search";

/** iOS's order and titles (`AppDestination`). */
export const DESTINATIONS: { id: Destination; title: string }[] = [
  { id: "chats", title: "Chats" },
  { id: "needsYou", title: "Needs you" },
  { id: "agents", title: "Agents" },
  { id: "search", title: "Search" },
];

/** A destination's accessible name: Needs you says how many decisions wait, when any do. */
export function destinationLabel(d: Destination, badgeCount: number): string {
  const title = DESTINATIONS.find((x) => x.id === d)!.title;
  return d === "needsYou" && badgeCount > 0 ? `${title}, ${badgeCount}` : title;
}

export interface DestinationsDeps {
  rosterNeedsYouInbox: typeof defaultRosterNeedsYouInbox;
  rosterAgentDirectory: typeof defaultRosterAgentDirectory;
  roomStatus: typeof defaultRoomStatus;
  roomStatusWord: typeof defaultRoomStatusWord;
}

const defaultDeps: DestinationsDeps = {
  rosterNeedsYouInbox: defaultRosterNeedsYouInbox,
  rosterAgentDirectory: defaultRosterAgentDirectory,
  roomStatus: defaultRoomStatus,
  roomStatusWord: defaultRoomStatusWord,
};

export function createDestinations(deps: DestinationsDeps = defaultDeps) {
  let selected = $state<Destination>("chats");
  let inbox = $state<NeedsYouInbox | null>(null);
  let agents = $state<RosterRow[]>([]);
  let published = $state<ReadonlyMap<string, AgentStatus>>(new Map());

  const statuses = createAgentStatuses(deps);
  const rosterTicket = createLatestOnly();
  let lastRows: RoomRow[] | null = null;
  let openRoomId: string | null = null;
  let inProgress = false;

  async function refreshStatuses(): Promise<void> {
    if (selected !== "agents") return;
    await statuses.refresh(agents, openRoomId, inProgress);
    published = statuses.snapshot();
  }

  async function readRoster(rows: RoomRow[], nowMs: number): Promise<void> {
    const ticket = rosterTicket.begin();
    const [nextInbox, nextAgents] = await Promise.all([
      deps.rosterNeedsYouInbox(rows, nowMs),
      deps.rosterAgentDirectory(rows, nowMs),
    ]);
    if (!rosterTicket.isCurrent(ticket)) return;
    inbox = nextInbox;
    agents = nextAgents;
    // Only a read that landed counts as asked: a failed one is tried again on the next refresh.
    lastRows = rows;
  }

  return {
    get selected(): Destination {
      return selected;
    },
    select(d: Destination): void {
      selected = d;
      if (d === "agents") void refreshStatuses().catch((e) => console.error("agent statuses failed", e));
    },
    get inbox(): NeedsYouInbox | null {
      return inbox;
    },
    /** Decisions only (RF4): an invitation alone lights nothing. */
    get badgeCount(): number {
      return inbox?.badgeCount ?? 0;
    },
    get agents(): RosterRow[] {
      return agents;
    },
    statusOf(roomId: string): AgentStatus | null {
      return published.get(roomId) ?? null;
    },
    /**
     * Follows the roster, the open room and whether it has a turn running. The core is asked
     * about the roster only when `rows` is a new array; the statuses, only while Agents shows.
     */
    async refresh(rows: RoomRow[], nowMs: number, open: string | null, turnInProgress: boolean): Promise<void> {
      openRoomId = open;
      inProgress = turnInProgress;
      try {
        if (rows !== lastRows) await readRoster(rows, nowMs);
        await refreshStatuses();
      } catch (err) {
        console.error("destinations refresh failed", err);
      }
    },
    /** Leaving an account: back to Chats with nothing of the last one, and no refresh lands late. */
    reset(): void {
      rosterTicket.begin();
      statuses.clear();
      lastRows = null;
      openRoomId = null;
      inProgress = false;
      selected = "chats";
      inbox = null;
      agents = [];
      published = new Map();
    },
  };
}

export const destinations = createDestinations();
