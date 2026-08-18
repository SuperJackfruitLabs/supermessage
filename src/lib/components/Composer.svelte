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
  //
  // A staged attachment is a *third* piece of per-room state with the same
  // hazard, held in `StagedAttachmentTracker` (`./stagedAttachment.ts`) for
  // the same reason the draft is held in `DraftTracker`: everything room-
  // scoped in this component has to be scoped explicitly, because the
  // component itself is never remounted. Two things about it differ from
  // the draft and are decided in that file's doc comments rather than here:
  // there is at most **one** attachment (the core replaces rather than
  // accumulates), and a room switch **discards** it rather than preserving
  // it per room (the core has already dropped the token by then, so a
  // preserved strip would be offering to send a file that can no longer be
  // sent). See `stagedAttachment.ts`'s `StagedAttachmentTracker`.
  //
  // Send is **repurposed, not disabled**, while a file is staged: it reads
  // `Send file` and sends the attachment. §8 of the attachments design
  // offers either, requiring only that the two are never ambiguous at the
  // same moment. Disabling was rejected because the strip is the confirm
  // step §2 requires, and a confirm step needs an *affirmative* control —
  // with Send disabled, the only enabled control on the strip would be
  // "Remove", i.e. a review step whose sole available answer is "no". The
  // genuinely ambiguous moments are a staged file alongside something else
  // the reader could expect to go with it — draft text (captions are out of
  // scope for this cut, §1) or a pending reply (an attachment carries no
  // `in_reply_to`) — and the strip states which of those Send will leave
  // behind whenever one is present (`sendCaveat`), while the button itself
  // names its object either way.

  import { onDestroy, onMount, tick } from "svelte";
  import { roomInfo } from "$lib/ipc";
  import { applyMention, findMentionQuery, matchMentions, mentionLabel } from "./mentions";
  // `collectMentions` is the core's: it produces the `m.mentions` that goes on
  // the wire, and an agent decides a message was addressed to it from that.
  // The caret handling above it stays here, where the input model lives.
  import { collectMentions, type Mentionable } from "$lib/ipc";
  import { timelineStore } from "$lib/stores/timeline.svelte";
  import { replyTargetStore } from "$lib/stores/replyTarget.svelte";
  import { DraftTracker } from "./draftTracker";
  import { TYPING_STOP_AFTER_MS, TypingTracker } from "./typingTracker";
  import {
    attachmentFailure,
    sendCaveat,
    stagedStripView,
    StagedAttachmentTracker,
    type AttachmentFailure,
  } from "./stagedAttachment";
  import {
    attachmentDiscard,
    attachmentSend,
    attachmentStage,
    onStagedAttachment,
    type CoreError,
    type StagedAttachment,
  } from "$lib/ipc";

  let { roomId }: { roomId: string } = $props();

  const drafts = new DraftTracker();
  let value = $state("");

  // ── Mentions ──────────────────────────────────────────────────────────────
  //
  // A mission room holds several agents and a person, and "can you retry that"
  // addresses nobody. `m.mentions` is also how an agent's own Matrix client
  // decides a message was meant for it, so this is on the integration path,
  // not only the ergonomics one.

  /** The focused room's joined members, loaded once per room. */
  let members = $state<Mentionable[]>([]);
  /** The textarea, so the caret position can be read and restored. */
  let input = $state<HTMLTextAreaElement | undefined>();
  /** Which suggestion is highlighted, or -1 when the list is closed. */
  let mentionCursor = $state(-1);
  /** What is being typed after an `@`, or null when nothing is. */
  let mentionQuery = $state<string | null>(null);

  const mentionMatches = $derived(
    mentionQuery === null ? [] : matchMentions(mentionQuery, members)
  );

  /**
   * Loads the room's members for autocomplete.
   *
   * Failure is silent by design: mentions are a convenience, and a room whose
   * member list could not be read must still be a room you can type in.
   */
  $effect(() => {
    const id = roomId;
    members = [];
    void roomInfo(id)
      .then((info) => {
        // The room may have changed while this was in flight.
        if (id === roomId) members = info.members;
      })
      .catch(() => {});
  });

  /** Reads the caret and reopens (or closes) the suggestion list. */
  function refreshMentionQuery(): void {
    const caret = input?.selectionStart ?? value.length;
    const found = findMentionQuery(value, caret);
    mentionQuery = found?.query ?? null;
    mentionCursor = found === null ? -1 : 0;
  }

  /** Completes the mention at the caret with `member`. */
  function chooseMention(member: Mentionable): void {
    const caret = input?.selectionStart ?? value.length;
    const next = applyMention(value, caret, member);
    value = next.text;
    mentionQuery = null;
    mentionCursor = -1;
    // Restored after Svelte writes the new value, or the caret jumps to the
    // end and the reader has to find their place again.
    void tick().then(() => {
      input?.focus();
      input?.setSelectionRange(next.caret, next.caret);
    });
  }
  let sending = $state(false);
  /**
   * The last refusal worth showing, or `null`.
   *
   * For a **text** send this stays exactly what it was: set only for
   * `CoreError.kind === "roomChanged"` (see `send`'s `catch` below and
   * `$lib/ipc.ts`'s `CoreErrorKind` doc comment), because a room-changed
   * rejection is the one case where silence would look like the message
   * went through when it didn't. Every other text-send failure still just
   * logs to the console; this is not a general-purpose error banner.
   *
   * For an **attachment** every failure surfaces, and that asymmetry is
   * deliberate rather than an oversight. A failed text send leaves the draft
   * in the box, so the reader can see for themselves that nothing went; a
   * failed attachment leaves *nothing* on screen — the strip is gone,
   * because the token is spent or unreachable either way (see
   * `attachmentSend`'s doc comment) — so a silent failure is indistinguish-
   * able from a successful send until the echo fails to arrive. It also has
   * refusals a reader can actually act on ("that file is 200.0 MiB, but this
   * homeserver accepts at most 50.0 MiB"), which is the entire reason the
   * core gives them typed kinds.
   *
   * `{ label, message }` rather than a bare string so the eyebrow can name
   * *which* refusal this is — `attachmentFailure` in `./stagedAttachment.ts`
   * is the one place that mapping lives.
   */
  let failure = $state<AttachmentFailure | null>(null);

  /**
   * The one file staged for the currently focused room, or `null` — a
   * `$state` mirror of `attachments`, which is a plain class for the same
   * reason `DraftTracker` is (unit-testable without a component-testing
   * setup this project does not have).
   *
   * Every write to it goes through `refreshStaged`, which re-reads the
   * tracker *for the current room* rather than assigning whatever value is
   * to hand. That is what makes the room-scoping rule impossible to bypass
   * by accident: there is no path that sets this to an attachment without
   * asking the tracker whether it belongs to `roomId`.
   */
  const attachments = new StagedAttachmentTracker();
  let staged = $state<StagedAttachment | null>(null);
  /** Whether a native picker is currently open, so a second click cannot open a second one. */
  let staging = $state(false);

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
    refreshMentionQuery();
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

  /** Re-reads the tracker for the room currently focused. The only writer of `staged`. */
  function refreshStaged(): void {
    staged = attachments.stagedFor(roomId);
  }

  /**
   * Tells the core to drop a staged file. Fire-and-forget: `attachment_discard`
   * never rejects (see its doc comment), and a discard that somehow failed
   * would leave a path pinned for at most the core's ten-minute staging
   * timeout — not something to put in front of the reader on a path whose
   * whole purpose is cancelling.
   */
  function discardStaged(token: string): void {
    void attachmentDiscard(token).catch((err: unknown) => {
      console.error("failed to discard a staged attachment", err);
    });
  }

  /**
   * Takes ownership of a freshly staged file for `forRoomId` — from either
   * source, since a picked file and a dropped one arrive in the same shape.
   *
   * Overwrites rather than accumulates: the core holds one staged file per
   * room and drops the superseded token itself, so a list here would be a
   * list of tokens that mostly no longer resolve. The superseded one is
   * discarded anyway, because the *cross-room* case (a drop landing while
   * the reader is switching) is one the core has not already cleaned up.
   */
  function adoptStaged(forRoomId: string, attachment: StagedAttachment): void {
    const superseded = attachments.stage(forRoomId, attachment);
    if (superseded !== null && superseded.token !== attachment.token) {
      discardStaged(superseded.token);
    }
    refreshStaged();
  }

  /**
   * Opens the native picker for the focused room and stages what comes back.
   *
   * **A `null` result is the reader cancelling, and is not an error** — it is
   * the most common outcome of opening a file chooser (design §7). Reporting
   * it would put a failure on screen every time someone pressed Escape, and
   * a `catch` that fires on the normal path is one that eventually swallows a
   * real failure.
   *
   * `roomId` is snapshotted before the await for the same reason `send`
   * snapshots it: a file chooser is open for as long as a human takes to
   * browse their home directory, and the token that comes back is bound to
   * the room it was opened for. If the reader has moved on by then the file
   * is discarded immediately rather than held for a room they are no longer
   * in — and they are told, because a picked file that silently produced no
   * strip would look like the app had ignored them. The refusal is
   * synthesized rather than caught: the core has no reason to reject here
   * (it verified the room before opening the dialog, not after), but the
   * reader's situation is exactly the one `roomChanged` describes, so it
   * reuses that wording rather than inventing a second phrasing for it.
   */
  async function attach(): Promise<void> {
    if (staging) return;
    const forRoomId = roomId;
    staging = true;
    failure = null;
    try {
      const picked = await attachmentStage(forRoomId);
      if (picked === null) return;
      if (roomId !== forRoomId) {
        discardStaged(picked.token);
        failure = attachmentFailure({ kind: "roomChanged", message: "" }, "attach");
        return;
      }
      adoptStaged(forRoomId, picked);
    } catch (err) {
      console.error("failed to stage an attachment", err);
      failure = attachmentFailure(err, "attach");
    } finally {
      staging = false;
    }
  }

  /** The way out the review step (design §2) requires: drop the strip and the core's copy with it. */
  function removeStaged(): void {
    const removed = attachments.take();
    if (removed !== null) discardStaged(removed.token);
    failure = null;
    refreshStaged();
  }

  /**
   * Sends the staged file. Called from `send` when there is one, so Enter
   * and the Send button behave identically — see this file's top-of-script
   * comment for why Send is repurposed rather than disabled.
   *
   * The strip goes on **every** outcome, success or failure, and that is the
   * fail-closed reading of the token rules rather than a shortcut:
   * `attachment_send` consumes the token before it reads a byte, so after
   * any failure past that point there is nothing left to retry, and the two
   * refusals that *don't* consume (`roomChanged`, `unknownAttachment`) leave
   * a token this room can't use anyway. Leaving the strip up would offer a
   * second press of Send that could only ever produce `unknownAttachment` —
   * or, worse, look like a retry of a send that had actually gone through.
   * The discard covers the one case where the core may still hold it.
   *
   * `attachments.takeToken` rather than `take`, so a send that resolves
   * *after* the reader has already attached a different file clears the send
   * it belonged to and not the new strip — the same "don't touch state that
   * has moved on during an await" rule `send` applies to the draft.
   */
  async function sendStaged(sentRoomId: string, attachment: StagedAttachment): Promise<void> {
    stopTyping(sentRoomId);
    sending = true;
    failure = null;
    try {
      await attachmentSend(sentRoomId, attachment.token);
      attachments.takeToken(attachment.token);
    } catch (err) {
      console.error("failed to send attachment", err);
      failure = attachmentFailure(err, "send");
      const abandoned = attachments.takeToken(attachment.token);
      if (abandoned !== null) discardStaged(abandoned.token);
    } finally {
      sending = false;
      refreshStaged();
    }
  }

  // A file dropped on the window, staged by the **Rust** drag-drop handler.
  //
  // `sm://attachment/staged` is the only drop channel this webview listens
  // on, and the rule is enforced by review rather than by the platform:
  // Tauri's own `tauri://drag-drop` still reaches the webview carrying the
  // dropped files' **raw filesystem paths**, and cannot be switched off
  // while keeping the Rust handler that stages them (`disable_drag_drop_handler()`
  // turns off both). So the design's §3 guarantee is narrower than "the
  // webview never sees a path" — what actually holds is that *our* IPC
  // surface carries none, and that nothing here asks for the one Tauri
  // offers. If you are here to add a second drop handler: don't. There is
  // no capability to gain from it (this event already carries everything
  // the composer renders) and the cost is attacker-reachable paths in
  // webview memory. See `$lib/ipc.ts`'s `onStagedAttachment` and
  // `core::attachments::on_files_dropped`.
  //
  // The payload names no room — a drop lands on whatever the reader is
  // looking at, which the core resolves from its own focused timeline — so
  // it is attributed to `roomId` as read at the moment the event arrives.
  // No await sits between the two, so there is no window for them to
  // disagree, unlike the picker path above.
  onMount(() => {
    let unlisten: (() => void) | undefined;
    let stopped = false;
    onStagedAttachment((payload) => adoptStaged(roomId, payload))
      .then((fn) => {
        if (stopped) void fn();
        else unlisten = fn;
      })
      .catch((err: unknown) => {
        console.error("failed to subscribe to staged attachments", err);
      });
    return () => {
      stopped = true;
      unlisten?.();
    };
  });

  // The attachment's own destruction path, alongside the typing notice's:
  // this pane being torn down (signing out is the only way today) must not
  // leave the core holding a path for a session that is over. `Session::logout`
  // clears the whole staging map anyway, so this is belt and braces for
  // every *other* reason this component might be destroyed.
  onDestroy(() => {
    const abandoned = attachments.take();
    if (abandoned !== null) discardStaged(abandoned.token);
  });

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
      // The staged attachment is discarded by the switch rather than kept
      // per room, unlike the draft immediately above — and the asymmetry is
      // the core's, not a choice made here. `Session::subscribe_timeline`
      // calls `StagedAttachments::retain_room(new_room)` after every
      // successful subscribe, so the outgoing room's token stops resolving
      // whether the webview forgets it or not. Keeping the strip would keep
      // a promise the core has already broken; discarding it here also
      // stops the path being pinned until the staging timeout sweeps it.
      // See `stagedAttachment.ts`'s `StagedAttachmentTracker` doc comment.
      const abandoned = attachments.switchTo(roomId);
      if (abandoned !== null) discardStaged(abandoned.token);
      refreshStaged();
      previousRoomId = roomId;
      // A room-changed send error names the room switch that caused it;
      // once the reader has switched again, it's talking about a switch
      // that's no longer the current one, so it stops being useful and
      // starts being confusing pinned against whatever room they're looking
      // at now. The same holds for every attachment refusal: each one is
      // about a file that no longer exists in this room's composer.
      failure = null;
    }
  });

  /** `roomId`'s own pending reply target, or `null` — see this file's top-of-script doc comment. */
  const replyTarget = $derived(replyTargetStore.get(roomId));

  const trimmed = $derived(value.trim());
  /**
   * A staged file is enough on its own — Send is the affirmative half of the
   * confirm step, so it cannot require text the design explicitly does not
   * support sending alongside a file (captions are out of scope, §1).
   */
  const canSend = $derived((trimmed !== "" || staged !== null) && !sending);

  /** Cancels the pending reply for `roomId` without discarding the draft text. */
  function cancelReply(): void {
    replyTargetStore.clear(roomId);
  }

  async function send(): Promise<void> {
    if (!canSend) return;
    const body = trimmed;
    const sentRoomId = roomId;
    // The attachment wins whenever there is one: Send is repurposed rather
    // than disabled while a file is staged (see this file's top-of-script
    // comment), and the strip says so in as many words whenever there is
    // draft text that could make the question ambiguous. The draft is left
    // exactly where it is — this cut sends a file *or* a message, never one
    // captioned with the other.
    const attachment = attachments.stagedFor(sentRoomId);
    if (attachment !== null) {
      await sendStaged(sentRoomId, attachment);
      return;
    }
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
    failure = null;
    try {
      if (target) {
        await timelineStore.sendReply(sentRoomId, body, target.eventId);
      } else {
        // Resolved from the finished text rather than tracked as the reader
        // types: a mention that was typed and then deleted must not still be
        // reported, and one pasted in whole should count.
        await timelineStore.send(sentRoomId, body, await collectMentions(body, members));
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
        // Unchanged wording, unchanged condition — only the shape moved, to
        // give attachment refusals an eyebrow of their own.
        failure = {
          label: "Send failed",
          message:
            "Not sent — you switched rooms before this went through. Your draft is safe; try again.",
        };
      }
    } finally {
      sending = false;
    }
  }

  function handleKeydown(event: KeyboardEvent): void {
    // The suggestion list owns these keys while it is open — Enter completes
    // the mention rather than sending a half-typed name.
    if (mentionQuery !== null && mentionMatches.length > 0) {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        mentionCursor = (mentionCursor + 1) % mentionMatches.length;
        return;
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        mentionCursor =
          (mentionCursor - 1 + mentionMatches.length) % mentionMatches.length;
        return;
      }
      if (event.key === "Enter" || event.key === "Tab") {
        event.preventDefault();
        const picked = mentionMatches[Math.max(mentionCursor, 0)];
        if (picked) chooseMention(picked);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        mentionQuery = null;
        mentionCursor = -1;
        return;
      }
    }

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
  <div class="shrink-0 border-l-2 border-l-accent bg-surface-sunken px-4 py-2">
    <div class="mx-auto flex w-full max-w-[72ch] items-start gap-2">
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
  </div>
{/if}
{#if failure}
  <div class="shrink-0 bg-surface-sunken px-4 py-2">
    <div class="mx-auto flex w-full max-w-[72ch] flex-col gap-0.5">
    <span class="font-mono text-label text-danger uppercase">{failure.label}</span>
    <p class="selectable text-ui text-content" role="alert">{failure.message}</p>
    </div>
  </div>
{/if}
<!--
  The staged-attachment strip: the review step the attachments design's §2
  requires, in the reply strip's shape (same 2px accent rail, same edge-to-
  edge sunken-family ground, same centred `72ch` column) and directly above
  the composer, because it is about what Send is going to do next.

  **Louder than the reply strip, on purpose.** §2's reasoning is that this
  client cannot delete a message: there is no redaction command, so a file
  sent by a mis-click is permanent and visible to everyone in the room, with
  no recourse inside the app. A review step that has to stop that has to be
  seen, so the ground lifts off the tray to `--color-accent-soft` and the
  remove affordance is a labelled button rather than the reply strip's bare
  `✕`. Neither is a new colour and neither is `--color-signal`, which stays
  reserved for a pending decision (spec §3) — amber here would mean the
  operator owed someone an answer, and they do not; they owe *themselves* a
  glance.

  Three facts, in the order a reader checks them: what it is called, what it
  actually is, and what Send will do with it. The middle line is
  content-sniffed by the core, not read off the extension — which is why it
  is worth screen space in a *confirm* step: `holiday.png` that reads
  `application/octet-stream` is not a photo, and the extension is the one
  part of the name a truncation can hide.

  The filename is plain text through Svelte's own escaping — never `{@html}`
  (§9) — and is bounded and neutralized before it gets here
  (`sanitizeFilename`), because a filename may legally contain newlines and
  bidi overrides and is sender-controlled once echoed back.
-->
{#if staged}
  {@const view = stagedStripView(staged)}
  <!--
    The moments Send could be read two ways: a file staged *and* something
    else in the composer a reader could expect to go with it — draft text
    (captions are out of scope, §1) or a pending reply (an attachment
    carries no `in_reply_to`). `sendCaveat` is the one place that wording
    lives, and returns `null` when there is nothing to disambiguate.
  -->
  {@const caveat = sendCaveat(trimmed !== "", replyTarget !== null)}
  <div class="shrink-0 border-l-2 border-l-accent bg-accent-soft px-4 py-2">
    <div class="mx-auto flex w-full max-w-[72ch] items-start gap-3">
      <div class="min-w-0 flex-1">
        <!--
          The eyebrow takes `--color-accent`, and that is the measurement
          that made it: on the composer tray the strip's own ground carries
          the "look here" alone, and `--color-accent-soft` over
          `--color-surface-sunken` is **1.059:1 in light** against 1.395:1
          in dark — weaker than the 1.090:1 sheet-on-field step the whole
          depth story rests on (spec §3), i.e. subliminal in exactly the
          theme most people use. An accent eyebrow is a second channel that
          does not depend on that step surviving, and it measures 6.10:1
          light / 5.46:1 dark on this ground. Amber would be louder and is
          forbidden: `--color-signal` means the operator owes someone an
          answer (§3), and they owe this one only to themselves.
        -->
        <p class="font-mono text-label text-accent uppercase">Attached</p>
        <p class="truncate text-ui font-medium text-content" title={view.filename}>{view.filename}</p>
        <p class="mt-0.5 truncate font-mono text-meta text-content-muted">{view.summary}</p>
        {#if caveat}
          <p class="mt-1 text-ui text-content-muted">{caveat}</p>
        {/if}
      </div>
      <button
        type="button"
        onclick={removeStaged}
        aria-label="Remove attachment"
        class="shrink-0 rounded-md border border-border-strong px-2 py-1 text-ui font-medium text-content-muted transition-colors hover:bg-surface hover:text-content"
      >
        Remove
      </button>
    </div>
  </div>
{/if}
<!--
  The strip's ground runs edge to edge, but its contents sit in the same
  centred `72ch` column the timeline uses (spec §6.3.0). Before this, the
  composer spanned the whole pane while the messages above it stopped at
  630px, so on a wide window the input read as belonging to a different
  layout from the conversation — reported from real use on a 1905px display,
  and visible the moment you look for it. The column is the app's reading
  measure; anything that lines up *with* the messages has to share it.
-->
<div
  class="shrink-0 bg-surface-sunken px-4 py-3"
  style="padding-bottom: calc(0.75rem + var(--inset-bottom));"
>
  <!-- `relative` so the mention list can hang above the strip without moving it. -->
  <div class="relative mx-auto flex w-full max-w-[72ch] items-end gap-2">
  <!--
    The attach control, left of the input (design §8).

    **A glyph, not an icon.** Spec §11 ships no icon set and lists the two
    characters in use (`✕`, `›`); one control is not a reason to start one,
    and a paperclip emoji would be a third typeface's worth of colour
    rendering in a monochrome console. `+` in mono is the same vernacular as
    the `›` prompt beside it.

    **A glyph is not a label**, so the accessible name is a real one and the
    glyph is `aria-hidden`. The `title` says the other half — that dropping
    a file works too — which is the only *standing* mention of drag-and-drop
    anywhere; the drop-active state in `+page.svelte` can only be seen while
    a file is already in the air.

    Ghosted while a picker is open rather than fading the label, matching
    Send's own disabled treatment: `hover:bg-surface`, not
    `hover:bg-surface-sunken`, because this sits *on* the sunken tray and a
    sunken hover would be 1.0:1 — the same inversion the room header's
    controls already make.
  -->
  {#if mentionQuery !== null && mentionMatches.length > 0}
    <!--
      Above the composer, not below it: the composer already sits at the
      bottom of the window, and a list opening downwards would open off-screen.
      Absolute so it cannot push the timeline as it grows and shrinks — the
      reading surface must not move while somebody types a name.
    -->
    <ul
      class="absolute bottom-full left-2 z-30 mb-1 max-h-56 w-72 overflow-y-auto rounded-md border border-border bg-surface py-1 shadow-lg"
      role="listbox"
      aria-label="Mention a member"
    >
      {#each mentionMatches as member, index (member.userId)}
        <li>
          <button
            type="button"
            role="option"
            aria-selected={index === mentionCursor}
            onclick={() => chooseMention(member)}
            class="flex w-full items-baseline gap-2 px-3 py-1.5 text-left transition-colors {index ===
            mentionCursor
              ? 'bg-surface-sunken'
              : 'hover:bg-surface-sunken'}"
          >
            <span class="truncate font-sans text-ui text-content">{mentionLabel(member)}</span>
            {#if member.displayName !== null}
              <span class="truncate font-mono text-meta text-content-faint">
                {member.userId}
              </span>
            {/if}
          </button>
        </li>
      {/each}
    </ul>
  {/if}
  <button
    type="button"
    onclick={attach}
    disabled={staging}
    aria-label="Attach a file"
    title="Attach a file — or drop one on the window"
    class="flex shrink-0 items-center justify-center rounded-md px-2.5 py-2 font-mono text-ui-lg text-content-muted transition-colors hover:bg-surface hover:text-content disabled:text-content-faint disabled:hover:bg-transparent"
  >
    <span aria-hidden="true">+</span>
  </button>
  <div
    class="flex min-w-0 flex-1 items-end gap-1.5 rounded-md px-2 py-1 outline-offset-2 transition-colors focus-within:outline focus-within:outline-2 focus-within:outline-accent"
  >
    <span class="shrink-0 pb-1.5 font-mono text-content-faint" aria-hidden="true">›</span>
    <textarea
      bind:this={input}
      bind:value
      onkeydown={handleKeydown}
      onclick={refreshMentionQuery}
      onkeyup={refreshMentionQuery}
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
    Dropping the fill rather than fading it keeps the label readable *and*
    strengthens the inert signal instead of weakening it: an unfilled
    button plainly is not the primary action, whereas a washed-out filled
    one just looks broken.

    **The label is `content-muted`, and the ground is not `surface`.** The
    commit that made this a ghost recorded the label at 4.92:1 — which is
    `content-faint` on `--color-surface`, a ground this button has never
    had. Dropping the fill exposes whatever is behind it, and that is the
    composer tray: `--color-surface-sunken`. Re-measured there by
    compositing the painted stack, `content-faint` is **4.516:1 light /
    4.911:1 dark** — over §9's floor, but in light by 0.016, which is
    rounding, not margin. So the rank moves up one: `content-muted` on the
    same ground measures **7.492:1 light / 9.697:1 dark** — not the 8.2:1
    muted reads on the sheet, for the same reason the faint figure moved:
    the ground here is sunken, and every number about this button has to
    be taken against it.

    Nothing is lost by that. The disabled state was never carried by the
    label's colour — it is carried by the missing accent fill and the
    hairline border that replaces it, which is exactly the argument for
    the ghost in the first place. Fading the label *as well* was belt and
    braces that cost legibility for a signal already fully delivered.

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
    class="flex shrink-0 items-center gap-1.5 rounded-md border border-transparent bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-colors disabled:border-border disabled:bg-transparent disabled:text-content-muted"
  >
    <!--
      The label names its object whenever there is one. This is half of the
      "never ambiguous at the same moment" rule §8 sets — the other half is
      the strip's own line about what happens to the draft text — and it is
      the half that is always on screen: a reader who has scrolled the strip
      out of mind still sees that this button is about a file.
    -->
    {staged ? "Send file" : "Send"}
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
</div>
