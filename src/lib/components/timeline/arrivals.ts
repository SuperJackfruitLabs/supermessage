/**
 * Which rows rise in when the list changes (iOS `TimelineCollectionView`
 * `:855-884`): only messages, only while the reader is at the newest end,
 * never on the first fill, and never for a burst.
 */

/** More new messages than this at once is history landing, not conversation. */
export const ARRIVAL_LIMIT = 3;

const MESSAGE_RENDERS = new Set(["bubble", "image", "mediaFile", "audio", "customEvent"]);

/** Whether an `ItemView.render` is a message (as opposed to a divider or log line). */
export function isArrivingRender(render: string): boolean {
  return MESSAGE_RENDERS.has(render);
}

/**
 * The item ids in `next` that were not in `previous`, or an empty set when
 * nothing should animate: first fill, reader away from the newest end
 * (pagination, resync), reduced motion (nothing would end the animation to
 * drain the set), or more than {@link ARRIVAL_LIMIT} new items (of any kind) at the tail.
 * Ids prepended above the previous newest item never animate.
 *
 * Takes **item ids** (`TimelineItem.id`, the SDK's unique id), never row
 * keys: a row key carries the reaction count, so a reaction landing on an
 * existing message changes its key and would read as an arrival. The unique
 * id also survives a send's server echo (the core collapses the
 * remove-then-readd), so a sent message rises once, not twice.
 */
export function arrivedKeys(
  previous: readonly string[],
  next: readonly string[],
  following: boolean,
  isMessage: (key: string) => boolean,
  reducedMotion = false,
): Set<string> {
  if (previous.length === 0 || !following || reducedMotion) return new Set();
  const seen = new Set(previous);
  // Only items after the previous newest one are news; ids above it were
  // prepended history (pagination) and never animate. If the previous newest
  // item is gone, fall back to the latest previous id still present.
  let tail = -1;
  for (let i = next.length - 1; i >= 0 && tail < 0; i--) if (seen.has(next[i]!)) tail = i;
  if (tail < 0) return new Set();
  const fresh = next.slice(tail + 1).filter((key) => !seen.has(key));
  // Like iOS `animates(arrived:had:)`: the limit counts every new item
  // (dividers, log lines) before narrowing to messages, so a 20-item page
  // with 2 messages is history, not conversation.
  if (fresh.length > ARRIVAL_LIMIT) return new Set();
  return new Set(fresh.filter(isMessage));
}
