// Collapses consecutive membership-change items into a single system line,
// so a room with many agents joining/leaving doesn't bury the conversation
// under one near-identical row per change. `docs/matrix-events.md` (Table B)
// calls for collapsing these runs; this module is the pure grouping logic
// `Timeline.svelte` renders from.
//
// Kept in its own module, mirroring `core::item_view` and
// `draftTracker.ts`, so the grouping is unit-testable without a DOM.
//
// This is a *presentation*-layer concern only. The timeline is driven by
// sequence-numbered diffs applied to `timelineStore.items` (see
// `timeline.svelte.ts` and `core::timeline`'s doc comment), and `virtua`
// virtualises that list by index — grouping must never mutate, filter, or
// reorder the source array, or disturb the identity/keys the rest of the
// app uses for it. `groupTimelineItems` below only ever *reads* `items`; it
// returns a new derived list of "display rows", each of which either wraps
// one original `TimelineItem` unchanged or bundles several into a group,
// but never drops, copies, or edits an item's own fields. `Timeline.svelte`
// calls this once per render from the raw item array (a `$derived`), so
// grouping recomputes automatically whenever a new event arrives (append),
// history is paginated in (prepend), or an item changes in place (edit) —
// there is no cached grouping state of its own to fall out of sync.
//
// Runs are broken on:
//  - any non-membership item, including a date divider — a run interrupted
//    by a real message or a date divider is two runs, never merged across
//    it (see this module's tests).
//  - a change in membership *verb* (`detail`): a run of joins immediately
//    followed by a leave produces two adjacent groups, not one sentence
//    that would misleadingly describe both as the same kind of change.
//    This is the "group by verb, don't merge across verbs" choice from the
//    task brief, picked over a neutral fallback sentence ("membership
//    changed") because a verb-pure group is exactly as informative as the
//    original ungrouped lines, just fewer of them — a neutral summary would
//    throw that information away for no reason.
//  - This module doesn't consult `timelineItemView.viewFor` at all — it
//    groups on `kind`/`detail` alone, not on what would ultimately render.
//    That keeps it decoupled from the render-decision vocabulary (a new
//    suppressed kind added there doesn't change grouping behaviour here) at
//    the cost of one edge case: an invisible item of a different kind sitting
//    between two membership runs (e.g. a suppressed `profileChange`) still
//    splits them into two groups even though nothing renders in between. That
//    is judged an acceptable, rare cosmetic trade-off against the simplicity
//    of not coupling this module to render decisions.
//
// Sender-run collapsing (spec §6.3) is a second, independent grouping rule
// living in the same module rather than a parallel mechanism, per that
// section's instruction to extend this file rather than add a second one.
// It marks — but never removes — a row: every non-membership item still
// gets exactly one `{type: "item"}` row (unlike membership runs, which
// disappear into a `membershipGroup`), so `continuesRun` is purely
// informational and `Timeline.svelte` decides what to do with it (Task 6:
// suppress the redundant sender line and tighten the gap). An item
// `continuesRun` when the *immediately preceding display row* — not the
// previous raw item — is itself an `item` row whose underlying item is
// message-shaped, same non-null `sender`, within `SENDER_RUN_WINDOW_MS` of
// this item's timestamp **in either direction** (see `continuesSenderRun`'s
// doc comment for why the window is symmetric), and this item is
// message-shaped too. Reading off
// the previous *display* row rather than the previous *raw* item is what
// makes a collapsed membership group (or a date divider, which also gets
// its own `item` row) break a run for free: once it's the immediately
// preceding row, it's already not a qualifying `item` row of a message, so
// no special-casing of membership or date-divider kinds is needed here
// beyond what "message-shaped" already excludes.
//
// "Message-shaped" is intentionally narrow: `kind === "message"` and
// nothing else. A custom event (`kind: "customMessage"`) is about to become
// a bordered card with its own header of its own (spec §7) — it must never
// silently continue, or be continued into, a run of plain-text bubbles, so
// it is excluded exactly like a state event, a redaction, or a membership
// change. This mirrors the run-breaking list above: anything that isn't an
// ordinary message breaks a run both as the *previous* row and as the
// *current* item.
//
// Like the membership-run grouping above, this does not consult
// `timelineItemView.viewFor` — it reads `kind`/`sender`/`timestampMs`
// directly off `TimelineItem`, for the same reason: staying decoupled from
// the render-decision vocabulary so a future `core::item_view::view_for` change can't
// silently change which messages collapse into a run.

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
      /** What the composer shows when someone replies to this item. */
      replyPreview: string | null;
      continuesRun: boolean;
    }
  | { type: "membershipGroup"; key: string; items: TimelineItem[]; text: string };

/**
 * How close two consecutive messages from the same sender must be, in
 * milliseconds, to read as one continuous run rather than two separate
 * turns — spec §6.3's "within 5 minutes". Exported so the test file (and
 * any future caller) states its fixture timestamps relative to this
 * constant instead of a duplicated magic number.
 */
export const SENDER_RUN_WINDOW_MS = 5 * 60_000;

/** The narrow "message-shaped" predicate the run rule uses — see the doc comment above. */
function isMessageShaped(item: TimelineItem): boolean {
  return item.kind === "message";
}

/**
 * Whether `item`, about to become an `{type: "item"}` row, continues a
 * sender run started by `previousRow` — the display row immediately before
 * it, or `undefined` at the start of the timeline. See the module doc
 * comment for why this is computed from the previous *row*, not the
 * previous *raw* item.
 *
 * The window check is symmetric (`Math.abs`), deliberately: real Matrix
 * timelines do produce items whose `timestampMs` isn't strictly increasing
 * (local echo vs. server timestamp, federation lag), so a signed
 * `item.timestampMs - previousItem.timestampMs` would let a message dated
 * arbitrarily far *before* its predecessor still read as "within the
 * window" — the negative delta compares as well inside it. `Math.abs` keeps
 * the behaviour this rule exists for (a message a few seconds out of order
 * from the same sender still reads as one continuous turn) while bounding
 * both directions the same way the 5-minute window already bounds the
 * forward one.
 */
function continuesSenderRun(previousRow: TimelineDisplayRow | undefined, item: TimelineItem): boolean {
  if (!previousRow || previousRow.type !== "item") return false;
  const previousItem = previousRow.item;
  if (!isMessageShaped(previousItem) || !isMessageShaped(item)) return false;
  if (previousItem.sender == null || item.sender == null) return false;
  if (previousItem.sender !== item.sender) return false;
  if (previousItem.timestampMs == null || item.timestampMs == null) return false;
  return Math.abs(item.timestampMs - previousItem.timestampMs) <= SENDER_RUN_WINDOW_MS;
}

/** How many members a collapsed line names explicitly before "and N others". */
const MAX_NAMED = 2;

/** "Alice" / "Alice and Bob" / "Alice, Bob and Carol" — English list joining. */
function joinNames(names: string[]): string {
  if (names.length === 1) return names[0]!;
  if (names.length === 2) return `${names[0]} and ${names[1]}`;
  return `${names.slice(0, -1).join(", ")} and ${names[names.length - 1]}`;
}

/**
 * Builds the collapsed sentence for a run of same-verb membership items.
 * `items` is never empty (only called from `flushRun` below, which is
 * itself a no-op on an empty run) and every item in it shares the same
 * `detail`, by construction of `groupTimelineItems`.
 *
 * Names at most `MAX_NAMED` people explicitly, then "and N other(s)" — a
 * run of any size produces a sentence of bounded length, and a run of
 * exactly one reads exactly like the ungrouped `core::item_view`
 * membership line ("Alice joined the room"), never "Alice and 0 others".
 */
function groupText(rows: TimelineRow[]): string {
  // Both halves come from the core. The verb is carried on the row apart from
  // the rendered "Alice joined the room" sentence precisely so a *run* can be
  // composed from many names and one verb without parsing that sentence back
  // apart; the names come from `senderName`, which resolves display name ->
  // sender id -> placeholder the same way every other attribution does.
  const verb = rows[0]!.membershipVerb ?? "updated their membership";
  // DISTINCT senders, not one name per event. A run is consecutive items
  // sharing a verb, and one member can produce several of them: a real room
  // rendered "Annapurna, Annapurna and 1 other updated their membership",
  // naming the same person twice and counting her toward "others". Found on
  // Android, where the ported copy of this function had inherited the same
  // defect; fixed there first.
  const names = [...new Set(rows.map((row) => row.senderName))];
  if (names.length <= MAX_NAMED) {
    return `${joinNames(names)} ${verb}`;
  }
  const named = names.slice(0, MAX_NAMED);
  const remaining = names.length - MAX_NAMED;
  const othersWord = remaining === 1 ? "other" : "others";
  return `${named.join(", ")} and ${remaining} ${othersWord} ${verb}`;
}

/**
 * Derives the display list `Timeline.svelte` renders from the raw,
 * diff-driven item array. Never mutates `items`; array order is preserved,
 * and every `TimelineItem` a caller sees in the result is the exact same
 * object reference that was in `items` (so any identity-based comparison
 * elsewhere — e.g. `$effect`s keyed on the last item's id — still works
 * against the raw array itself, which callers keep reading directly for
 * that).
 *
 * Grouped rows key off the *first* item's id in the run
 * (`"group:" + firstId`), not e.g. a hash of every id in the run —
 * deliberately, so a run that's still growing (a new membership item just
 * arrived and extended it) keeps the *same* key across a recompute. virtua's
 * `getKey` uses this to decide whether a row is "the same row, new content"
 * (patched in place) or "a different row" (unmounted and a new one
 * mounted); a key that changed shape on every append would remount that
 * row's DOM node — and disturb virtua's size cache and scroll anchoring —
 * on every single membership event in a long run, instead of just updating
 * its text in place the way an ordinary growing message list already does.
 * A single-item passthrough row keys off the item's own id unchanged, so
 * grouping never destabilises the key of an item that isn't even part of a
 * run.
 */
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
 */
export function shouldShift(previous: readonly string[], next: readonly string[]): boolean {
  if (previous.length === 0 || next.length === 0) return false;

  const indexInNext = new Map(next.map((key, index) => [key, index]));
  for (let i = 0; i < previous.length; i += 1) {
    const moved = indexInNext.get(previous[i]!);
    if (moved === undefined) continue;
    return moved !== i;
  }
  return false;
}

export function groupTimelineItems(source: readonly TimelineRow[]): TimelineDisplayRow[] {
  const rows: TimelineDisplayRow[] = [];
  let run: TimelineRow[] = [];

  function flushRun(): void {
    if (run.length === 0) return;
    rows.push({
      type: "membershipGroup",
      key: `group:${run[0]!.item.id}`,
      items: run.map((row) => row.item),
      text: groupText(run),
    });
    run = [];
  }

  for (const row of source) {
    const item = row.item;
    const continuesMembershipRun =
      item.kind === "membership" && (run.length === 0 || run[0]!.item.detail === item.detail);
    if (continuesMembershipRun) {
      run.push(row);
      continue;
    }
    flushRun();
    if (item.kind === "membership") {
      // A membership item with a different verb than the run just flushed:
      // starts a fresh run of its own rather than passing through.
      run.push(row);
    } else {
      // `rows.at(-1)` here is the previous *display* row, including a
      // membership group `flushRun()` may have just pushed above — see the
      // module doc comment for why that's what the sender-run rule reads.
      rows.push({
        type: "item",
        key: rowKey(item),
        item,
        view: row.view,
        canReplyOrReact: row.canReplyOrReact,
        replyQuote: row.replyQuote,
        senderName: row.senderName,
        replyPreview: row.replyPreview,
        continuesRun: continuesSenderRun(rows.at(-1), item),
      });
    }
  }
  flushRun();

  return rows;
}
