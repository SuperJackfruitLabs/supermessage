<script lang="ts">
  // Pinned message composer for the focused room. Enter sends, Shift+Enter
  // inserts a newline; the textarea clears only once the send call
  // succeeds, so a failed send leaves the draft in place rather than
  // silently discarding it.
  //
  // Deliberately does not touch `timelineStore.items` — the core appends a
  // local echo to the timeline itself (see `timelineStore`'s module doc
  // comment), which arrives back through the diff stream. Appending here
  // too would render every sent message twice.
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

  import { timelineStore } from "$lib/stores/timeline.svelte";
  import { DraftTracker } from "./draftTracker";

  let { roomId }: { roomId: string } = $props();

  const drafts = new DraftTracker();
  let value = $state("");
  let sending = $state(false);

  // Bookkeeping for the effect below, not a value the template reads —
  // same reasoning as `Timeline.svelte`'s `previousLastId`.
  let previousRoomId: string | null = null;

  $effect(() => {
    if (roomId !== previousRoomId) {
      value = drafts.switchTo(roomId, value);
      previousRoomId = roomId;
    }
  });

  const trimmed = $derived(value.trim());
  const canSend = $derived(trimmed !== "" && !sending);

  async function send(): Promise<void> {
    if (!canSend) return;
    const body = trimmed;
    const sentRoomId = roomId;
    sending = true;
    try {
      await timelineStore.send(body);
      if (roomId === sentRoomId) {
        value = "";
      } else {
        // The reader switched to a different room while this send was in
        // flight. `value` now belongs to that other room — clearing it here
        // would wipe out whatever they've since started typing there. Only
        // the sent room's stored draft needs clearing.
        drafts.setDraftFor(sentRoomId, "");
      }
    } catch (err) {
      console.error("failed to send message", err);
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

<div
  class="flex shrink-0 items-end gap-2 border-t border-border bg-surface px-4 py-3"
  style="padding-bottom: calc(0.75rem + var(--inset-bottom));"
>
  <textarea
    bind:value
    onkeydown={handleKeydown}
    disabled={sending}
    rows="1"
    placeholder="Message…"
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
