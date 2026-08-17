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
