// What the message pane shows while a room switch is in flight.
//
// Switching rooms is not instant — the core tears down one subscription and
// builds another, and the first batch of the new room arrives over IPC. That
// gap is short but it is not nothing, and what the pane put in it was an
// assertion it had no grounds for.

/**
 * How long a switch may take before the pane admits to waiting.
 *
 * A measured switch settled in 145ms. A spinner that appears and disappears
 * inside that window does not describe the wait, it *is* the flicker — two
 * more visual states rather than fewer. So the pane holds a calm, empty
 * surface first and only says anything if the wait outlasts a flinch.
 */
export const LOADING_AFTER_MS = 220;

/** What the pane should be rendering. */
export type PaneState =
  /** The room's messages. */
  | "rows"
  /** The room answered, and there is genuinely nothing in it. */
  | "empty"
  /** Waiting, briefly, and saying nothing about it. */
  | "settling"
  /** Waiting long enough that silence would read as broken. */
  | "loading";

export interface PaneInputs {
  /** Whether this room's timeline has delivered a batch — even an empty one. */
  loaded: boolean;
  /** How many display rows the pane has to show. */
  rowCount: number;
  /** How long the pane has been waiting for this room, in ms. */
  waitingMs: number;
}

/**
 * Decides what the pane shows, from what it actually knows.
 *
 * The distinction that was missing is `loaded`: "this room has answered" is
 * not the same as "this room has no messages", and the pane had no way to tell
 * them apart. It rendered *"Nothing here yet."* whenever the item list was
 * empty — which, during every single room switch, it briefly is.
 *
 * Measured on 2026-08-17 switching between two rooms: `"Nothing here yet."`
 * appeared 10ms in, over a room holding 1937px of history, and was gone by
 * 66ms. Not long, but it is the one state in the sequence that says something
 * false rather than merely showing nothing, which is why it read as a fault
 * and the bare scroller either side of it read as loading.
 *
 * Rows outrank everything: a room that has messages on screen shows them,
 * whatever else is still in flight behind them (back-pagination and re-seeds
 * both keep arriving long after the first batch). Only a pane with nothing to
 * draw has a decision to make, and then the only question is whether it knows
 * yet.
 *
 * Pure, so the rule is testable; the fade it drives is not.
 */
export function paneState({ loaded, rowCount, waitingMs }: PaneInputs): PaneState {
  if (rowCount > 0) return "rows";
  // Before the threshold the pane says nothing at all, whatever it has heard.
  // `loaded` alone is not enough to call a room empty: the core opens every
  // subscription with an empty `Reset` and only *then* paginates history in,
  // so "this room has answered" arrives a beat before "this room has anything
  // in it" — measured still flashing "Nothing here yet." at 18ms into a switch
  // with the `loaded` check alone in place. Emptiness is the one claim here
  // that can be wrong, so it is the one that waits.
  if (waitingMs < LOADING_AFTER_MS) return "settling";
  return loaded ? "empty" : "loading";
}
