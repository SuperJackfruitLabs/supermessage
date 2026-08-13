// Decides when `Composer.svelte` should actually call `timelineStore.setTyping`
// as the reader types — a plain, framework-free class, extracted for the
// same reason `draftTracker.ts` is (see that file's doc comment): this
// project's vitest runs with `environment: "node"` and has no
// component-mounting setup, so the logic that matters has to be testable on
// its own.
//
// Matrix's own convention (and this task's brief): send `typing: true` with
// a timeout, refreshed periodically while the reader keeps typing, and an
// explicit `typing: false` on send or when they stop. `Room::typing_notice`
// on the Rust side already throttles the *network* request for exactly this
// "call it on every keystroke" pattern (`matrix-sdk-0.18.0/src/room/mod.rs`'s
// `TYPING_NOTICE_TIMEOUT`/`TYPING_NOTICE_RESEND_TIMEOUT`, 4s/3s — see
// `core::timeline::FocusedTimeline::set_typing`'s doc comment) — but that
// throttle only kicks in *after* an IPC round trip has already been made.
// This tracker is the layer above that: it decides whether a keystroke
// should even trigger the `setTyping` call at all, so a burst of keystrokes
// produces at most one IPC command every `TYPING_SEND_INTERVAL_MS`, not one
// per keystroke.

/**
 * How often a keystroke may trigger a fresh `typing: true` notice, in ms.
 *
 * Matches `matrix-sdk`'s own `TYPING_NOTICE_RESEND_TIMEOUT` (3s) exactly:
 * sending more often than that would only produce IPC calls the Rust side
 * already no-ops on the network, so there is nothing to gain from a shorter
 * interval here — and using the *same* number keeps this tracker's decision
 * and the SDK's own resend window from drifting into two different
 * "how often is too often" answers for what is really one throttle.
 */
export const TYPING_SEND_INTERVAL_MS = 3_000;

/**
 * How long the reader must stop typing before an explicit `typing: false` is
 * sent, in ms.
 *
 * Deliberately longer than the server-side notice's own validity window
 * (`matrix-sdk`'s `TYPING_NOTICE_TIMEOUT`, 4s): by the time this fires, the
 * last `typing: true` this tracker sent has already expired on the
 * homeserver on its own, so sending the explicit `false` here is a
 * courtesy that clears the indicator promptly rather than making other
 * members wait out the expiry — never a race against the notice still being
 * "active" server-side.
 */
export const TYPING_STOP_AFTER_MS = 5_000;

/**
 * Tracks one composer's typing state against real wall-clock time, deciding
 * when a `setTyping(true)`/`setTyping(false)` call is actually warranted.
 * Does not own a timer itself and does not call `setTyping` — `Composer.svelte`
 * drives it (a `setTimeout` for the inactivity case, `Date.now()` for the
 * throttle) and acts on the booleans this returns, so the decision itself
 * stays pure and independently testable (see `typingTracker.test.ts`).
 */
export class TypingTracker {
  #lastSentTrueAtMs: number | null = null;
  #active = false;

  /**
   * Call on every keystroke. Returns whether a fresh `setTyping(true)`
   * should be sent right now — `true` at most once per
   * {@link TYPING_SEND_INTERVAL_MS}, regardless of how many times this is
   * called in between.
   */
  onType(nowMs: number): boolean {
    this.#active = true;
    if (this.#lastSentTrueAtMs !== null && nowMs - this.#lastSentTrueAtMs < TYPING_SEND_INTERVAL_MS) {
      return false;
    }
    this.#lastSentTrueAtMs = nowMs;
    return true;
  }

  /**
   * Call when typing should be considered stopped — the inactivity timer
   * elapsing, a message being sent, the draft being cleared, or the
   * composer losing the room it was typing in (room switch, unmount).
   * Returns whether a `setTyping(false)` is actually warranted: `false` when
   * nothing had been reported as typing since the last stop (or ever), so
   * there is no active notice to clear and no call worth making.
   */
  onStop(): boolean {
    if (!this.#active) return false;
    this.#active = false;
    this.#lastSentTrueAtMs = null;
    return true;
  }
}
