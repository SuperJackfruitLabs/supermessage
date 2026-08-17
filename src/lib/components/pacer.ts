// Turning an agent's answer from a series of jumps into writing.
//
// The wire is chunky by design. `apps/hub`'s streaming policy holds a delta
// until it has at least 24 characters *and* lands on a sentence boundary, or
// until 1.5s has passed — because every delta is a to-device message sent to
// every one of the reader's devices, and sending one per token would be
// indefensible. The consequence is that the text can sit perfectly still for a
// second and a half and then gain a whole paragraph at once.
//
// That is the right thing to send and the wrong thing to show. So the client
// keeps its own clock: it stays some way behind what it has been told, and
// spends that debt down steadily. Nothing about the wire changes.

/**
 * How fast text is revealed, in characters per second.
 *
 * Fast enough not to be a novelty — a 600-character reply finishes in about
 * two seconds, well inside the time an agent takes to write the next one — and
 * slow enough that the 1.5s gaps in the wire are filled rather than merely
 * relocated. Reading speed is nowhere near this; the point is not to pace the
 * reader but to remove the staircase.
 */
export const REVEAL_CHARS_PER_SECOND = 320;

/**
 * How far behind the pacer will ever allow itself to fall.
 *
 * Without a cap the debt is unbounded: an agent that writes faster than the
 * reveal rate would put the pacer further behind with every delta, and by the
 * end of a long answer the reader would be watching text that arrived a
 * minute ago — the exact "it's still typing but it finished ages ago" that
 * makes streaming UIs feel broken. Past this, the rate gives way and the
 * backlog is dropped to the cap.
 */
export const MAX_LAG_CHARS = 400;

export interface Pacer {
  /** The text that should be on screen right now. */
  readonly visible: string;
  /** How many characters have been received but not yet revealed. */
  readonly pending: number;
  /**
   * Accepts a delta. `text` is the whole answer so far, not the increment —
   * the wire format is cumulative, because to-device delivery is
   * at-least-once and unordered.
   *
   * Takes no timestamp: arrival time does not affect the reveal, which is
   * paced purely by `advance`. A delta that arrives late is simply more text
   * to owe.
   */
  receive(text: string): void;
  /** Reveals whatever `elapsedMs` of steady writing is worth. */
  advance(elapsedMs: number): void;
  /** Reveals everything immediately — the turn is over. */
  finish(): void;
  /** Forgets the turn entirely. */
  reset(): void;
}

/**
 * A pacer for one room's live text.
 *
 * Deliberately pure and clock-free: `advance` is handed elapsed time rather
 * than reading one, so the whole thing is testable without fake timers and the
 * caller can drive it from whatever frame loop it already has.
 *
 * **Monotonic in what it has been told.** A delta that is shorter than what we
 * already hold is not applied. To-device delivery is at-least-once and
 * unordered, so a late duplicate of an earlier chunk will arrive, and the one
 * thing a reader must never see is written text being un-written. Comparing
 * lengths is enough because the format is cumulative: a longer string is
 * always the later one.
 */
export function createPacer(): Pacer {
  let target = "";
  let revealed = 0;
  // Carried between advances so a rate that yields a fractional character per
  // frame still adds up — at 60fps and 320 c/s that is 5.3 characters a frame,
  // and dropping the fraction every time would cost nearly 6% of the rate.
  let carry = 0;

  return {
    get visible(): string {
      return target.slice(0, revealed);
    },
    get pending(): number {
      return target.length - revealed;
    },
    receive(text: string): void {
      // Shorter than what we hold: a reordered or duplicated delta. Ignore it
      // rather than rewind — see this function's doc comment.
      if (text.length < target.length) return;
      target = text;
    },
    advance(elapsedMs: number): void {
      if (revealed >= target.length) {
        carry = 0;
        return;
      }
      carry += (elapsedMs / 1000) * REVEAL_CHARS_PER_SECOND;
      const step = Math.floor(carry);
      carry -= step;
      revealed = Math.min(target.length, revealed + step);

      // Too far behind to catch up at the steady rate. Jump the backlog down
      // to the cap: a visible skip once beats a reader watching an answer that
      // finished a minute ago.
      const lag = target.length - revealed;
      if (lag > MAX_LAG_CHARS) revealed = target.length - MAX_LAG_CHARS;
    },
    finish(): void {
      revealed = target.length;
      carry = 0;
    },
    reset(): void {
      target = "";
      revealed = 0;
      carry = 0;
    },
  };
}
