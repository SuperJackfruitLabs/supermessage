// How the timeline draws the core's grouping.
//
// The rules themselves (sender runs, reply quotes, runtime suffixes, and which
// membership changes share a sentence or fold into one line) are the core's,
// decided once in `timeline_grouping.rs` and carried on every `TimelineRow`
// the delta channel sends: `continuesRun`, `endsRun`, `hidesQuote`,
// `showsRuntime` and `membership`. This module keeps what a host owns: the
// mechanical walk that turns those fields into display rows, which collapsed
// stretches the reader has opened, and the virtualiser's keys and `shift`.
// It computes no grouping of its own, so there is nothing here to drift from
// iOS and Android.
//
// Kept in its own module, mirroring `core::item_view` and `draftTracker.ts`,
// so the walk is unit-testable without a DOM.
//
// This is a *presentation*-layer concern only. The timeline is driven by
// sequence-numbered diffs applied to `timelineStore.items` (see
// `timeline.svelte.ts` and `core::timeline`'s doc comment), and `virtua`
// virtualises the derived list by index. `groupTimelineItems` only ever
// *reads* its source: it never mutates, filters (beyond the rows the core
// said draw nothing), copies or reorders the items, and every item a caller
// sees in the result is the same object that was in the source.
//
// What the walk does:
//  - A row whose view is `none` draws nothing and is skipped. The core marks a
//    no-op membership change, a suite event whose prose already draws the
//    card, and a voice reply folded into its text this way. The core's own
//    pass ignores such rows too, so skipping them splits no run.
//  - A membership row with a `membership` placement joins an entry: the run
//    (`group:<first row>`, the sentence on its first row), or, while its
//    stretch is collapsed and not in `expanded`, the whole stretch
//    (`stretch:<first row>`). Entries are keyed on the first row's id so a
//    run that is still growing keeps its key across a recompute, which is
//    what lets virtua patch the row in place instead of remounting it.
//  - A membership row without a placement (one the core has not settled) is
//    drawn as the ordinary row it is.
//  - Consecutive messages this device has no keys for become one
//    `undecryptableGroup` with a count, never a column of identical lines.
//    That one is presentation, not a grouping rule, so it stays here.
//  - Every other row is one `item` row; `continuesRun` and `endsRun` are the core's.

import type { ItemView, TimelineItem, TimelineRow } from "$lib/ipc";

/** One row `Timeline.svelte` iterates, in place of the raw item array. */
export type TimelineDisplayRow =
  | {
      type: "item";
      key: string;
      item: TimelineItem;
      /** The core's render decision for `item`, carried through unchanged. */
      view: ItemView;
      /** Whether this item can be replied to or reacted to yet. */
      canReplyOrReact: boolean;
      /** The core's resolved reply quote, or `null` when this is not a reply. */
      replyQuote: TimelineRow["replyQuote"];
      /** Who to attribute this item to, resolved by the core. */
      senderName: string;
      /** The same without the bridge's runtime suffix — the sender line. */
      senderShort: string;
      /** The glyph for the sender's face, resolved by the core. */
      senderInitial: string;
      /** What the composer shows when someone replies to this item. */
      replyPreview: string | null;
      continuesRun: boolean;
      /** Whether this row is the last of its run (the core's; absent on the wire means false). */
      endsRun: boolean;
    }
  | { type: "membershipGroup"; key: string; items: TimelineItem[]; text: string }
  /**
   * A stretch of three or more membership runs folded into one summary line.
   * `key` is the stretch id, which is what `expanded` holds once it is opened.
   */
  | { type: "membershipStretch"; key: string; text: string }
  /**
   * A run of messages this device has no keys for, drawn as one row with a
   * count and a way to recovery — never a column of identical lines.
   */
  | { type: "undecryptableGroup"; key: string; items: TimelineItem[]; count: number };

/**
 * A row's key: its identity, plus whatever changes the row's *shape*.
 *
 * virtua stores measured item sizes **per key** (its README's FAQ, "Why my
 * items are squashed (or rendered inconsistently) on resize/add/remove?"), and
 * `VListHandle` exposes no way to ask for a remeasure. So a row whose key does
 * not change keeps the height it was first measured at, however much its
 * content grows.
 *
 * That was invisible while the core replayed the SDK's remove-then-readd
 * literally: every update destroyed the row and built a new one, which
 * re-measured it as a side effect. Once `core::dto::collapse_reinsertions`
 * stopped a message vanishing on every send, the cost showed up in its place —
 * a reaction arriving grew the content past the cached height and the chip was
 * painted over by the row beneath it.
 *
 * So the reaction count rides in the key. Not the whole item, which would
 * remount a row for a changed timestamp and undo the fix above; and not
 * identity alone, which is what produced the clipped chip.
 *
 * Known gap, left deliberately: an **edit** can also change a row's height, and
 * is not in the key. No report of it, and guessing at which fields matter is
 * how this ends up keyed on everything. Add it when something demonstrates it.
 */
function rowKey(item: TimelineItem): string {
  return `${item.id}:${item.reactions.length}`;
}

/**
 * The value for virtua's `shift` prop, given the row keys before and after an
 * update: **true only when the list changed at its head.**
 *
 * `shift` tells virtua to hold the scroll position against the *end* of the
 * list rather than the start, which is what you want when rows are added to or
 * removed from the beginning — back-pagination prepending older history, or a
 * limited sync unloading the oldest events. virtua's own docs are explicit
 * that it is for that case and no other: "recommended to set to false if
 * modifications occur in the middle or end of the list to prevent unexpected
 * behavior".
 *
 * It shipped hard-coded `true`, which claimed *every* update was a head
 * change. Measured in the running app on 2026-08-17: sending one message moved
 * every cached offset down by that message's own height (108px, matching the
 * appended row exactly), virtua's range computation then pointed at the wrong
 * rows, and it unmounted the ones actually on screen. Up to 602px of a 911px
 * viewport painted no row at all — bare `--color-surface-sunken` where the
 * sheet should be — and stayed blank until a manual scroll forced a
 * recomputation. The same probe with `shift` off peaked at a 125px transient
 * lasting a single frame. The reader's report was "all previous messages
 * disappeared until I scrolled", and the history was never gone: virtua simply
 * was not painting it.
 *
 * The test is whether the rows that **survived** the update moved. The first
 * key present in both lists is found, and its index compared: same index means
 * everything above it is unchanged and the edit landed in the middle or at the
 * end; a different index means rows were inserted or dropped above it, which
 * is exactly the case `shift` exists for. Scanning for the first *survivor*
 * rather than trusting `previous[0]` is what makes a batch that drops the
 * oldest row and prepends history — `core::timeline`'s `reset_shrank` path —
 * still read as a head change.
 *
 * No survivor at all means these two lists are not versions of each other (a
 * room switch), and an empty list on either side means there is nothing to
 * hold in place. Both answer `false`, which is virtua's ordinary
 * anchor-from-the-start behaviour.
 *
 * Pure over the two key arrays, so the decision is testable without a DOM —
 * the rendering it drives is not, which is why the numbers above were measured
 * against the running app and are quoted here rather than asserted.
 *
 * `skip` names rows that are not anchors: the SDK keeps the day's date
 * divider at the top while it inserts older events of the same day *after*
 * it, so a divider that "did not move" says nothing about the history below
 * it (measured in "Buddhimaan" on 2026-10-03, 38 -> 48 rows).
 */
export function shouldShift(
  previous: readonly string[],
  next: readonly string[],
  skip: ReadonlySet<string> = new Set(),
): boolean {
  if (previous.length === 0 || next.length === 0) return false;

  const indexInNext = new Map(next.map((key, index) => [key, index]));
  for (let i = 0; i < previous.length; i += 1) {
    if (skip.has(previous[i]!)) continue;
    const moved = indexInNext.get(previous[i]!);
    if (moved === undefined) continue;
    return moved !== i;
  }
  return false;
}

const NOTHING_OPENED: ReadonlySet<string> = new Set();

/**
 * The display rows to draw for `source`, in order.
 *
 * `expanded` holds the ids of the stretches the reader has opened; every
 * other collapsed stretch draws as its one summary line.
 */
export function groupTimelineItems(
  source: readonly TimelineRow[],
  expanded: ReadonlySet<string> = NOTHING_OPENED,
): TimelineDisplayRow[] {
  const rows: TimelineDisplayRow[] = [];
  // Where each open run or stretch sits in `rows`, so its later rows land in it.
  const entryIndex = new Map<string, number>();
  let undecryptable: TimelineRow[] = [];

  function flushUndecryptable(): void {
    if (undecryptable.length === 0) return;
    rows.push({
      type: "undecryptableGroup",
      key: `utd:${undecryptable[0]!.item.id}`,
      items: undecryptable.map((row) => row.item),
      count: undecryptable.length,
    });
    undecryptable = [];
  }

  function join(id: string, text: string | undefined, stretch: boolean, row: TimelineRow): void {
    const index = entryIndex.get(id);
    const entry = index === undefined ? undefined : rows[index];
    if (entry?.type === "membershipGroup") {
      entry.items.push(row.item);
    } else if (entry === undefined && text !== undefined) {
      entryIndex.set(id, rows.length);
      rows.push(stretch ? { type: "membershipStretch", key: id, text } : { type: "membershipGroup", key: id, items: [row.item], text });
    }
  }

  for (const row of source) {
    if (row.view.render === "none") continue;
    if (row.view.render === "placeholder" && row.view.kind.about === "unableToDecrypt") {
      undecryptable.push(row);
      continue;
    }
    flushUndecryptable();
    const placement = row.item.kind === "membership" ? row.membership : undefined;
    if (placement) {
      const stretchId = placement.stretchId;
      if (stretchId !== undefined && !expanded.has(stretchId)) {
        join(stretchId, placement.stretchText, true, row);
      } else {
        join(placement.runId, placement.runText, false, row);
      }
      continue;
    }
    rows.push({
      type: "item",
      key: rowKey(row.item),
      item: row.item,
      view: row.view,
      canReplyOrReact: row.canReplyOrReact,
      replyQuote: row.replyQuote,
      senderName: row.senderName,
      senderShort: row.senderShort,
      senderInitial: row.senderInitial,
      replyPreview: row.replyPreview,
      continuesRun: row.continuesRun ?? false,
      endsRun: row.endsRun ?? false,
    });
  }
  flushUndecryptable();

  return rows;
}
