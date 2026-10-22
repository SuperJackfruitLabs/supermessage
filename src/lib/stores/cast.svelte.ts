// Who is in the focused room, from the core (`room_cast`): the typing line,
// the live card's name and, later, the composer's mentions read it.

import { roomCast as defaultRoomCast, type RoomCast } from "$lib/ipc";

export interface CastStoreDeps {
  roomCast: typeof defaultRoomCast;
}

export function createCastStore(deps: CastStoreDeps = { roomCast: defaultRoomCast }) {
  let cast = $state<RoomCast | null>(null);
  // Bumped by every load and by clear(): a load that finds it moved is stale.
  let generation = 0;

  return {
    get cast(): RoomCast | null {
      return cast;
    },
    /** The cast, only if it is this room's. */
    castFor(roomId: string | null): RoomCast | null {
      return roomId !== null && cast?.roomId === roomId ? cast : null;
    },
    async load(roomId: string): Promise<void> {
      const mine = ++generation;
      cast = null;
      try {
        const loaded = await deps.roomCast(roomId);
        if (mine === generation) cast = loaded;
      } catch (err) {
        if (mine === generation) cast = null;
        console.error("castStore: could not load the room's cast", err);
      }
    },
    clear(): void {
      generation++;
      cast = null;
    },
  };
}

export const castStore = createCastStore();
