<script lang="ts">
  // Pinned message composer for the focused room. Enter sends, Shift+Enter
  // inserts a newline; the textarea clears only once the send call
  // succeeds, so a failed send leaves the draft in place rather than
  // silently discarding it.
  //
  // Deliberately does not touch `timelineStore.items` — the core appends a
  // local echo to the timeline itself (see `timelineStore`'s module doc
  // comment), which arrives back through the diff stream. Appending here
  // too would render every sent message twice. Same rule for a reply: when
  // `replyTarget` is set, `send` routes through `timelineStore.sendReply`
  // instead of `send`, but still never touches `items` itself.
  //
  // Per-room drafts, not a single shared one: this component is intentionally
  // *not* remounted when the focused room changes (unlike `Timeline`, which
  // `+page.svelte` wraps in `{#key roomsStore.selectedId}`) — remounting
  // would be the simpler fix, but it means a draft evaporates the instant
  // you switch away, which is a worse experience for an ordinary "let me
  // check something in another room" detour. Instead `roomId` is a reactive
  // prop, and every time it changes, `DraftTracker.switchTo` atomically
  // saves the outgoing room's in-progress text and returns the incoming
  // room's — see `draftTracker.ts` for why this is a real bug fix, not a
  // nicety: without it, `value` would keep showing (and sending would keep
  // targeting) whichever room was focused when the text was typed.
  //
  // The pending reply target has the identical hazard and is scoped the
  // identical way, through `replyTargetStore` (`$lib/stores/replyTarget.svelte.ts`)
  // rather than a second `DraftTracker` — see that store's doc comment for
  // why it doesn't need `switchTo`'s "flush the outgoing value" step a
  // continuously-typed draft does: nothing here ever mutates a reply target
  // in place, only `set`/`clear` calls that already name the room they
  // apply to. `replyTarget` below is a `$derived` read of `roomId`'s own
  // entry, so it updates automatically both when `roomId` changes and when
  // `Timeline.svelte` sets one for the room currently shown.
  //
  // Typing notices have the identical room-scoping hazard as the draft
  // itself, plus a lifecycle draft doesn't: they must be explicitly
  // *stopped* for the outgoing room, not merely stop applying to it — see
  // `stopTyping` below, called from the same room-switch effect that
  // `drafts.switchTo` already runs from, on send, and on this component's
  // own destruction. `typingTracker` (`./typingTracker.ts`) is what decides
  // *whether* a given moment warrants a `setTyping` call at all; this file
  // only decides *when* those moments are (a keystroke, a pause, a send, a
  // switch, an unmount).

  import { onDestroy } from "svelte";
  import { timelineStore } from "$lib/stores/timeline.svelte";
  import { replyTargetStore } from "$lib/stores/replyTarget.svelte";
  import { DraftTracker } from "./draftTracker";
  import { TYPING_STOP_AFTER_MS, TypingTracker } from "./typingTracker";
  import type { CoreError } from "$lib/ipc";

  let { roomId }: { roomId: string } = $props();

  const drafts = new DraftTracker();
  let value = $state("");
  let sending = $state(false);
  /**
   * Set only for the one failure mode worth calling out by name: the core
   * rejected a send with `CoreError.kind === "roomChanged"` because the
   * reader switched rooms before it went through (see `send`'s `catch`
   * below and `$lib/ipc.ts`'s `CoreErrorKind` doc comment). Every other
   * failure still just logs to the console — this is not a general-purpose
   * error banner — but a room-changed rejection is the one case where
   * silence would look like the message went through when it didn't, which
   * is worse than an ordinary visible failure.
   */
  let sendError = $state<string | null>(null);

  const typingTracker = new TypingTracker();
  // The pending inactivity timer that fires `stopTyping` after a pause in
  // keystrokes — plain bookkeeping, not a value the template reads.
  let typingStopTimer: ReturnType<typeof setTimeout> | undefined;

  // Bookkeeping for the effect below, not a value the template reads —
  // same reasoning as `Timeline.svelte`'s `previousLastId`.
  let previousRoomId: string | null = null;

  /**
   * Sends an explicit `setTyping(forRoomId, false)` if (and only if)
   * `typingTracker` still considers typing active, and cancels any pending
   * inactivity timer. Idempotent — safe to call from every "typing has
   * definitely stopped" moment (a pause, a send, a room switch, this
   * component's own destruction) without checking first whether one of the
   * others already fired.
   */
  function stopTyping(forRoomId: string): void {
    if (typingStopTimer !== undefined) {
      clearTimeout(typingStopTimer);
      typingStopTimer = undefined;
    }
    if (typingTracker.onStop()) {
      void timelineStore.setTyping(forRoomId, false).catch((err: unknown) => {
        console.error("failed to send stop-typing notice", err);
      });
    }
  }

  /**
   * Called on every keystroke that leaves a non-empty draft. `typingTracker.onType`
   * is the actual throttle decision (see its doc comment for the interval and
   * why); this only reschedules the inactivity timer that eventually calls
   * `stopTyping` on its own, so a reader who stops typing without sending or
   * switching rooms still clears their notice promptly rather than waiting
   * out the whole session.
   */
  function handleTyping(): void {
    if (typingTracker.onType(Date.now())) {
      void timelineStore.setTyping(roomId, true).catch((err: unknown) => {
        console.error("failed to send typing notice", err);
      });
    }
    if (typingStopTimer !== undefined) clearTimeout(typingStopTimer);
    typingStopTimer = setTimeout(() => stopTyping(roomId), TYPING_STOP_AFTER_MS);
  }

  /** `textarea`'s `oninput` — routes to `handleTyping`/`stopTyping` depending on whether anything is left to be "typing". */
  function handleInput(): void {
    if (value.trim() === "") {
      stopTyping(roomId);
    } else {
      handleTyping();
    }
  }

  // The typing notice's own destruction path: signing out, or this pane
  // being torn down for any other reason, must not leave a stale "typing"
  // notice active in whichever room was last focused.
  onDestroy(() => stopTyping(roomId));

  $effect(() => {
    if (roomId !== previousRoomId) {
      // Stop typing in the *outgoing* room before switching state to the
      // new one — `stopTyping` must be called with `previousRoomId`, not
      // `roomId`, or it would (incorrectly) tell the room the reader is
      // switching *into* that they just stopped typing there, and never
      // notify the room they're leaving at all. `previousRoomId === null`
      // is the first mount, with no outgoing room to notify — same guard
      // `drafts.switchTo` already uses for "no before room yet".
      if (previousRoomId !== null) stopTyping(previousRoomId);
      value = drafts.switchTo(roomId, value);
      previousRoomId = roomId;
      // A room-changed send error names the room switch that caused it;
      // once the reader has switched again, it's talking about a switch
      // that's no longer the current one, so it stops being useful and
      // starts being confusing pinned against whatever room they're looking
      // at now.
      sendError = null;
    }
  });

  /** `roomId`'s own pending reply target, or `null` — see this file's top-of-script doc comment. */
  const replyTarget = $derived(replyTargetStore.get(roomId));

  const trimmed = $derived(value.trim());
  const canSend = $derived(trimmed !== "" && !sending);

  /** Cancels the pending reply for `roomId` without discarding the draft text. */
  function cancelReply(): void {
    replyTargetStore.clear(roomId);
  }

  async function send(): Promise<void> {
    if (!canSend) return;
    const body = trimmed;
    const sentRoomId = roomId;
    // Snapshot *before* the `await` below: if the reader switches rooms
    // while this send is in flight, `replyTargetStore.get(roomId)` would
    // read the *newly* focused room's target, not the one this send was
    // actually composed against — the same "read the wrong room's state
    // after an await" hazard `roomId === sentRoomId` below already guards
    // for `value`.
    const target = replyTargetStore.get(sentRoomId);
    // "false on send" (this task's brief) — stopped here, not left to the
    // homeserver-side notice to simply expire, so anyone still watching
    // this room's typing indicator sees it clear the instant the message
    // goes out rather than up to `TYPING_NOTICE_TIMEOUT` (4s) later.
    stopTyping(sentRoomId);
    sending = true;
    sendError = null;
    try {
      if (target) {
        await timelineStore.sendReply(sentRoomId, body, target.eventId);
      } else {
        await timelineStore.send(sentRoomId, body);
      }
      if (roomId === sentRoomId) {
        value = "";
      } else {
        // The reader switched to a different room while this send was in
        // flight. `value` now belongs to that other room — clearing it here
        // would wipe out whatever they've since started typing there. Only
        // the sent room's stored draft needs clearing.
        drafts.setDraftFor(sentRoomId, "");
      }
      // Always the sent room, never `roomId` — same reasoning as
      // `drafts.setDraftFor(sentRoomId, "")` above: clearing whichever room
      // is *now* focused could wipe out a reply the reader has since
      // started composing there.
      replyTargetStore.clear(sentRoomId);
    } catch (err) {
      console.error("failed to send message", err);
      // `value`/`replyTargetStore` are deliberately left untouched here —
      // the draft (and any pending reply target) survive a failed send
      // exactly like the doc comment at the top of this file promises,
      // whichever room they now belong to.
      if ((err as CoreError)?.kind === "roomChanged") {
        sendError =
          "Not sent — you switched rooms before this went through. Your draft is safe; try again.";
      }
    } finally {
      sending = false;
    }
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void send();
    }
  }
</script>

{#if replyTarget}
  <div class="flex shrink-0 items-center gap-2 border-t border-border bg-surface-sunken px-4 py-2 text-xs">
    <div class="min-w-0 flex-1 truncate">
      <span class="font-medium text-content">Replying to {replyTarget.sender}</span>
      {#if replyTarget.excerpt}
        <span class="text-content-muted">— {replyTarget.excerpt}</span>
      {/if}
    </div>
    <button
      type="button"
      onclick={cancelReply}
      aria-label="Cancel reply"
      class="shrink-0 rounded px-1.5 py-0.5 text-content-muted transition-colors hover:bg-surface hover:text-content"
    >
      ✕
    </button>
  </div>
{/if}
{#if sendError}
  <div class="flex shrink-0 items-center border-t border-border bg-surface-sunken px-4 py-2 text-xs">
    <p class="selectable text-danger" role="alert">{sendError}</p>
  </div>
{/if}
<div
  class="flex shrink-0 items-end gap-2 border-t border-border bg-surface px-4 py-3"
  style="padding-bottom: calc(0.75rem + var(--inset-bottom));"
>
  <textarea
    bind:value
    onkeydown={handleKeydown}
    oninput={handleInput}
    disabled={sending}
    rows="1"
    placeholder={replyTarget ? `Reply to ${replyTarget.sender}…` : "Message…"}
    class="max-h-40 min-h-10 flex-1 resize-none rounded-md border border-border bg-surface-sunken px-3 py-2 text-sm text-content outline-none focus:border-accent disabled:opacity-60"
  ></textarea>
  <button
    type="button"
    onclick={send}
    disabled={!canSend}
    class="shrink-0 rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-content transition-opacity disabled:opacity-60"
  >
    Send
  </button>
</div>
