<script lang="ts">
  /**
   * What the composer is replying to.
   *
   * The left accent border is the same device the timeline's reply quote
   * uses, so the two read as the same relationship seen from two sides.
   */
  export interface Props {
    sender: string;
    /** The core's own excerpt of the message being replied to. */
    excerpt: string | null;
    onCancel: () => void;
  }

  let { sender, excerpt, onCancel }: Props = $props();
</script>

<div class="shrink-0 border-l-2 border-l-accent bg-surface-sunken px-4 py-2">
  <div class="mx-auto flex w-full max-w-[72ch] items-start gap-2">
  <div class="min-w-0 flex-1">
    <p class="truncate font-mono text-label text-content-muted uppercase">
      Replying to {sender}
    </p>
    {#if excerpt}
      <p class="mt-0.5 truncate font-serif text-meta text-content-muted">{excerpt}</p>
    {/if}
  </div>
  <button
    type="button"
    onclick={onCancel}
    aria-label="Cancel reply"
    class="shrink-0 rounded px-1.5 py-0.5 text-content-muted transition-colors hover:bg-surface hover:text-content"
  >
    ✕
  </button>
  </div>
</div>
