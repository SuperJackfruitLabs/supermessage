// A room's notification mode and pin, as iOS `RoomInfoPanel.swift:121-138, 359-397` and
// `RoomListView.swift:244-301` offer them.

import {
  nextNotificationMode,
  roomInfo,
  setRoomNotificationMode,
  setRoomPinned,
  type NotificationMode,
} from "$lib/ipc";

/** "Notify me about", in iOS's order. */
export const NOTIFY_OPTIONS: readonly { mode: NotificationMode; label: string }[] = [
  { mode: "allMessages", label: "Everything" },
  { mode: "mentionsOnly", label: "Mentions only" },
  { mode: "default", label: "Account default" },
  { mode: "muted", label: "Nothing" },
];

/** The "Mute" switch: on is muted, off restores the account default (iOS `:359-373`). */
export function muteTarget(on: boolean): NotificationMode {
  return on ? "muted" : "default";
}

export interface ToggleDeps {
  roomInfo: typeof roomInfo;
  nextNotificationMode: typeof nextNotificationMode;
  setRoomNotificationMode: typeof setRoomNotificationMode;
  setRoomPinned: typeof setRoomPinned;
}

const defaultDeps: ToggleDeps = { roomInfo, nextNotificationMode, setRoomNotificationMode, setRoomPinned };

/**
 * Flips a room's mute. Asks the core what the room is set to first and writes only the answer's
 * successor (the core's `next_notification_mode`: muted goes to default, anything else to muted).
 * `null` when the question or the write failed; nothing was changed then, or nothing is known.
 */
export async function toggleMute(roomId: string, deps: ToggleDeps = defaultDeps): Promise<{ muted: boolean } | null> {
  try {
    const info = await deps.roomInfo(roomId);
    const next = await deps.nextNotificationMode(info.notifications);
    await deps.setRoomNotificationMode(roomId, next);
    return { muted: next === "muted" };
  } catch {
    return null;
  }
}

/** Flips a room's pin from what the core says it is. `null` when the question or the write failed. */
export async function togglePin(roomId: string, deps: ToggleDeps = defaultDeps): Promise<{ pinned: boolean } | null> {
  try {
    const info = await deps.roomInfo(roomId);
    const pinned = !info.pinned;
    await deps.setRoomPinned(roomId, pinned);
    return { pinned };
  } catch {
    return null;
  }
}

/** What is said when a mute or pin write is refused, under the control that asked for it. */
export const SETTING_FAILURE = "Couldn't change that. Try again.";

/**
 * Runs `work` unless one is already running for `key`, and releases the key however it ends. A
 * switch that can be flipped twice sends two writes whose answers race.
 */
export async function exclusively<T>(
  busy: { has(key: string): boolean; add(key: string): unknown; delete(key: string): unknown },
  key: string,
  work: () => Promise<T>,
): Promise<{ ran: true; value: T } | { ran: false }> {
  if (busy.has(key)) return { ran: false };
  busy.add(key);
  try {
    return { ran: true, value: await work() };
  } finally {
    busy.delete(key);
  }
}
