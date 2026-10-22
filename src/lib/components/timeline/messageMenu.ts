import type { TimelineDisplayRow } from "../timelineGrouping";

type ItemRow = Extract<TimelineDisplayRow, { type: "item" }>;

/**
 * Which of the message menu's text actions apply to `row`, as on iOS.
 *
 * Copy needs something to copy, and does not need the message to have been
 * sent. Reply and reactions (`react`) do. Delete needs a message of this account that
 * can be addressed: `canReplyOrReact` is false while a send is pending, and
 * the `eventId` check is the same fact stated again, because a redaction
 * addresses an event and a local echo has none yet.
 */
export function menuActions(row: ItemRow): {
  react: boolean;
  copy: boolean;
  del: boolean;
  bar: boolean;
} {
  const { body, isOwn, eventId } = row.item;
  const react = row.canReplyOrReact;
  const copy = body !== null && body !== "";
  return {
    react,
    copy,
    del: isOwn && react && eventId !== null,
    // A pending message cannot be replied to or reacted to, but its text can
    // still be copied (iOS TimelineCollectionView offers Copy on any body).
    bar: react || copy,
  };
}
