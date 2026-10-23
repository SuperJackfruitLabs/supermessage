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

function accepts(s: LiveTurnState, u: TurnUpdate): boolean {
  if (s.roomId === null || u.summary.roomId !== s.roomId) return false;
  return !(s.summary !== null && u.summary.revision < s.summary.revision);
}

/** A new turn starts clean: the last one's steps, reasoning and answer are not its. */
function baseFor(s: LiveTurnState, u: TurnUpdate): LiveTurnState {
  return s.summary !== null && s.summary.turnId !== u.summary.turnId ? emptyTurn(s.roomId) : s;
}

/**
 * Whether the update names a step past the end of what is held: applying it
 * would leave a hole, so the caller must ask the core for the record instead.
 */
export function turnHasGap(s: LiveTurnState, u: TurnUpdate): boolean {
  return accepts(s, u) && u.step !== null && u.step.index > baseFor(s, u).steps.length;
}

/**
 * Another room's update, or one older than what is held, changes nothing. Nor
 * does one that would leave a gap in the steps: the revision stays where it was,
 * so the snapshot that repairs it is not mistaken for an older view.
 */
export function applyTurnUpdate(s: LiveTurnState, u: TurnUpdate): LiveTurnState {
  if (!accepts(s, u) || turnHasGap(s, u)) return s;
  const base = baseFor(s, u);
  let steps = base.steps;
  if (u.step !== null) {
    // The core gives the index: an existing one is replaced, index == length appends.
    const { index, step } = u.step;
    steps = [...steps.slice(0, index), step, ...steps.slice(index + 1)];
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
    // The room's own agent keeps the header's name ("Atlas", not the profile's "atlas-bot"):
    // one agent, one name (D11, P2).
    if (cast.counterpart?.userId === id) return cast.counterpart.name;
    const person = cast.people.find((p) => p.userId === id);
    if (person) return person.name;
  }
  return cast.counterpart?.name ?? null;
}
