// Opening a room from a notification (`NotificationRouter.swift:4-31`): the room is selected and
// nothing else moves. The roster follows the selected space (owner Q1), so a room outside it
// would open with no row to show for it; the rail then goes back to All rooms, where it is.

import type { RoomRow } from "$lib/ipc";

export interface OpenRoomDeps {
  rooms: RoomRow[];
  selectedRoomId: string | null;
  spaceSelected: boolean;
  selectSpace: (spaceId: string | null) => Promise<void>;
  selectRoom: (roomId: string) => void;
}

export async function openRoomFromNotification(roomId: string, deps: OpenRoomDeps): Promise<void> {
  if (deps.spaceSelected && !deps.rooms.some((row) => row.room.id === roomId)) {
    await deps.selectSpace(null);
  }
  if (roomId !== deps.selectedRoomId) deps.selectRoom(roomId);
}
