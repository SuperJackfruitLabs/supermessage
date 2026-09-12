<script lang="ts">
  /**
   * The room pane with nothing selected.
   *
   * Only ever reachable in the wide layout: the collapsed layout shows the
   * roster instead, so it can never show this — see `+page.svelte`'s note
   * on why the two layouts differ here rather than sharing one empty state.
   */
</script>

<div class="flex flex-1 items-center justify-center">
  <p class="text-ui text-content-muted">Choose a room from the roster.</p>
</div>
