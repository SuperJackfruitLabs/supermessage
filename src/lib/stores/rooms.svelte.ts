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
//
// The mirror-image hazard is arming the tracker for a stream that isn't
// going to restart, which is just as corrupting and was the actual bug
// shipped on this branch: see `restoreSession` below.

import {
  joinRoom as defaultJoinRoom,
  leaveRoom as defaultLeaveRoom,
  logout as defaultLogout,
  makeSessionCommands as defaultMakeSessionCommands,
  onRoomsDiff as defaultOnRoomsDiff,
  roomsResync as defaultRoomsResync,
  type Membership,
  type RoomSummary,
} from "$lib/ipc";
import { startGapSync } from "./gapSync";
import { timelineStore } from "./timeline.svelte";
import { spacesStore } from "./spaces.svelte";

export interface RoomsStoreDeps {
  roomsResync: typeof defaultRoomsResync;
  onRoomsDiff: typeof defaultOnRoomsDiff;
  makeSessionCommands: typeof defaultMakeSessionCommands;
  logout: typeof defaultLogout;
  joinRoom: typeof defaultJoinRoom;
  leaveRoom: typeof defaultLeaveRoom;
  /**
   * Re-reads the joined spaces. Called after a join, because the invitation
   * that was just accepted may have been a space — and a space leaves the
   * roster the moment it is joined (see `core::rooms::roster_admits`), so
   * without this it would disappear from one surface without appearing in the
   * other until the next launch.
   */
  reloadSpaces: () => Promise<void>;
}

const defaultDeps: RoomsStoreDeps = {
  roomsResync: defaultRoomsResync,
  onRoomsDiff: defaultOnRoomsDiff,
  makeSessionCommands: defaultMakeSessionCommands,
  logout: defaultLogout,
  joinRoom: defaultJoinRoom,
  leaveRoom: defaultLeaveRoom,
  reloadSpaces: () => spacesStore.load(),
};

export function createRoomsStore(deps: RoomsStoreDeps = defaultDeps) {
  let rooms = $state<RoomSummary[]>([]);
  let selectedId = $state<string | null>(null);
  /**
   * The selected room's name as of the moment it was chosen — the fallback
   * for when the roster stops listing it.
   *
   * The roster is not the set of rooms this account is in; it is a *view* of
   * that set, and since the spaces rail landed it is a view the reader can
   * narrow. Selecting a space filters the room list, and the spaces-rail
   * design (§7) requires the room pane to keep showing whatever is open even
   * when it drops out of the roster — filtering a navigation surface must
   * not close what someone is reading.
   *
   * Without this, it half-did. The timeline stayed (nothing re-subscribes),
   * but the room header derived its name by looking the selected id up in
   * `rooms` — so the moment the filter excluded that room the header fell
   * back to the raw `!id:server`, and the avatar's initial with it. Caught by
   * rendering it, not by reading the code.
   *
   * Deliberately captured at select time and not maintained afterwards: the
   * live roster wins whenever it still lists the room (see
   * `selectedRoomName`), so this is only ever read for a room the roster has
   * stopped describing — at which point a rename we would have missed is not
   * something any other source could tell us either.
   */
  let selectedName = $state<string | null>(null);
  // Whether a session is already established in the core. Not `$state`:
  // nothing renders it, and `restoreSession` must read it synchronously
  // before its first await.
  let sessionActive = false;

  const gapSync = startGapSync<RoomSummary>({
    subscribe: (onEnvelope) => deps.onRoomsDiff(onEnvelope),
    // The room-list channel has a single subject (the core stamps every
    // envelope with `""`), so there is nothing to filter on and no
    // `accepts` predicate — unlike the timeline channel, whose subject is
    // the focused room. See `timeline.svelte.ts`.
    resync: async () => {
      const [seq, items] = await deps.roomsResync();
      return { subject: "", seq, items };
    },
    onUpdate: (next) => {
      rooms = next;
    },
  });

  // The only way to obtain `login`/`restoreSession` — supplying the arm
  // callback isn't optional, it's how you get the functions in the first
  // place.
  const commands = deps.makeSessionCommands(() => gapSync.resetForNewSubscription());

  /** Logs in, and records that a session is now established. */
  async function login(homeserver: string, username: string, password: string): Promise<void> {
    await commands.login(homeserver, username, password);
    sessionActive = true;
  }

  /**
   * Restores a persisted session, or reports that there was none.
   *
   * Skips the round trip entirely when a session is already established,
   * because arming the tracker is not free: it is a hard reset that tells
   * the store to expect the *next* stream to start at seq 1. `/login`
   * navigates to `/` on success and `/`'s mount restores, so without this
   * guard every password login re-armed the tracker against a room-list
   * stream that was already mid-flight at some much higher seq — the very
   * next envelope read as a gap, the resync that followed pushed the
   * expected sequence back up, and the room list then froze for the rest of
   * the session. `Session::restore_and_start` guards the same thing core-side
   * (the webview is not the only possible caller); this guard is what stops
   * the tracker being re-armed for a stream that never restarts.
   */
  async function restoreSession(): Promise<boolean> {
    if (sessionActive) return true;
    const restored = await commands.restoreSession();
    sessionActive = restored;
    return restored;
  }

  /**
   * Logs out and clears local room/selection state. Re-arms the tracker
   * too — logout stops the stream so nothing strictly requires it before
   * the next login (which re-arms unconditionally anyway), but leaving a
   * stale room list on screen after logging out would be its own bug.
   *
   * Local state clears in a `finally`, so a `logout` command that rejects
   * (the core wipes session, secrets and stores before it can fail on the
   * store directory) still leaves the UI logged out rather than showing a
   * room list backed by an account that is already gone. The error is
   * rethrown for the caller to report.
   */
  async function logout(): Promise<void> {
    try {
      await deps.logout();
    } finally {
      sessionActive = false;
      gapSync.resetForNewSubscription();
      selectedId = null;
      selectedName = null;
    }
  }

  /**
   * Selects a room and subscribes its timeline. Fire-and-forget: a failed
   * subscribe (e.g. the room vanished) is logged rather than thrown, since
   * there's no caller left holding a promise from a UI click handler to
   * catch it.
   */
  function select(id: string): void {
    selectedId = id;
    selectedName = rooms.find((room) => room.id === id)?.name ?? null;
    timelineStore.subscribeTo(id).catch((err: unknown) => {
      console.error("failed to subscribe to timeline for room", id, err);
    });
  }

  /**
   * Accepts the invitation to `id`.
   *
   * Nothing is updated here on success: joining changes the room's state on
   * the homeserver, the core's room-list stream emits the resulting diff, and
   * the roster folds it in like any other change. Writing an optimistic
   * `membership` here as well would put the composer on screen before the
   * join landed — and leave it there if it never did.
   *
   * Rejects rather than swallowing a failure, so the caller can keep the
   * invitation on screen and say what happened.
   */
  async function acceptInvitation(id: string): Promise<void> {
    await deps.joinRoom(id);
    // Only after the join resolved: a refresh on a refused join would be a
    // wasted round trip, and the failure is the caller's to show.
    await deps.reloadSpaces().catch((err: unknown) => {
      // A stale rail is a cosmetic problem and the join already happened;
      // failing the whole action here would say the opposite.
      console.error("failed to refresh spaces after accepting an invitation", err);
    });
  }

  /**
   * Declines the invitation to `id` (or leaves the room, which is the same
   * call).
   *
   * Refreshes the rail afterwards for the same reason accepting does, and it
   * matters more here: an invited *space* is a rail entry and never a roster
   * row. The roster is diffed, so a declined room leaves it unaided; the rail
   * is a one-shot fetch, so a declined space would go on being offered until
   * the next launch. Cosmetic if it fails, exactly as on the accept path —
   * the leave already happened.
   */
  async function declineInvitation(id: string): Promise<void> {
    await deps.leaveRoom(id);
    await deps.reloadSpaces().catch((err: unknown) => {
      console.error("failed to refresh spaces after declining an invitation", err);
    });
  }

  return {
    get rooms(): RoomSummary[] {
      return rooms;
    },
    get selectedId(): string | null {
      return selectedId;
    },
    /**
     * The selected room's name, `null` when nothing is selected.
     *
     * The live roster entry whenever there is one, so a rename lands
     * immediately; the name remembered at select time when the roster no
     * longer lists the room — which today means a space filter excludes it.
     * See `selectedName` for why the room pane must not degrade to a raw
     * room id in that case.
     */
    get selectedRoomName(): string | null {
      if (selectedId === null) return null;
      return rooms.find((room) => room.id === selectedId)?.name ?? selectedName;
    },
    /**
     * The selected room's membership, `null` when nothing is selected or the
     * roster no longer lists it.
     *
     * Unlike `selectedRoomName` there is no remembered fallback: a room the
     * roster has stopped listing is one whose state nothing can vouch for,
     * and guessing `joined` would put a composer in front of it.
     */
    get selectedMembership(): Membership | null {
      if (selectedId === null) return null;
      return rooms.find((room) => room.id === selectedId)?.membership ?? null;
    },
    select,
    acceptInvitation,
    declineInvitation,
    login,
    restoreSession,
    logout,
    /** Stops the room-list subscription for good (e.g. app teardown). Unused today; kept reachable rather than discarded. */
    unlisten: gapSync.unlisten,
  };
}

export const roomsStore = createRoomsStore();
