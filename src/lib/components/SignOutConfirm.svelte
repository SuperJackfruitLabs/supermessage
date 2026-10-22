<script lang="ts">
  import { onMount } from "svelte";
  import { errorMessage } from "$lib/errorMessage";
  import InlineFailure from "./timeline/InlineFailure.svelte";
  import { SIGN_OUT_FOOTER, signOutTitle } from "./accountView";

  /**
   * "Sign out of <name>?" (`AccountPanel.swift:178-187`). Signing out removes
   * the account and its messages from this device, so it is never one click.
   *
   * The same native modal `<dialog>` as `LeaveRoomConfirm`: everything behind
   * it is inert, focus opens on Cancel rather than the destructive action,
   * and Escape (the `cancel` event) asks the parent to close through
   * `onCancel`. While sign-out is in flight, Cancel and Escape do nothing.
   * `accountName` is null until M3 brings the core's `account` command.
   */
  export interface Props {
    accountName: string | null;
    onConfirm: () => Promise<void>;
    onCancel: () => void;
  }

  let { accountName, onConfirm, onCancel }: Props = $props();

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

  async function confirm(): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      await onConfirm();
    } catch (err) {
      failure = errorMessage(err);
    } finally {
      busy = false;
    }
  }
</script>

<!-- `<dialog>` has no padding, so a click on the element itself is on its backdrop. -->
<dialog
  bind:this={dialog}
  aria-label={signOutTitle(accountName)}
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
    <h2 class="text-ui font-medium break-words text-content">{signOutTitle(accountName)}</h2>
    <p class="text-ui text-content-muted">{SIGN_OUT_FOOTER}</p>
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
        onclick={confirm}
        class="rounded-control bg-danger px-3 py-2 text-ui text-accent-content transition-colors disabled:opacity-50"
      >
        {busy ? "Signing out…" : "Sign out"}
      </button>
    </div>
  </div>
</dialog>
