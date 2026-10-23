<script lang="ts">
  import { onMount } from "svelte";
  import RichText from "../RichText.svelte";
  import type { RichBlock } from "$lib/ipc";

  /**
   * A long message, whole, in a reading view (iOS `TimelineChrome.swift:192-225`):
   * titled with the attribution, the same renderer in `font-serif text-longread`
   * with the line height 4px more, at most 640px wide, padding 20/16, on the
   * surface, with "Done".
   *
   * A native modal `<dialog>` like `LeaveRoomConfirm`: focus starts on Done,
   * Escape closes through `onClose`. The parent owns whether it is open and
   * puts focus back on whatever opened it (once the dialog is gone).
   */
  export interface Props {
    title: string;
    blocks: RichBlock[];
    onClose: () => void;
  }

  let { title, blocks, onClose }: Props = $props();

  let dialog = $state<HTMLDialogElement | null>(null);
  let done = $state<HTMLButtonElement | null>(null);

  /** Closed natively first, which hands focus back to whoever had it. */
  function close(): void {
    dialog?.close();
    onClose();
  }

  onMount(() => {
    dialog?.showModal();
    done?.focus();
  });
</script>

<dialog
  bind:this={dialog}
  aria-label={title}
  class="m-auto flex max-h-[85vh] w-full max-w-[640px] flex-col rounded-card border border-border bg-surface p-0 text-content shadow-overlay backdrop:bg-scrim"
  oncancel={(e: Event) => {
    e.preventDefault();
    close();
  }}
  onclick={(e: MouseEvent) => {
    if (e.target === dialog) close();
  }}
>
  <div class="flex items-center gap-3 border-b border-border px-4 py-3">
    <h2 class="min-w-0 flex-1 truncate font-sans text-ui font-semibold">{title}</h2>
    <button
      type="button"
      bind:this={done}
      class="shrink-0 rounded-control px-2 py-1 font-sans text-ui font-semibold text-accent focus-visible:outline-2 focus-visible:outline-accent"
      onclick={close}>Done</button
    >
  </div>
  <div class="min-h-0 overflow-y-auto px-5 py-4">
    <div
      class="message-html selectable font-serif text-longread break-words"
      style:line-height="calc(var(--text-longread--line-height) * 1em + 4px)"
      data-testid="long-read-body"
    >
      <RichText {blocks} />
    </div>
  </div>
</dialog>
