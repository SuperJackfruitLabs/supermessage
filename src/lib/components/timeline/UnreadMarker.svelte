<script lang="ts">
  /**
   * The "New" divider, where the reader's unread messages begin.
   *
   * Accent rather than amber: arriving at unread messages is not something
   * the reader owes anyone an answer to, and `--color-signal` means exactly
   * that. `accent/40` on the rules either side so the word carries and the
   * lines only locate it.
   */
</script>

<div class="flex items-center gap-3 py-2" data-testid="unread-marker">
  <span class="h-px flex-1 bg-accent/40"></span>
  <span class="font-mono text-meta uppercase tracking-wide text-accent">New</span>
  <span class="h-px flex-1 bg-accent/40"></span>
</div>
