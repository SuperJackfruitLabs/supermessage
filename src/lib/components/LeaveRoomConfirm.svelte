<script lang="ts">
  import { onMount } from "svelte";
  import { errorMessage } from "$lib/errorMessage";
  import InlineFailure from "./timeline/InlineFailure.svelte";
  import { leaveTitle } from "./roomInfoView";

  /**
   * "Leave <room>?" — the confirmation leaving asks for, in iOS's words
   * (`RoomInfoPanel.swift:174-196`). Leaving took effect on the tap there until
   * a stray one cost a room, so it is never one click.
   *
   * A native modal `<dialog>` (`showModal`): everything behind it is inert, so
   * neither Tab nor a screen reader reaches the panel while the question is
   * open. Focus opens on Cancel, not on the destructive Leave: Enter on a
   * freshly opened confirm must never leave a room. Escape (the `cancel`
   * event) cancels through `onCancel` rather than closing the dialog itself,
   * so the parent stays the one owner of whether it is open; focus goes back
   * to whatever opened it. While Leave is in flight, Cancel and Escape do
   * nothing, so the panel cannot close mid-call. `onLeave` is a callback so a story can drive
   * it with no Tauri host; when it rejects the dialog stays open and says so.
   */
  export interface Props {
    roomName: string | null;
    onLeave: () => Promise<void>;
    onCancel: () => void;
  }

  let { roomName, onLeave, onCancel }: Props = $props();

  let busy = $state(false);
  let failure = $state<string | null>(null);
  let cancelButton = $state<HTMLButtonElement | null>(null);
  let dialog = $state<HTMLDialogElement | null>(null);

  onMount(() => {
    const opener = document.activeElement;
    dialog?.showModal();
    cancelButton?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  });

  function cancel(): void {
    if (!busy) onCancel();
  }

  async function leave(): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      await onLeave();
    } catch (err) {
      failure = errorMessage(err);
    } finally {
      busy = false;
    }
  }
</script>

<!--
  `<dialog>` has no padding, so a click that lands on the dialog element itself
  is a click on its backdrop.
-->
<dialog
  bind:this={dialog}
  aria-label={leaveTitle(roomName)}
  class="m-auto mt-16 w-full max-w-sm rounded-card border border-border bg-surface p-0 text-content shadow-lg backdrop:bg-scrim"
  oncancel={(e: Event) => {
    e.preventDefault();
    cancel();
  }}
  onclick={(e: MouseEvent) => {
    if (e.target === dialog) cancel();
  }}
>
  <div class="flex flex-col gap-3 p-4">
    <h2 class="text-ui font-medium break-words text-content">{leaveTitle(roomName)}</h2>
    <p class="text-ui text-content-muted">You'll stop receiving its messages.</p>
    {#if failure !== null}<InlineFailure message={failure} />{/if}
    <div class="flex justify-end gap-2">
      <button
        bind:this={cancelButton}
        type="button"
        disabled={busy}
        onclick={cancel}
        class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content disabled:opacity-50"
      >
        Cancel
      </button>
      <button
        type="button"
        disabled={busy}
        onclick={leave}
        class="rounded-control bg-danger px-3 py-2 text-ui text-accent-content transition-colors disabled:opacity-50"
      >
        Leave
      </button>
    </div>
  </div>
</dialog>
