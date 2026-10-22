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
 * The message keys in `next` that were not in `previous`, or an empty set
 * when nothing should animate: first fill, reader away from the newest end
 * (pagination, resync), or more than {@link ARRIVAL_LIMIT} new messages.
 */
export function arrivedKeys(
  previous: readonly string[],
  next: readonly string[],
  following: boolean,
  isMessage: (key: string) => boolean,
): Set<string> {
  if (previous.length === 0 || !following) return new Set();
  const seen = new Set(previous);
  const fresh = next.filter((key) => !seen.has(key) && isMessage(key));
  return fresh.length > ARRIVAL_LIMIT ? new Set() : new Set(fresh);
}
