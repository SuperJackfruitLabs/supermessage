// TODO(batch B): delete this file. Core batch B puts `isAgent`, `isLongRead`,
// `hidesQuote` and `showsRuntime` on `TimelineRow`; until it merges, the host
// derives stand-ins here so `messageView.ts` already reads the row's own
// fields. After the merge, delete this file, `factsByKey`, `agentIds` and the `cast` prop in
// `Timeline.svelte`: the display row already carries the core's four facts, and `messageRow`
// lets them win over this stopgap.

import type { TimelineDisplayRow } from "../timelineGrouping";
import type { MessageFacts } from "./messageView";

/** The core's `LONG_READ_GRAPHEMES`; this stand-in counts UTF-16 units. */
export const LONG_READ_STOPGAP_LENGTH = 600;

/**
 * Stand-ins for the facts of the item row at `index`.
 *
 * - isAgent: the sender is in the room cast's `agentIds` (the core's decision).
 * - isLongRead: `body.length > 600` (TODO(batch B): row.isLongRead).
 * - hidesQuote: the row directly above is the message it replies to.
 * - showsRuntime: the room has more than one agent.
 */
export function stopgapFacts(
  rows: readonly TimelineDisplayRow[],
  index: number,
  agentIds: readonly string[],
): MessageFacts {
  const row = rows[index];
  if (row === undefined || row.type !== "item") return {};
  const above = rows[index - 1];
  const parent = row.item.replyTo?.eventId ?? null;
  return {
    isAgent: row.item.sender !== null && agentIds.includes(row.item.sender),
    isLongRead: (row.item.body?.length ?? 0) > LONG_READ_STOPGAP_LENGTH,
    hidesQuote:
      parent !== null && above !== undefined && above.type === "item" && above.item.eventId === parent,
    showsRuntime: agentIds.length > 1,
  };
}
