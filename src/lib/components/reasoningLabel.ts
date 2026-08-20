// What the collapsed reasoning header says.
//
// Ported from svelte-ai-elements' `reasoning-trigger`, whose rule is "Thinking
// ..." while streaming and "Thought for N seconds" after. Two of its cases
// read as bugs rather than facts, so they are named here instead: a duration of
// zero becomes "a moment" (the turn did take time — less than we report in),
// and an unknown duration becomes "Thought about it" rather than a guess.
//
// The thought channel carries no timing of its own (see the hub's
// `matrix-as/live.ts`), so the caller times it from the deltas it sees. A
// device that was asleep for the first half of a turn has no honest number,
// which is why `seconds` is optional rather than defaulted to zero.

export interface ReasoningState {
  /** Is the agent still producing reasoning? */
  streaming: boolean;
  /** How long it has been thinking, if we saw the start. */
  seconds: number | undefined;
}

/** Roughly how long, in words a reader can use at a glance. */
function spent(seconds: number): string {
  if (seconds < 1) return "a moment";
  if (seconds < 60) {
    return `${seconds} ${seconds === 1 ? "second" : "seconds"}`;
  }
  // Floored, not rounded: "2 minutes" for 2m34s is the honest reading of a
  // clock, and rounding up would report a turn as longer than it was.
  const minutes = Math.floor(seconds / 60);
  return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}

export function reasoningLabel({ streaming, seconds }: ReasoningState): string {
  // Present tense first, whatever the clock says: a reader deciding whether to
  // wait needs the tense more than the number.
  if (streaming) return "Thinking…";
  if (seconds === undefined) return "Thought about it";
  if (seconds < 1) return "Thought for a moment";
  return `Thought for ${spent(seconds)}`;
}
