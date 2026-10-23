// Which row carries a finished turn's record (iOS `TimelineCollectionView.swift:812-824`):
// the newest row that is not the reader's own and draws as a bubble. An image,
// a file, a card or a voice note from the agent is not where "What I did" goes.

import type { TimelineDisplayRow } from "../timelineGrouping";

export function recordAnchorKey(rows: readonly TimelineDisplayRow[]): string | null {
  for (let i = rows.length - 1; i >= 0; i--) {
    const row = rows[i]!;
    if (row.type === "item" && !row.item.isOwn && row.view.render === "bubble") return row.key;
  }
  return null;
}
