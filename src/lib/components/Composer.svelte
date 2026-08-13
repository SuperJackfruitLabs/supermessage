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

<!--
  Separation from the timeline/typing indicator above is a ground change
  (surface → surface-sunken), not a hairline: this strip and `Timeline`'s
  date-divider rule already lean on hairlines constantly (roster rows, the
  room header, the connection banner), so one more directly under the
  typing indicator would just read as a fourth stacked bar rather than a
  boundary. A change of ground reads as a distinct zone — "you have left
  the reading surface and entered the instrument" — without adding a line,
  and it's consistent with `--color-surface-sunken`'s existing job as the
  inset/well tone (roster, info panel, and formerly this same textarea).
  Deliberately no border-t on any of the three strips below: they now share
  one ground and read as a single contiguous instrument rather than three
  independently bordered bars.
-->
{#if replyTarget}
  <div class="flex shrink-0 items-start gap-2 border-l-2 border-l-accent bg-surface-sunken px-4 py-2">
    <div class="min-w-0 flex-1">
      <p class="truncate font-mono text-label text-content-muted uppercase">
        Replying to {replyTarget.sender}
      </p>
      {#if replyTarget.excerpt}
        <p class="mt-0.5 truncate font-serif text-meta text-content-muted">{replyTarget.excerpt}</p>
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
  <div class="flex shrink-0 flex-col gap-0.5 bg-surface-sunken px-4 py-2">
    <span class="font-mono text-label text-danger uppercase">Send failed</span>
    <p class="selectable text-ui text-content" role="alert">{sendError}</p>
  </div>
{/if}
<div
  class="flex shrink-0 items-end gap-2 bg-surface-sunken px-4 py-3"
  style="padding-bottom: calc(0.75rem + var(--inset-bottom));"
>
  <div
    class="flex min-w-0 flex-1 items-end gap-1.5 rounded-md px-2 py-1 outline-offset-2 transition-colors focus-within:outline focus-within:outline-2 focus-within:outline-accent"
  >
    <span class="shrink-0 pb-1.5 font-mono text-content-faint" aria-hidden="true">›</span>
    <textarea
      bind:value
      onkeydown={handleKeydown}
      oninput={handleInput}
      disabled={sending}
      rows="1"
      placeholder={replyTarget ? `Reply to ${replyTarget.sender}…` : "Message…"}
      class="max-h-40 min-h-10 flex-1 resize-none bg-transparent py-1 font-sans text-ui text-content outline-none placeholder:text-content-faint disabled:opacity-60"
    ></textarea>
  </div>
  <!--
    Disabled is a *ghost*, not a faded fill. `disabled:opacity-60` over the
    accent fill measured 2.04:1 light / 2.17:1 dark. WCAG exempts inactive
    controls, so that was not a violation — but this button is disabled
    whenever the composer is empty, which is most of the time, and an
    illegible label on the primary instrument's main control most of the
    time is a usability problem the exemption does not make go away.
    Dropping the fill rather than fading it keeps the label readable
    (`content-faint` on `surface`, 4.92:1) *and* strengthens the inert
    signal instead of weakening it: an unfilled button plainly is not the
    primary action, whereas a washed-out filled one just looks broken.

    The border is `border-transparent` when enabled rather than absent, so
    the box measures the same in both states — adding a border only on
    `:disabled` would shift the button by a pixel every time the composer
    goes from empty to non-empty, which is on the first keystroke of every
    message.
  -->
  <button
    type="button"
    onclick={send}
    disabled={!canSend}
    class="flex shrink-0 items-center gap-1.5 rounded-md border border-transparent bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-colors disabled:border-border disabled:bg-transparent disabled:text-content-faint"
  >
    Send
    <!--
      80%, not the spec's literal 70% (§6.4). The hint is quieter than the
      label either way, but at 70% `--color-accent-content` composites to
      4.32:1 (light) / 4.14:1 (dark) against `--color-accent` — under the
      4.5:1 floor §9 sets for the same design. 80% reads 5.12:1 / 5.09:1 and
      is still visibly secondary. Same precedent as the palette's own
      revisions: where §9's floor and a literal value in the spec disagree,
      the floor wins and the value moves. Measured by compositing the layer
      stack in a canvas, not by parsing `getComputedStyle` — this element's
      colour is an alpha over an accent ground, which is precisely the case
      an rgba-parsing probe gets wrong.
    -->
    <span aria-hidden="true" class="font-mono opacity-80">⏎</span>
  </button>
</div>
