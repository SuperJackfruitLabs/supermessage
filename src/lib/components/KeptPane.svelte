<script lang="ts">
  import type { Snippet } from "svelte";

  /**
   * A pane that stays mounted while it is not the one on screen. Hiding it is `display: none` plus
   * `hidden` and `inert`: it leaves the layout, the tab order and the accessibility tree, and what
   * it holds (the roster's avatar cache, its scroll position, its chip) is still there on return.
   * Unmounting it instead re-reads every avatar over IPC and shows "not read yet" as an empty state.
   */
  let { active, children }: { active: boolean; children: Snippet } = $props();
</script>

<div class="h-full {active ? '' : 'hidden'}" hidden={!active} inert={!active} data-testid="kept-pane" data-active={active}>
  {@render children()}
</div>
