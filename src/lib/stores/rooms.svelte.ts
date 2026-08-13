// The room list, kept in sync with the core's `sm://rooms/diff` channel via
// `gapSync`'s gap/resync ordering. Also owns which room is selected —
// selecting a room is what drives the timeline store's subscription, since
// in this UI the two are never meaningfully independent.
//
// Also owns starting/ending a session (`login`/`restoreSession`/`logout`).
// That's not primarily an organizational choice — it's the fix for a real
// hazard: the core restarts its room-list sequence counter from scratch
// every time `start_streams` runs (`SeqCounter::default()` inside
// `spawn_room_list`, `src-tauri/src/core/rooms.rs`), which happens on every
// `login` and every `restore_session`. If this store's `DiffTracker` isn't
// re-armed at exactly that moment, the next session's `seq: 1` envelope
// looks like an already-applied duplicate of the *previous* session's
// history (`DiffTracker.apply` only detects gaps forward, never backward)
// and is silently dropped — no resync is ever triggered, and once the new
// session's `seq` climbs back past the stale expected value, its ops get
// folded onto the old session's leftover items. Silent corruption, not
// staleness.
//
// So `login`/`restoreSession` are exposed here, backed by
// `ipc.ts::makeSessionCommands`, which structurally cannot hand back a
// working `login`/`restoreSession` function without also being given the
// re-arm callback — see that function's doc comment for why a bare export
// with a warning comment wasn't enough.

import {
  logout as defaultLogout,
  makeSessionCommands as defaultMakeSessionCommands,
  onRoomsDiff as defaultOnRoomsDiff,
  roomsResync as defaultRoomsResync,
  type RoomSummary,
} from "$lib/ipc";
import { startGapSync } from "./gapSync";
import { timelineStore } from "./timeline.svelte";

export interface RoomsStoreDeps {
  roomsResync: typeof defaultRoomsResync;
  onRoomsDiff: typeof defaultOnRoomsDiff;
  makeSessionCommands: typeof defaultMakeSessionCommands;
  logout: typeof defaultLogout;
}

const defaultDeps: RoomsStoreDeps = {
  roomsResync: defaultRoomsResync,
  onRoomsDiff: defaultOnRoomsDiff,
  makeSessionCommands: defaultMakeSessionCommands,
  logout: defaultLogout,
};

export function createRoomsStore(deps: RoomsStoreDeps = defaultDeps) {
  let rooms = $state<RoomSummary[]>([]);
  let selectedId = $state<string | null>(null);

  const gapSync = startGapSync<RoomSummary>({
    subscribe: (onEnvelope) => deps.onRoomsDiff(onEnvelope),
    resync: () => deps.roomsResync(),
    onUpdate: (next) => {
      rooms = next;
    },
  });

  // The only way to obtain `login`/`restoreSession` — supplying the arm
  // callback isn't optional, it's how you get the functions in the first
  // place.
  const { login, restoreSession } = deps.makeSessionCommands(() => gapSync.resetForNewSubscription());

  /**
   * Logs out and clears local room/selection state. Re-arms the tracker
   * too — logout stops the stream so nothing strictly requires it before
   * the next login (which re-arms unconditionally anyway), but leaving a
   * stale room list on screen after logging out would be its own bug.
   */
  async function logout(): Promise<void> {
    await deps.logout();
    gapSync.resetForNewSubscription();
    selectedId = null;
  }

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
    login,
    restoreSession,
    logout,
    /** Stops the room-list subscription for good (e.g. app teardown). Unused today; kept reachable rather than discarded. */
    unlisten: gapSync.unlisten,
  };
}

export const roomsStore = createRoomsStore();
