<script lang="ts">
  import InlineFailure from "./InlineFailure.svelte";

  /**
   * "Request changes" — the reason a gate's rejection-with-feedback carries
   * back to the agent (`DecisionCard.swift:568-578`). The scrim-and-dialog
   * shape is `DeleteConfirm`'s. `onSend` is a callback so a story can drive
   * it with no Tauri host; when it resolves `false` the dialog stays open,
   * keeps what was typed and says so, because a lost reason is worse than a
   * retry. An empty (or whitespace) reason is sent as `null`.
   */
  export interface Props {
    onSend: (comment: string | null) => Promise<boolean>;
    onCancel: () => void;
  }

  let { onSend, onCancel }: Props = $props();

  let text = $state("");
  let busy = $state(false);
  let failed = $state(false);

  async function send(): Promise<void> {
    if (busy) return;
    busy = true;
    failed = false;
    try {
      const trimmed = text.trim();
      failed = !(await onSend(trimmed === "" ? null : trimmed));
    } catch {
      failed = true;
    } finally {
      busy = false;
    }
  }

  /**
   * Lift the dialog to `<body>`. It is opened from inside a virtualised
   * timeline row; any transform or containment on an ancestor would turn
   * `fixed` into "fixed to the row", and a row scrolled out of view unmounts.
   */
  function portal(node: HTMLElement): { destroy(): void } {
    document.body.appendChild(node);
    return { destroy: () => node.remove() };
  }

  /** Focus the field as the dialog opens. */
  function focusOnMount(node: HTMLElement): void {
    node.focus();
  }
</script>

<div use:portal class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button type="button" aria-label="Close" class="absolute inset-0 bg-scrim" onclick={onCancel}
  ></button>

  <div
    role="dialog"
    tabindex="-1"
    aria-label="Request changes"
    class="relative z-10 flex w-full max-w-sm flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onCancel();
    }}
  >
    <h2 class="text-ui font-medium text-content">Request changes</h2>
    <p class="text-ui text-content-muted">
      This goes back to the agent as the reason, so it can pick the work up again.
    </p>
    <textarea
      bind:value={text}
      use:focusOnMount
      rows="3"
      placeholder="What needs changing?"
      aria-label="What needs changing?"
      class="selectable w-full resize-none rounded-control border border-border-strong bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint"
    ></textarea>
    {#if failed}<InlineFailure message="Not sent. Try again." />{/if}
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
        onclick={send}
        class="rounded-control bg-accent px-3 py-2 text-ui text-accent-content transition-colors disabled:cursor-not-allowed disabled:opacity-50"
      >
        Send
      </button>
    </div>
  </div>
</div>
