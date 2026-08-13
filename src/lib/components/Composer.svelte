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

  import { timelineStore } from "$lib/stores/timeline.svelte";

  let value = $state("");
  let sending = $state(false);

  const trimmed = $derived(value.trim());
  const canSend = $derived(trimmed !== "" && !sending);

  async function send(): Promise<void> {
    if (!canSend) return;
    const body = trimmed;
    sending = true;
    try {
      await timelineStore.send(body);
      value = "";
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
