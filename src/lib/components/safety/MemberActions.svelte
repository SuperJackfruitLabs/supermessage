<script lang="ts">
  import { onMount } from "svelte";
  import type { RoomMember } from "$lib/ipc";
  import { blockLabel, memberActionsMessage, memberName, reportLabel, unblockLabel } from "./memberActions";

  /**
   * What can be done about a room member: block or unblock, report (iOS
   * `RoomInfoPanel.swift:238-259`). A native modal `<dialog>`, titled with the name; focus
   * opens on Cancel. The parent owns whether it is open and says a refusal under the list
   * (`onBlock`/`onUnblock` resolve once the attempt is over; a rejection leaves the dialog open).
   */
  export interface Props {
    member: RoomMember;
    blocked: boolean;
    onBlock: () => Promise<void>;
    onUnblock: () => Promise<void>;
    onReport: () => void;
    onCancel: () => void;
  }

  let { member, blocked, onBlock, onUnblock, onReport, onCancel }: Props = $props();

  const name = $derived(memberName(member));
  let busy = $state(false);
  let dialog = $state<HTMLDialogElement | null>(null);
  let cancelButton = $state<HTMLButtonElement | null>(null);

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

  async function run(action: () => Promise<void>): Promise<void> {
    if (busy) return;
    busy = true;
    try {
      await action();
    } catch {
      // The parent says what went wrong; the dialog stays open and usable.
    } finally {
      busy = false;
    }
  }
</script>

<dialog
  bind:this={dialog}
  aria-label={name}
  class="m-auto mt-16 max-h-[90vh] w-full max-w-sm overflow-y-auto rounded-card border border-border bg-surface p-0 text-content shadow-lg backdrop:bg-scrim"
  oncancel={(e: Event) => {
    e.preventDefault();
    cancel();
  }}
  onclick={(e: MouseEvent) => {
    if (e.target === dialog) cancel();
  }}
>
  <div class="flex flex-col gap-3 p-4">
    <h2 class="text-ui font-medium break-words text-content">{name}</h2>
    <p class="text-ui text-content-muted">{memberActionsMessage(blocked, member.isAgent)}</p>
    <div class="flex flex-col gap-1">
      {#if blocked}
        <button
          type="button"
          disabled={busy}
          onclick={() => void run(onUnblock)}
          class="rounded-control px-3 py-2 text-left text-ui text-content transition-colors hover:bg-surface-sunken disabled:opacity-50"
        >
          {unblockLabel(name)}
        </button>
      {:else}
        <button
          type="button"
          disabled={busy}
          onclick={() => void run(onBlock)}
          class="rounded-control px-3 py-2 text-left text-ui text-danger transition-colors hover:bg-surface-sunken disabled:opacity-50"
        >
          {blockLabel(name)}
        </button>
      {/if}
      <button
        type="button"
        disabled={busy}
        onclick={onReport}
        class="rounded-control px-3 py-2 text-left text-ui text-danger transition-colors hover:bg-surface-sunken disabled:opacity-50"
      >
        {reportLabel(name)}
      </button>
    </div>
    <div class="flex justify-end">
      <button
        bind:this={cancelButton}
        type="button"
        disabled={busy}
        onclick={cancel}
        class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content disabled:opacity-50"
      >
        Cancel
      </button>
    </div>
  </div>
</dialog>
