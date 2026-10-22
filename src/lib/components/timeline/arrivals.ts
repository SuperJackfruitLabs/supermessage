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
 * drain the set), or more than {@link ARRIVAL_LIMIT} new messages.
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
  const fresh = next.filter((key) => !seen.has(key) && isMessage(key));
  return fresh.length > ARRIVAL_LIMIT ? new Set() : new Set(fresh);
}
