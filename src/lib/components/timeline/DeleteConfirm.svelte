<script lang="ts">
  import { errorMessage } from "$lib/errorMessage";
  import InlineFailure from "./InlineFailure.svelte";

  /**
   * "Delete message?" — the confirmation a redaction asks for, in iOS's words.
   *
   * A redaction is permanent and visible to the room, so it is never one
   * click. The scrim-and-dialog shape is `RecoveryPanel`'s. `onConfirm` is a
   * callback so a story can drive it with no Tauri host; when it rejects the
   * dialog stays open and says so (a failed delete must not look like a
   * deleted one), and the reader can retry or cancel.
   */
  export interface Props {
    onConfirm: () => Promise<void>;
    onCancel: () => void;
  }

  let { onConfirm, onCancel }: Props = $props();

  let busy = $state(false);
  let failure = $state<string | null>(null);

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

<div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button type="button" aria-label="Close" class="absolute inset-0 bg-scrim" onclick={onCancel}
  ></button>

  <div
    role="dialog"
    tabindex="-1"
    aria-label="Delete message?"
    class="relative z-10 flex w-full max-w-sm flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    }}
  >
    <h2 class="text-ui font-medium text-content">Delete message?</h2>
    <p class="text-ui text-content-muted">This removes it for everyone in the room.</p>
    {#if failure !== null}<InlineFailure message={failure} />{/if}
    <div class="flex justify-end gap-2">
      <button
        type="button"
        onclick={onCancel}
        class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        Cancel
      </button>
      <button
        type="button"
        disabled={busy}
        onclick={confirm}
        class="rounded-control bg-danger px-3 py-2 text-ui text-accent-content transition-colors disabled:opacity-50"
      >
        Delete
      </button>
    </div>
  </div>
</div>
