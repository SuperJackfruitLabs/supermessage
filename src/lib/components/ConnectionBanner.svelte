<script lang="ts">
  // A thin status strip above the two-pane layout. Hidden entirely once the
  // core reports "live" — a banner that's always there just becomes noise.
  //
  // No hardcoded colors: state is conveyed through both text (the label
  // itself, plus the core's own message when it has one) and the danger
  // token for the error case, never color alone.

  import { connectionStore } from "$lib/stores/connection.svelte";
  import type { ConnectionState } from "$lib/ipc";

  /** Labels every non-"live" state; "live" never reaches this component. */
  function labelFor(state: ConnectionState): string {
    switch (state) {
      case "offline":
        return "Offline";
      case "syncing":
        return "Syncing…";
      case "error":
        return "Connection error";
      case "live":
        return "";
    }
  }
</script>

{#if connectionStore.state !== "live"}
  <div
    class="flex shrink-0 items-center justify-center gap-1.5 border-b border-border bg-surface-raised px-4 py-2 text-sm {connectionStore.state ===
    'error'
      ? 'text-danger'
      : 'text-content-muted'}"
    role="status"
  >
    <span class="font-medium">{labelFor(connectionStore.state)}</span>
    {#if connectionStore.message}
      <span class="text-content-muted">— {connectionStore.message}</span>
    {/if}
  </div>
{/if}
