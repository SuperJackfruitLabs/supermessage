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
  /** Edit follows `editable`, as iOS does (TimelineCollectionView.swift:1110-1117), never `isOwn`. */
  edit: boolean;
  del: boolean;
  /** Anything for the "More" menu: Copy, Edit or Delete. */
  more: boolean;
  bar: boolean;
} {
  const { body, isOwn, eventId, editable } = row.item;
  const react = row.canReplyOrReact;
  const copy = body !== null && body !== "";
  const edit = editable && eventId !== null;
  const del = isOwn && react && eventId !== null;
  const more = copy || edit || del;
  return {
    react,
    copy,
    edit,
    del,
    more,
    // A pending message cannot be replied to or reacted to, but its text can
    // still be copied (iOS TimelineCollectionView offers Copy on any body).
    bar: react || more,
  };
}

/**
 * Whether a menu of `menuHeight` anchored at `anchor` should open upward: it
 * does not fit below, and it does fit above. When neither fits it opens down,
 * where the scroller can still be scrolled to reveal it.
 */
export function menuOpensUp(
  anchor: { top: number; bottom: number },
  viewport: { top: number; bottom: number },
  menuHeight: number,
): boolean {
  return anchor.bottom + menuHeight > viewport.bottom && anchor.top - menuHeight >= viewport.top;
}
