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

  deps.onTyping((payload: TypingPayload) => {
    if (payload.roomId !== roomId) return;
    users = payload.users;
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
