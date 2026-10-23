// How a search result reads.
//
// Server-side search returns raw ids and a body; a result list is only useful
// if a reader can tell at a glance which conversation a hit came from and
// whether it is the one they meant. That resolution is here, pure, because it
// is the part that can be wrong — the panel just draws it.

import type { RoomRow, SearchResult } from "$lib/ipc";

export interface SearchResultView {
  eventId: string;
  roomId: string;
  /** The room's name, or its id when the roster does not know it. */
  roomLabel: string;
  /** Who said it — a display name is not available here, so the mxid. */
  sender: string;
  /** The message, collapsed to one line and bounded. */
  snippet: string;
  timestampMs: number | null;
}

/** How much of a matching message a row shows. */
export const SNIPPET_MAX = 140;

/**
 * Collapses a message to one line.
 *
 * A hit in a fenced code block or a multi-paragraph answer is common in these
 * rooms, and a result row that grows to forty lines stops being a list.
 */
export function snippet(body: string, max: number = SNIPPET_MAX): string {
  const flat = body.replace(/\s+/g, " ").trim();
  return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`;
}

/**
 * Names the room a hit came from, using the roster the client already has.
 *
 * Falls back to the room id rather than "Unknown room": an id is ugly and
 * true, and a search result the reader cannot place is worse than one that
 * looks technical. A room the roster does not list is a real case — a search
 * reaches rooms the current space filter hides.
 */
export function roomLabel(roomId: string, rooms: readonly RoomRow[]): string {
  return rooms.find((row) => row.room.id === roomId)?.room.name ?? roomId;
}

export function projectSearchResults(
  results: readonly SearchResult[],
  rooms: readonly RoomRow[]
): SearchResultView[] {
  return results.map((result) => ({
    eventId: result.eventId,
    roomId: result.roomId,
    roomLabel: roomLabel(result.roomId, rooms),
    sender: result.sender,
    snippet: snippet(result.body),
    timestampMs: result.timestampMs,
  }));
}

/** The room a search was opened from. */
export interface SearchScope {
  roomId: string;
  name: string;
}

/** What the panel says before the first search. */
export function searchHint(scope: SearchScope | null, narrowed: boolean): string {
  return scope !== null && narrowed
    ? `Find a message in ${scope.name}.`
    : "Find a message across your rooms.";
}

/** The room id to pass to the search, or undefined to search every room. */
export function scopedRoomId(scope: SearchScope | null, narrowed: boolean): string | undefined {
  return scope !== null && narrowed ? scope.roomId : undefined;
}

/**
 * Ticket counter for requests where only the newest answer may be used.
 *
 * Changing the scope re-runs a search while the older one may still be in
 * flight; whichever resolves last would otherwise win, even if it is the
 * older query.
 */
export function createLatestOnly(): { begin(): number; isCurrent(ticket: number): boolean } {
  let current = 0;
  return {
    begin: () => ++current,
    isCurrent: (ticket) => ticket === current,
  };
}

/**
 * Whether changing the scope should run the search again: only when a search
 * was made (a failed one counts, so the reader can retry by changing scope)
 * and there is still a term to run.
 */
export function rerunsOnScopeChange(args: {
  changed: boolean;
  searched: boolean;
  term: string;
}): boolean {
  return args.changed && args.searched && args.term.trim() !== "";
}

/**
 * What Escape does in the search panel: the overlay closes; the one embedded in the sidebar has
 * nothing to close, so it clears the term and stays.
 */
export function escapeOutcome(embedded: boolean): "close" | "clear" {
  return embedded ? "clear" : "close";
}
