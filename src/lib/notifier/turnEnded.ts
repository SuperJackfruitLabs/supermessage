// The desktop's stand-in for the iOS Live Activity (`FleetActivityController.swift:6-29`): one
// notification when an agent's turn ends in a failed step, news no message notification carries.
// The words are the core's (`turn_card`, from `FleetCard.swift:183-230`); this file decides only
// when to say them. A running turn is not posted: the tray's working line shows it, and XDG has no
// reliable in-place progress update across servers.
//
// Not duplicating the message notification: the core ends a turn as `done` when the agent's
// stream closes (`turns.rs`, `TurnInput::Answer { done: true }`), which is how an ordinary
// answered turn ends, and the answer then lands as a message that notifies as one. `reply` is
// only ever seen for the focused timeline. So neither ending posts on its own. A "finished with
// no reply" note needs the core to tell a check-mark-only finish apart (a CP6 follow-up). A failed
// step posts whatever ended the turn: the reply and the failure are two different news items with
// two ids. A timed-out turn has no card.

import {
  notificationDelivers as defaultDelivers,
  notificationPost as defaultPost,
  roomCast as defaultRoomCast,
  roomInfo as defaultRoomInfo,
  turnCard as defaultTurnCard,
  type NotificationMode,
  type PlannedNotification,
  type TurnUpdate,
} from "$lib/ipc";
import { roomsStore } from "$lib/stores/rooms.svelte";
import { turnAgentName } from "$lib/stores/liveTurn";

export interface TurnEndedDeps {
  turnCard: typeof defaultTurnCard;
  notificationPost: typeof defaultPost;
  notificationDelivers: typeof defaultDelivers;
  roomInfo: typeof defaultRoomInfo;
  roomCast: typeof defaultRoomCast;
  /** The room's name, the fallback for an agent with no name of its own. */
  roomName: (roomId: string) => string | null;
}

export function createTurnEndedNotifier(deps: TurnEndedDeps) {
  // The turn each room last showed, and whether it was running.
  const last = new Map<string, { turnId: number; inProgress: boolean }>();
  const posted = new Set<string>();
  let focused = false;
  let selectedId: string | null = null;
  // Bumped by reset(): an answer from before it is for an account that is gone.
  let generation = 0;

  async function announce(update: TurnUpdate, mine: number): Promise<void> {
    const s = update.summary;
    let name: string | null = null;
    try {
      name = turnAgentName(s, await deps.roomCast(s.roomId));
    } catch {
      // No cast: the room's name stands in.
    }
    if (mine !== generation) return;
    // "Agent": the last fallback, with no cast name and no room name *(no iOS copy)*.
    const card = await deps.turnCard(s, name ?? deps.roomName(s.roomId) ?? "Agent");
    if (card === null || mine !== generation) return;
    const note: PlannedNotification = {
      id: `turn:${s.roomId}:${s.turnId}`,
      roomId: s.roomId,
      eventId: null,
      title: card.headline,
      subtitle: null,
      body: card.subtitle,
      category: "message",
      permission: null,
      gate: null,
    };
    let mode: NotificationMode | null = null;
    try {
      mode = (await deps.roomInfo(s.roomId)).notifications;
    } catch {
      // Unknown: delivered, as the message notifier does.
    }
    if (mine !== generation || !(await deps.notificationDelivers(note, mode)) || mine !== generation) return;
    await deps.notificationPost(note);
  }

  return {
    /** One `sm://turn` update, for any room. */
    turnChanged(update: TurnUpdate): void {
      const s = update.summary;
      const before = last.get(s.roomId);
      last.set(s.roomId, { turnId: s.turnId, inProgress: s.inProgress });
      if (s.inProgress || !before || before.turnId !== s.turnId || !before.inProgress) return;
      // Only a failure is news the answer's own message does not carry (see the header).
      if (s.failedStep === null) return;
      const id = `turn:${s.roomId}:${s.turnId}`;
      if (posted.has(id)) return;
      posted.add(id);
      // The room on screen in a focused window: the turn is being watched.
      if (focused && selectedId === s.roomId) return;
      void announce(update, generation).catch(() => {});
    },
    focusChanged(next: boolean): void {
      focused = next;
    },
    selectedChanged(roomId: string | null): void {
      selectedId = roomId;
    },
    /** Sign-out: forget every room's state and drop what is being worded. */
    reset(): void {
      generation++;
      last.clear();
      posted.clear();
      selectedId = null;
      focused = false;
    },
  };
}

export const turnEnded = createTurnEndedNotifier({
  turnCard: defaultTurnCard,
  notificationPost: defaultPost,
  notificationDelivers: defaultDelivers,
  roomInfo: defaultRoomInfo,
  roomCast: defaultRoomCast,
  roomName: (roomId) => roomsStore.rooms.find((r) => r.room.id === roomId)?.room.name ?? null,
});
