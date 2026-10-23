<script lang="ts">
  /** The composer's refusal line: an eyebrow naming it, and the words. */
  let { label, message }: { label: string; message: string } = $props();
</script>

<div class="shrink-0 bg-surface-sunken px-4 py-2">
  <div class="mx-auto flex w-full max-w-[72ch] flex-col gap-0.5">
    <span class="text-label text-danger">{label}</span>
    <p class="selectable text-ui text-content" role="alert">{message}</p>
  </div>
</div>
