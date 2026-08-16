// Tracks who's typing in the focused room (`sm://typing`). Mirrors
// `connection.svelte.ts`'s shape — the event carries the *current* full
// typing list, not an incremental patch (see `core::timeline::TYPING_EVENT`'s
// doc comment), so there's no diff/gap machinery here either.
//
// Unlike `connectionStore`, this state is per-room, and the core only ever
// streams typing for whichever room is focused (mirroring `FocusedTimeline`'s
// single-subscription invariant — see `core::timeline`'s module doc
// comment). `focus` is what keeps this store in step with that: it must be
// called synchronously, before the corresponding `timelineSubscribe` command
// is issued, exactly like `gapSync.resetForNewSubscription()` already is for
// the timeline diff channel — `timelineStore.subscribeTo` is the one call
// site that does both, for the identical race described in that module's
// top-of-file doc comment: without narrowing what counts as "ours" before
// the round trip starts, a typing event the *previous* room's still-live
// core-side subscription emits while the new room's subscribe command is in
// flight would still match `roomId` (nothing has been reset to the new room
// yet) and render under the new room's header for a moment.

import { onTyping as defaultOnTyping, type TypingPayload, type TypingUser } from "$lib/ipc";

/**
 * How long a typing notice is believed without being renewed.
 *
 * A typing notice is a *claim with a deadline* — the sender says "I am typing,
 * ask again within N seconds" — and the homeserver publishes the end of it as
 * an ordinary ephemeral event. Ephemeral events are the one class Matrix never
 * retransmits: miss it on a gap, a resync or a dropped socket and nothing will
 * ever say "they stopped".
 *
 * That is not hypothetical. An agent answered, the bridge sent its stop, the
 * homeserver broadcast `user_ids: []` — verified against it directly — and the
 * indicator still sat there until the reader left the room and came back,
 * because `focus` was the only thing that ever cleared it.
 *
 * 30 seconds because that is the timeout the AgentPod bridge sends and renews
 * at 20s intervals while an agent is genuinely working; a live typist always
 * refreshes well inside this window, so the only notice this expires is one
 * whose ending was lost.
 */
export const TYPING_TTL_MS = 30_000;

export interface TypingStoreDeps {
  onTyping: typeof defaultOnTyping;
}

const defaultDeps: TypingStoreDeps = { onTyping: defaultOnTyping };

export function createTypingStore(deps: TypingStoreDeps = defaultDeps) {
  // The only room this store will accept typing state for — see this
  // module's doc comment. `null` until the first `focus` call (before any
  // room has ever been subscribed to).
  let roomId: string | null = null;
  let users = $state<TypingUser[]>([]);
  /** Fires when the current notice has gone unrenewed for too long. */
  let expiry: ReturnType<typeof setTimeout> | null = null;

  function clearExpiry(): void {
    if (expiry !== null) {
      clearTimeout(expiry);
      expiry = null;
    }
  }

  /**
   * Applies a payload and arms (or disarms) the expiry that outlives it.
   *
   * An empty list needs no timer — it IS the end. A non-empty one restarts the
   * clock, so a typist who keeps renewing is never cut off mid-thought.
   */
  function apply(next: TypingUser[]): void {
    users = next;
    clearExpiry();
    if (next.length === 0) return;
    expiry = setTimeout(() => {
      expiry = null;
      users = [];
    }, TYPING_TTL_MS);
  }

  deps.onTyping((payload: TypingPayload) => {
    if (payload.roomId !== roomId) return;
    apply(payload.users);
  }).catch((err: unknown) => {
    console.error("typingStore: failed to subscribe to typing events", err);
  });

  /**
   * Declares which room's typing state this store should show from now on,
   * clearing whatever was shown for the previous room. See this module's
   * doc comment for why the caller must call this *before* issuing the
   * corresponding `timelineSubscribe` command, not after.
   */
  function focus(newRoomId: string): void {
    roomId = newRoomId;
    clearExpiry();
    users = [];
  }

  return {
    /** Who's typing in the currently focused room, if anyone. */
    get users(): TypingUser[] {
      return users;
    },
    focus,
  };
}

export const typingStore = createTypingStore();
