// Opening a room from a notification (`NotificationRouter.swift:4-31`): the room is selected and
// nothing else moves (owner decision O2, as iOS). The rail keeps its space: a space switch never
// closes the open room, and the pane keeps showing one the roster has filtered out
// (`spaces.svelte.ts`), so a room outside the selected space opens without a row.

export interface OpenRoomDeps {
  selectedRoomId: string | null;
  selectRoom: (roomId: string) => void;
}

export async function openRoomFromNotification(roomId: string, deps: OpenRoomDeps): Promise<void> {
  if (roomId !== deps.selectedRoomId) deps.selectRoom(roomId);
}
