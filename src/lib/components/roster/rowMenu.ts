// What the roster's row menu offers (iOS `RoomListView.swift:244-301`, as a menu).

import type { NotificationMode, RoomAffordance } from "$lib/ipc";

export type RowMenuItem = { id: "mute" | "pin" | "decline"; label: string; destructive: boolean };

/**
 * An invitation offers Decline; a joined room offers Mute/Unmute and Pin/Unpin, worded from what
 * the core said the room is set to (`null`, when it could not say, reads "Mute" and "Pin");
 * anything else offers nothing.
 */
export function rowMenuItems(
  affordance: RoomAffordance,
  info: { notifications: NotificationMode; pinned: boolean } | null,
): RowMenuItem[] {
  switch (affordance) {
    case "respondToInvitation":
      return [{ id: "decline", label: "Decline", destructive: true }];
    case "compose":
      return [
        { id: "mute", label: info?.notifications === "muted" ? "Unmute" : "Mute", destructive: false },
        { id: "pin", label: info?.pinned ? "Unpin" : "Pin", destructive: false },
      ];
    case "nothing":
      return [];
  }
}

/** Opens at the point, flipped left or up when it would leave the window, never negative. */
export function menuPosition(
  point: { x: number; y: number },
  size: { width: number; height: number },
  viewport: { width: number; height: number },
): { left: number; top: number } {
  const left = point.x + size.width > viewport.width ? point.x - size.width : point.x;
  const top = point.y + size.height > viewport.height ? point.y - size.height : point.y;
  return { left: Math.max(0, left), top: Math.max(0, top) };
}
