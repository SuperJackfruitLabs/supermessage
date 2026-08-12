// The room list, kept in sync with the core's `sm://rooms/diff` channel via
// `gapSync`'s gap/resync ordering. Also owns which room is selected —
// selecting a room is what drives the timeline store's subscription, since
// in this UI the two are never meaningfully independent.

import { onRoomsDiff as defaultOnRoomsDiff, roomsResync as defaultRoomsResync, type RoomSummary } from "$lib/ipc";
import { startGapSync } from "./gapSync";
import { timelineStore } from "./timeline.svelte";

export interface RoomsStoreDeps {
  roomsResync: typeof defaultRoomsResync;
  onRoomsDiff: typeof defaultOnRoomsDiff;
}

const defaultDeps: RoomsStoreDeps = {
  roomsResync: defaultRoomsResync,
  onRoomsDiff: defaultOnRoomsDiff,
};

export function createRoomsStore(deps: RoomsStoreDeps = defaultDeps) {
  let rooms = $state<RoomSummary[]>([]);
  let selectedId = $state<string | null>(null);

  startGapSync<RoomSummary>({
    subscribe: (onEnvelope) => deps.onRoomsDiff(onEnvelope),
    resync: () => deps.roomsResync(),
    onUpdate: (next) => {
      rooms = next;
    },
  });

  /**
   * Selects a room and subscribes its timeline. Fire-and-forget: a failed
   * subscribe (e.g. the room vanished) is logged rather than thrown, since
   * there's no caller left holding a promise from a UI click handler to
   * catch it.
   */
  function select(id: string): void {
    selectedId = id;
    timelineStore.subscribeTo(id).catch((err: unknown) => {
      console.error("failed to subscribe to timeline for room", id, err);
    });
  }

  return {
    get rooms(): RoomSummary[] {
      return rooms;
    },
    get selectedId(): string | null {
      return selectedId;
    },
    select,
  };
}

export const roomsStore = createRoomsStore();
