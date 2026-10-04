// Keeping a reader at the tail of the conversation when the pane changes
// shape under them.
//
// The timeline is the `flex-1` in a column of `shrink-0` siblings — `LiveTurn`,
// the typing indicator, the connection banner, the composer — every one of
// which can appear or grow while the reader sits perfectly still, and every one
// of which takes its height out of this one. Meanwhile the scrolled content
// grows from the bottom as messages arrive, and keeps growing after that as
// virtua measures rows it had only estimated.
//
// Scroll offsets are measured from the *top*, so all of that lands off the
// bottom: the tail slides under the fold with `scrollTop` untouched, and a
// resize is not a scroll, so nothing runs to notice.

/** The two measurements that decide where the tail is. */
export interface PaneMetrics {
  /** The visible height of the scroller. */
  viewport: number;
  /** The full scrollable height of its content. */
  content: number;
}

/**
 * Whether a reader who was following the tail should be carried back to it.
 *
 * Two things push the tail under the fold, and both are silent:
 *
 *  - **the viewport shrank** — a sibling panel opened and took the height;
 *  - **the content grew** — a message arrived, or virtua measured a row it had
 *    been estimating and found it taller.
 *
 * Growth of the viewport needs nothing: the tail is coming back into view by
 * itself. Content *shrinking* needs nothing either — the bottom moves towards
 * the reader, not away.
 *
 * A prepend is deliberately not covered and does not need to be: back-paginated
 * history grows `content` and `scrollTop` by the same amount (virtua's `shift`,
 * see `timelineGrouping.ts`'s `shouldShift`), so the distance to the tail never
 * changes and the reader stays exactly where they were reading.
 *
 * `followBottom` outranks all of it. Somebody who scrolled up to read
 * something is reading something.
 *
 * Deliberately not thresholded: `followBottom` already answers "was this reader
 * at the tail", and re-pinning someone who is at the tail costs nothing
 * whatever the size of the change.
 *
 * ## What this measured
 *
 * Both halves were watched failing in the running app on 2026-08-17, in one
 * room, against a live agent.
 *
 * **The viewport half.** An agent starts writing, `LiveTurn` opens to its 33vh
 * cap, and the timeline's viewport goes 911px -> 565px with `scrollTop`
 * unmoved. The reader loses the bottom 393px of the conversation, including the
 * message they had just sent. Worse, `handleScroll` derives `followBottom` from
 * `scrollSize - viewportSize - offset < 120` against that shrunken viewport —
 * 393 fails it — so the *next* scroll event of any size concludes the reader
 * has wandered off. One pixel of trackpad twitch mid-stream was enough: the
 * finished reply landed at `fromBottom: 1558`, where the identical run without
 * the twitch ended at 0.
 *
 * **The content half.** With only the viewport half fixed, a 1721px reply
 * arrived and `content` went 6043 -> 7747 while `scrollTop` stayed at 5132.
 * The reader was left looking at their own sent message with the whole answer
 * below the fold. The one-shot `scrollToIndex` that was supposed to handle this
 * fires a `tick()` after the item lands — which is before virtua has measured
 * it, so it aims at an estimate and lands short, and nothing re-aims once the
 * real height is known. Re-pinning on the size change instead is immune to that
 * ordering, because it fires again on every correction.
 *
 * Pure over the numbers so the rule is testable; the resize that feeds it, and
 * the scroll it drives, are not — which is why the measurements above are
 * quoted rather than asserted.
 */
export function shouldRepin(
  previous: PaneMetrics,
  next: PaneMetrics,
  followBottom: boolean,
): boolean {
  // The first observation, before anything has been measured: every real pane
  // looks like content growth against zero, which would scroll a reader who
  // opened a room part-way up. Saying so explicitly beats relying on it.
  if (previous.viewport === 0 && previous.content === 0) return false;
  if (!followBottom) return false;
  return next.viewport < previous.viewport || next.content > previous.content;
}

/**
 * Whether this is the moment to land the list on its newest row.
 *
 * A room opens at the newest message, and the pin that achieves it runs on
 * mount — before the rows exist. The scroller is empty then, so setting
 * `scrollTop` does nothing, and the real content arrives afterwards as diffs.
 *
 * {@link shouldRepin} deliberately ignores that first observation: measured
 * against a pane that was `{0, 0}`, *any* arrival looks like growth, and
 * treating it as growth would yank a reader who deliberately opened part-way
 * up. That guard is right for growth and wrong for arrival — and when a room's
 * whole history lands in a single diff batch (the ordinary case: one `reset`
 * then one `insert` of everything), the single growth it swallows is the only
 * one there was. The list stays at offset 0, showing the oldest message
 * loaded, which is the opposite of where a reader wants to be.
 *
 * So arrival is named separately from growth. It fires once, for the
 * transition from an empty scroller to one with content, and never again —
 * after that the list is settled and {@link shouldRepin} owns the tail.
 */
export function shouldSettleAtBottom(
  previous: PaneMetrics,
  next: PaneMetrics,
  settled: boolean,
): boolean {
  if (settled) return false;
  // "Empty" is about content, not the viewport: a pane can have its height
  // before it has a single row, which is exactly the state mount leaves behind.
  return previous.content === 0 && next.content > 0;
}

/**
 * How long after the reader's own input (wheel, touch, key, pointer on the
 * pane) a scroll event is still theirs. Long enough to cover a trackpad
 * fling's trailing events; short enough that virtua's own corrections, which
 * arrive with no input at all, never land inside it.
 */
export const READER_INPUT_WINDOW_MS = 400;

/**
 * Whether a scroll event was the reader's.
 *
 * Most scroll events in this pane are not: virtua corrects its offset as it
 * measures rows, `shift` holds position across a prepend, and the re-pin below
 * assigns `scrollTop` itself. Treating those as the reader moving is what
 * opened rooms part-way up their history (see the tests for the measurements).
 */
export function isReaderScroll(lastReaderInputAt: number | null, now: number): boolean {
  return lastReaderInputAt !== null && now - lastReaderInputAt <= READER_INPUT_WINDOW_MS;
}

/** How close to the bottom (px) still counts as at the tail. */
export const TAIL_THRESHOLD = 120;

/**
 * Whether the pane follows the tail after a scroll event.
 *
 * Only the reader can stop following: a layout-driven scroll, however far from
 * the tail it lands, leaves `followBottom` set so the next resize re-pins. Any
 * scroll that reaches the tail resumes following, whoever caused it.
 */
export function nextFollowBottom(
  current: boolean,
  distanceFromBottom: number,
  readerDriven: boolean,
): boolean {
  const atTail = distanceFromBottom < TAIL_THRESHOLD;
  if (atTail) return true;
  return readerDriven ? false : current;
}

/** How close to the top (px) asks for older history. */
export const HEAD_THRESHOLD = 200;

export interface OlderHistoryQuery {
  offset: number;
  readerDriven: boolean;
  /** Whether the content is taller than the viewport. */
  scrollable: boolean;
  paginating: boolean;
  reachedStart: boolean;
}

/**
 * Whether to fetch older history now.
 *
 * Near the top only when the reader went there — a correction that happens to
 * pass the top must not start a chain of fetches. And whenever the history
 * does not yet fill the pane, since a pane that cannot scroll will never
 * produce the scroll that would ask.
 */
export function shouldRequestOlder(query: OlderHistoryQuery): boolean {
  if (query.paginating || query.reachedStart) return false;
  if (!query.scrollable) return true;
  return query.readerDriven && query.offset < HEAD_THRESHOLD;
}

/**
 * Whether virtua should hold position against the end for this update.
 *
 * `headChanged` is `shouldShift`'s answer (`timelineGrouping.ts`). A list that
 * does not scroll has no position to hold, and virtua's shift freezes its
 * rendered range until the next scroll event — which such a list never sends.
 */
export function shouldShiftNow(headChanged: boolean, scrollable: boolean): boolean {
  return headChanged && scrollable;
}

/**
 * Whether a scroll event should be answered with a re-pin.
 *
 * `shouldRepin` covers the pane changing size. This covers the offset moving
 * while the size does not grow: virtua compensating for rows it measured
 * shorter than estimated can move a following reader anywhere, content shrinks
 * at the same time, and no resize rule fires. If the reader is following and
 * did not make this scroll, the tail is where they belong.
 */
export function shouldRepinAfterScroll(
  followBottom: boolean,
  distanceFromBottom: number,
  readerDriven: boolean,
): boolean {
  return followBottom && !readerDriven && distanceFromBottom >= TAIL_THRESHOLD;
}
