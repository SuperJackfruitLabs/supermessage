<script lang="ts">
  // A thin status strip above the two-pane layout. Hidden entirely once the
  // core reports "live" — a banner that's always there just becomes noise.
  //
  // No hardcoded colors: state is conveyed through both text (the label
  // itself, plus the core's own message when it has one) and the danger
  // token for the error case, never color alone.

  import type { ConnectionState } from "$lib/ipc";

  /**
   * What the core says about the connection, passed in rather than read.
   *
   * This component used to import `connectionStore` directly. It takes the
   * two values it actually reads instead, which is what `AGENTS.md` rule 1
   * describes — the webview receives what the core decided — and what makes
   * the component something a story can hand a fixture to.
   *
   * The route owns the store and passes these down.
   */
  export interface Props {
    state: ConnectionState;
    /** The core's own explanation, when it has one. */
    message?: string | null;
  }

  let { state, message = null }: Props = $props();

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

{#if state !== "live"}
  <div
    class="flex h-6 shrink-0 items-center justify-center gap-1.5 border-b border-border bg-surface-raised px-4"
    role="status"
  >
    <span
      class="font-mono text-label uppercase {state === 'error'
        ? 'text-danger'
        : 'text-content-muted'}"
    >
      {labelFor(state)}
    </span>
    {#if message}
      <span class="text-ui text-content-muted">— {message}</span>
    {/if}
  </div>
{/if}
