<script lang="ts">
  /** The composer is editing one of your own messages (iOS `EditStrip`). */
  export interface Props {
    onCancel: () => void;
  }

  let { onCancel }: Props = $props();
</script>

<div class="shrink-0 border-l-2 border-l-accent bg-surface-sunken px-4 py-2">
  <div class="mx-auto flex w-full max-w-[72ch] items-center gap-2">
    <p class="min-w-0 flex-1 truncate text-label text-content-muted">Editing message</p>
    <button
      type="button"
      onclick={onCancel}
      aria-label="Cancel"
      class="shrink-0 rounded px-1.5 py-0.5 text-content-muted transition-colors hover:bg-surface hover:text-content"
    >
      ✕
    </button>
  </div>
</div>
