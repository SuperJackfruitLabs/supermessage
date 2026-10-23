// Local notifications for a running desktop app. The core decides everything about them
// (`notification_policy`: what is news, its words, its category, whether a muted room holds it
// back); this file only keeps the lifecycle iOS's `PlatformCoordinator.swift:214-269` keeps:
// when to ask, what the context is, and what has been shown.

import {
  notificationDelivers as defaultDelivers,
  notificationPost as defaultPost,
  notificationsForFocused as defaultForFocused,
  notificationsForRoster as defaultForRoster,
  roomInfo as defaultRoomInfo,
  type NotificationContext,
  type NotificationMode,
  type PlannedNotification,
  type RoomRow,
} from "$lib/ipc";

/** After the window becomes active, roster changes are not news (`PlatformCoordinator.swift:95`, `:226`). */
export const CATCH_UP_MS = 5000;
/** A room's notification mode is remembered for a minute (`PlatformCoordinator.swift:264-265`). */
export const MODE_CACHE_MS = 60_000;
/**
 * The timeline is asked at most once per this long, trailing. A streaming turn re-emits the
 * timeline several times a second, and iOS reads in-process rows where this is an IPC call
 * *(desktop-only rule)*.
 */
export const TIMELINE_THROTTLE_MS = 250;

export interface NotifierDeps {
  notificationsForRoster: typeof defaultForRoster;
  notificationsForFocused: typeof defaultForFocused;
  notificationDelivers: typeof defaultDelivers;
  notificationPost: typeof defaultPost;
  roomInfo: typeof defaultRoomInfo;
  now: () => number;
}

const DEFAULT_DEPS: NotifierDeps = {
  notificationsForRoster: defaultForRoster,
  notificationsForFocused: defaultForFocused,
  notificationDelivers: defaultDelivers,
  notificationPost: defaultPost,
  roomInfo: defaultRoomInfo,
  now: () => Date.now(),
};

export function createNotifier(deps: NotifierDeps = DEFAULT_DEPS) {
  let previous: RoomRow[] | null = null;
  const notified = new Set<string>();
  const modes = new Map<string, { mode: NotificationMode | null; at: number }>();
  let focused = false;
  let selectedId: string | null = null;
  let catchUpUntil = 0;
  // The room the timeline calls are about, and the time before which its rows are history
  // (`PlatformCoordinator.swift:235-239`).
  let timelineRoomId: string | null = null;
  let since = 0;
  let pending: { roomId: string; roomName: string } | null = null;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let busy = false;
  let dirty = false;
  // Bumped by reset(): an answer from before it is for an account that is gone.
  let generation = 0;

  /** `remotePush` is always false: the desktop has no push. */
  function context(): NotificationContext {
    return { openRoomId: selectedId, appActive: focused, timelineRoomId: selectedId, remotePush: false };
  }

  async function modeOf(roomId: string): Promise<NotificationMode | null> {
    const cached = modes.get(roomId);
    if (cached && deps.now() - cached.at < MODE_CACHE_MS) return cached.mode;
    let mode: NotificationMode | null;
    try {
      mode = (await deps.roomInfo(roomId)).notifications;
    } catch {
      mode = null;
    }
    modes.set(roomId, { mode, at: deps.now() });
    return mode;
  }

  async function deliver(notes: PlannedNotification[], mine: number): Promise<void> {
    for (const note of notes) {
      try {
        const mode = await modeOf(note.roomId);
        if (mine !== generation) return;
        if (await deps.notificationDelivers(note, mode)) {
          if (mine !== generation) return;
          await deps.notificationPost(note);
        }
      } catch {
        // One note that cannot be shown must not stop the rest.
      }
    }
  }

  async function runFocused(): Promise<void> {
    const ask = pending;
    pending = null;
    if (!ask || ask.roomId !== selectedId) return;
    if (busy) {
      dirty = true;
      pending = ask;
      return;
    }
    busy = true;
    const mine = generation;
    try {
      const notes = await deps.notificationsForFocused(ask.roomId, ask.roomName, since, [...notified], context());
      if (mine !== generation) return;
      // Recorded now, delivered or not: "held back" is an answer too, and asking again on every
      // reaction would be the same question (`PlatformCoordinator.swift:245-248`).
      for (const note of notes) if (note.eventId) notified.add(note.eventId);
      await deliver(notes, mine);
    } catch {
      // `roomChanged` and `notReady` are the timeline not being the room yet; the next change asks again.
    } finally {
      busy = false;
      if (dirty && mine === generation) {
        dirty = false;
        schedule();
      }
    }
  }

  function schedule(): void {
    if (timer !== null) return;
    timer = setTimeout(() => {
      timer = null;
      void runFocused();
    }, TIMELINE_THROTTLE_MS);
  }

  function switchRoom(roomId: string | null): void {
    if (roomId === timelineRoomId) return;
    timelineRoomId = roomId;
    since = deps.now();
  }

  return {
    get notified(): ReadonlySet<string> {
      return notified;
    },
    /** The roster changed. The first one is a baseline, and so is any during catch-up. */
    roomsChanged(rows: RoomRow[]): void {
      const before = previous;
      previous = rows;
      if (before === null || deps.now() < catchUpUntil) return;
      const mine = generation;
      void deps
        .notificationsForRoster(before, rows, context())
        .then((notes) => (mine === generation ? deliver(notes, mine) : undefined))
        .catch(() => {});
    },
    /** The focused timeline emitted. Throttled; the core reads its own rows. */
    timelineChanged(roomId: string, roomName: string): void {
      switchRoom(roomId);
      if (deps.now() < catchUpUntil) since = Math.max(since, deps.now());
      pending = { roomId, roomName };
      schedule();
    },
    /** Window focus changed. Becoming active starts the catch-up. */
    focusChanged(next: boolean): void {
      focused = next;
      if (next) catchUpUntil = deps.now() + CATCH_UP_MS;
    },
    selectedChanged(roomId: string | null): void {
      selectedId = roomId;
      switchRoom(roomId);
      if (pending && pending.roomId !== roomId) pending = null;
    },
    /** Sign-out: forget the baseline, what was shown, and the modes. */
    reset(): void {
      generation++;
      previous = null;
      notified.clear();
      modes.clear();
      selectedId = null;
      timelineRoomId = null;
      since = 0;
      catchUpUntil = 0;
      pending = null;
      busy = false;
      dirty = false;
      if (timer !== null) clearTimeout(timer);
      timer = null;
    },
  };
}

export const notifier = createNotifier();
