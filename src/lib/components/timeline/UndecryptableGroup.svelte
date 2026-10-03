<script lang="ts">
  /**
   * A run of messages this device has no keys for, as one row.
   *
   * Before this, every such message was its own "Encrypted message — this
   * device has no key for it" log line: on a new device a room was a column
   * of identical lines (Krishna's, on 2026-10-03) and nothing on screen said
   * what to do. One row says how many, and offers the one way back —
   * restoring keys with the recovery key — the same pointer the iOS
   * undecryptable card gives ("Encryption recovery in your account can
   * restore older messages").
   *
   * A log row like `LogLine` (centred, meta, faint), because it is about the
   * room's history rather than something someone said; the action is the
   * one difference.
   */
  export interface Props {
    count: number;
    /** Opens Encryption recovery. Absent, the row only explains. */
    onOpenRecovery?: () => void;
  }

  let { count, onOpenRecovery }: Props = $props();

  const text = $derived(
    count === 1
      ? "1 encrypted message this device cannot read yet."
      : `${count} encrypted messages this device cannot read yet.`,
  );
</script>

<div class="flex justify-center py-2">
  <p class="min-w-0 max-w-[68ch] text-center font-sans text-meta break-words text-content-faint">
    {text}
    {#if onOpenRecovery}
      <button
        type="button"
        onclick={onOpenRecovery}
        class="rounded-control px-1 text-accent underline-offset-2 hover:underline"
      >
        Restore with your recovery key
      </button>
    {/if}
  </p>
</div>
