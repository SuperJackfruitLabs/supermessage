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
   * Focus opens on Cancel, not on the destructive Leave: Enter on a freshly
   * opened confirm must never leave a room. Escape cancels, and focus goes
   * back to whatever opened it. `onLeave` is a callback so a story can drive
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

  onMount(() => {
    const opener = document.activeElement;
    cancelButton?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  });

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

<div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button
    type="button"
    aria-label="Close"
    tabindex="-1"
    class="absolute inset-0 bg-scrim"
    onclick={onCancel}
  ></button>

  <div
    role="dialog"
    aria-modal="true"
    tabindex="-1"
    aria-label={leaveTitle(roomName)}
    class="relative z-10 flex w-full max-w-sm flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onCancel();
      }
    }}
  >
    <h2 class="text-ui font-medium break-words text-content">{leaveTitle(roomName)}</h2>
    <p class="text-ui text-content-muted">You'll stop receiving its messages.</p>
    {#if failure !== null}<InlineFailure message={failure} />{/if}
    <div class="flex justify-end gap-2">
      <button
        bind:this={cancelButton}
        type="button"
        onclick={onCancel}
        class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
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
</div>
