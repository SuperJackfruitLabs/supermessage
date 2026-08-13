// Per-room composer drafts.
//
// Extracted as a plain, framework-free class specifically so the
// room-switch logic is unit-testable without a Svelte component-testing
// setup — this project has none (vitest runs with `environment: "node"`)
// and adding one is out of scope for this fix. `Composer.svelte` is a thin
// reactive wrapper: it calls `switchTo` once, from an `$effect` keyed on its
// `roomId` prop, and otherwise just binds the textarea to the returned
// string. All the logic that matters is here, and is what `draftTracker.test.ts`
// exercises directly.
//
// This exists to fix a real bug: `Composer` sits outside the `{#key
// roomsStore.selectedId}` block that remounts `Timeline` per room (it has
// to, so a draft can survive a room switch instead of being wiped — see
// below), which means its own state is *not* reset by a room switch. Without
// this tracker, its `value` would keep showing — and sending would keep
// targeting — whichever room was focused when the text was typed, not
// whichever room is focused when Enter is pressed.

/**
 * Tracks one unsent draft string per room, and which room is currently
 * focused. `switchTo` is the only method that matters: it atomically saves
 * the outgoing room's in-progress text and returns the incoming room's, so
 * a caller can never observe a mixed/stale state where the visible draft
 * belongs to a room other than the one that's about to receive it.
 */
export class DraftTracker {
  #drafts = new Map<string, string>();
  #currentRoomId: string | null = null;

  /**
   * Switches focus to `roomId`. `outgoingText` is the caller's current
   * on-screen value, saved under whichever room was focused before (a no-op
   * the first time this is called, since there is no "before" room yet).
   * Returns the text to display for `roomId` — its saved draft, or `""` if
   * it has none yet.
   *
   * A no-op that returns `outgoingText` unchanged if `roomId` is already
   * the focused room, so callers can call this unconditionally from a
   * reactive effect without worrying about redundant invocations clobbering
   * anything.
   */
  switchTo(roomId: string, outgoingText: string): string {
    if (roomId === this.#currentRoomId) return outgoingText;
    if (this.#currentRoomId !== null) {
      this.#drafts.set(this.#currentRoomId, outgoingText);
    }
    this.#currentRoomId = roomId;
    return this.#drafts.get(roomId) ?? "";
  }

  /**
   * Overwrites the saved draft for `roomId` without changing which room is
   * currently focused. Exists for one case `switchTo` can't cover: an
   * in-flight send that started while `roomId` was focused finishing
   * *after* the reader has already switched to a different room — the
   * just-sent room's stored draft must still end up cleared (so switching
   * back to it later doesn't resurrect the message that was already sent),
   * but whatever the reader is now typing in the newly-focused room must
   * not be touched.
   */
  setDraftFor(roomId: string, text: string): void {
    this.#drafts.set(roomId, text);
  }
}
