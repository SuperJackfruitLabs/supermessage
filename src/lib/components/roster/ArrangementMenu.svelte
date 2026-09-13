<script lang="ts">
  import type { RosterView } from "$lib/ipc";

  /**
   * Which arrangement the roster is in, and how to change it.
   *
   * The three options are a fixed vocabulary rather than a prop: they are
   * what `core::roster` arranges by, and a caller that could pass a fourth
   * would be inventing an arrangement the core cannot produce.
   */
  export interface Props {
    view: RosterView;
    onChange: (view: RosterView) => void;
  }

  let { view, onChange }: Props = $props();
</script>

<!--
  The arrangement switcher. Sticky rather than scrolling away: on a desktop
  the roster column is always on screen, so the control that decides what
  it *is* should be too — unlike the phone, where the same control lives in
  the toolbar because there is no column to spare.
-->
<div
  role="radiogroup"
  aria-label="Roster arrangement"
  class="sticky top-0 z-10 flex gap-1 border-b border-border bg-surface px-3 py-2"
>
  {#each [{ id: "recent", label: "Recent" }, { id: "waiting", label: "Waiting" }, { id: "machine", label: "Machine" }] as option (option.id)}
    <button
      type="button"
      role="radio"
      aria-checked={view === option.id}
      onclick={() => onChange(option.id as RosterView)}
      class="flex-1 rounded px-2 py-1 text-label transition-colors {view === option.id
        ? 'bg-surface-raised text-content'
        : 'text-content-muted hover:text-content'}"
    >
      {option.label}
    </button>
  {/each}
</div>
