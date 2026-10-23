<script lang="ts">
  import type { RosterFilter, RosterFilterChip } from "$lib/ipc";

  /**
   * All · Unread above the Chats list (iOS `RosterFilterChips`). The chips, their titles and their
   * counts are the core's; this draws them. A count shows only on a chip other than All, and only
   * above zero, but the accessible name always says it: "Unread, 2". A row that scrolls sideways
   * rather than wrapping, so a chip never breaks across two lines at a large text size.
   */
  export interface Props {
    chips: RosterFilterChip[];
    selected: RosterFilter;
    onSelect: (filter: RosterFilter) => void;
  }

  let { chips, selected, onSelect }: Props = $props();
</script>

<div
  role="group"
  aria-label="Filter rooms"
  data-testid="filter-chips"
  class="flex gap-2 overflow-x-auto px-4 py-1.5"
>
  {#each chips as chip (chip.filter)}
    {@const on = chip.filter === selected}
    <button
      type="button"
      data-testid="chip-{chip.filter}"
      aria-pressed={on}
      aria-label="{chip.title}, {chip.count}"
      onclick={() => onSelect(chip.filter)}
      class="flex shrink-0 items-center gap-1 rounded-pill px-3 py-1.5 text-ui whitespace-nowrap transition-colors {on
        ? 'border border-transparent bg-accent font-semibold text-accent-content'
        : 'border border-border bg-surface-raised text-content'}"
    >
      <span>{chip.title}</span>
      {#if chip.filter !== "all" && chip.count > 0}
        <span
          data-testid="chip-count-{chip.filter}"
          class="tabular-nums {on ? 'text-accent-content' : 'text-content-muted'}">{chip.count}</span
        >
      {/if}
    </button>
  {/each}
</div>
