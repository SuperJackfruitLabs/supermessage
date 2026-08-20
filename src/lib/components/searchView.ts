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
