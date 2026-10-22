import type { TimelineItem } from "$lib/ipc";
import type { TimelineDisplayRow } from "../timelineGrouping";

/**
 * Padding that keeps the action pill inside the scroller at both ends.
 *
 * The pill straddles the top edge of its message block (half of it above),
 * so the first loaded row needs room above for that half, and the last row
 * needs room below because its block can be shorter than the pill. 16px is
 * more than the half-pill (about 14px at the default text size).
 */
export function rowEdgeClass(index: number, count: number): string {
  const edges: string[] = [];
  if (index === 0) edges.push("pt-4");
  if (index === count - 1) edges.push("pb-4");
  return edges.join(" ");
}

export type ItemRow = Extract<TimelineDisplayRow, { type: "item" }>;

/**
 * Whether the row at `index` is the last of its run: false only when the
 * next row is a message that continues it. A run's time is shown once, at
 * its end (iOS `TimelineRowView.swift:334-371`).
 */
export function endsRun(rows: readonly TimelineDisplayRow[], index: number): boolean {
  const next = rows[index + 1];
  return !(next !== undefined && next.type === "item" && next.continuesRun);
}

export interface OwnMeta {
  state: "Sending…" | "Not sent" | null;
  edited: boolean;
  showTime: boolean;
}

/**
 * What the line under an own bubble says. A send that is in flight or failed
 * always speaks, and brings the time with it; otherwise the time waits for
 * the end of the run.
 */
export function ownMeta(row: ItemRow, endsRun: boolean): OwnMeta {
  const sendState = row.item.sendState;
  const state =
    sendState === "notSentYet" ? "Sending…" : sendState === "sendingFailed" ? "Not sent" : null;
  return { state, edited: row.item.edited, showTime: endsRun || state !== null };
}

const TIMED_RENDERS: ReadonlySet<string> = new Set(["bubble", "image", "mediaFile", "audio"]);

/**
 * Whether hovering or focusing the row should reveal its time: only for
 * message-shaped renders, and only when the time is not already on screen
 * (a peer's run start has it in the header, an own row has it when
 * `ownMeta` shows it). A dispatch card always shows its own time.
 */
export function revealsTime(row: ItemRow, endsRun: boolean): boolean {
  if (!TIMED_RENDERS.has(row.view.render) || row.item.timestampMs === null) return false;
  if (row.item.isOwn) return !ownMeta(row, endsRun).showTime;
  return row.continuesRun;
}

export interface ReaderFace {
  userId: string;
  mxcUri: string | null;
  initial: string | null;
}

/**
 * One face per sender, from the first message that sender has in `rows`
 * (iOS `TimelineCollectionView.swift:747-757`).
 */
export function readerFaces(rows: readonly TimelineDisplayRow[]): Map<string, ReaderFace> {
  const faces = new Map<string, ReaderFace>();
  for (const row of rows) {
    if (row.type !== "item" || row.item.kind !== "message") continue;
    const userId = row.item.sender;
    if (userId === null || faces.has(userId)) continue;
    faces.set(userId, { userId, mxcUri: row.item.senderAvatar, initial: row.senderInitial });
  }
  return faces;
}

/** The faces for an own message's `readBy`; none for anyone else's message. */
export function readersFor(item: TimelineItem, faces: Map<string, ReaderFace>): ReaderFace[] {
  if (!item.isOwn) return [];
  return item.readBy.map((userId) => faces.get(userId) ?? { userId, mxcUri: null, initial: null });
}
