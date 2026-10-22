// The mirror of one room's turn record, as pure functions.
//
// The turn's facts (status, step titles, counts, the record label) are the
// core's: `core::turns` folds the to-device events and sends `TurnUpdate`s.
// This only holds the latest of them for the room being looked at and refuses
// what arrives out of order. Revisions come from one book-wide counter, so a
// lower revision is always an older view.

import type { RoomCast, TurnSnapshot, TurnStep, TurnSummary, TurnUpdate } from "$lib/ipc";

export interface LiveTurnState {
  roomId: string | null;
  summary: TurnSummary | null;
  steps: TurnStep[];
  thought: string | null;
  answer: string | null;
}

export function emptyTurn(roomId: string | null): LiveTurnState {
  return { roomId, summary: null, steps: [], thought: null, answer: null };
}

/** Another room's update, or one older than what is held, changes nothing. */
export function applyTurnUpdate(s: LiveTurnState, u: TurnUpdate): LiveTurnState {
  if (s.roomId === null || u.summary.roomId !== s.roomId) return s;
  if (s.summary !== null && u.summary.revision < s.summary.revision) return s;
  // A new turn starts clean: the last one's steps, reasoning and answer are not its.
  const base =
    s.summary !== null && s.summary.turnId !== u.summary.turnId ? emptyTurn(s.roomId) : s;
  let steps = base.steps;
  if (u.step !== null) {
    // The core gives the index: an existing one is replaced, index == length appends.
    const { index, step } = u.step;
    if (index <= steps.length) {
      steps = [...steps.slice(0, index), step, ...steps.slice(index + 1)];
    }
  }
  return { ...base, summary: u.summary, steps };
}

/**
 * Brings back what the core holds; a snapshot older than what is held is dropped.
 * Which room it is for is the caller's to guard (`liveStore.focus`, by generation).
 */
export function restoreTurn(s: LiveTurnState, snap: TurnSnapshot | null): LiveTurnState {
  if (snap === null) return s;
  if (s.summary !== null && snap.summary.revision < s.summary.revision) return s;
  const sameTurn = s.summary !== null && s.summary.turnId === snap.summary.turnId;
  return {
    roomId: s.roomId,
    summary: snap.summary,
    steps: snap.steps,
    thought: snap.thought ?? (sameTurn ? s.thought : null),
    // The answer text is not part of the record; it only streams on the live channel.
    answer: sameTurn ? s.answer : null,
  };
}

/**
 * Who the live card names. The summary's agent when it is one of the room's
 * agents (a to-device sender nobody vouches for is not), else the room's agent.
 */
export function turnAgentName(summary: TurnSummary | null, cast: RoomCast | null): string | null {
  if (cast === null) return null;
  const id = summary?.agentId;
  if (id !== undefined && cast.agentIds.includes(id)) {
    const person = cast.people.find((p) => p.userId === id);
    if (person) return person.name;
  }
  return cast.counterpart?.name ?? null;
}
