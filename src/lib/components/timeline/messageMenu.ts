import type { TimelineDisplayRow } from "../timelineGrouping";

type ItemRow = Extract<TimelineDisplayRow, { type: "item" }>;

/**
 * Which of the message menu's text actions apply to `row`, as on iOS.
 *
 * Copy needs something to copy. Delete needs a message of this account that
 * can be addressed: `canReplyOrReact` is false while a send is pending, and
 * the `eventId` check is the same fact stated again, because a redaction
 * addresses an event and a local echo has none yet.
 */
export function menuActions(row: ItemRow): { copy: boolean; del: boolean } {
  const { body, isOwn, eventId } = row.item;
  return {
    copy: body !== null && body !== "",
    del: isOwn && row.canReplyOrReact && eventId !== null,
  };
}
