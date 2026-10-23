<script lang="ts">
  import { DESTINATIONS, destinationLabel, type Destination } from "$lib/stores/destinations.svelte";

  /**
   * The four places the sidebar can show (iOS `SidebarDestinations`): a row of labelled buttons
   * rather than a segmented control, because four segments in a 288px column truncate "Needs you"
   * and the inbox's count needs somewhere to go. Each is a glyph over its title; the selected one
   * is accent on accent-soft. The count is decisions only and sits over the Needs you glyph.
   *
   * Titles never truncate: when the four do not fit side by side the row wraps instead.
   */
  export interface Props {
    selected: Destination;
    badgeCount: number;
    onSelect: (d: Destination) => void;
  }

  let { selected, badgeCount, onSelect }: Props = $props();

  // Line glyphs in `currentColor`: two bubbles, a tray, sparkles, a magnifier.
  const GLYPHS: Record<Destination, string[]> = {
    chats: [
      "M3.5 6.5a3 3 0 0 1 3-3h6a3 3 0 0 1 3 3v3a3 3 0 0 1-3 3H8l-3 2.5v-2.7a3 3 0 0 1-1.5-2.6Z",
      "M9 15.5v.5a2.5 2.5 0 0 0 2.5 2.5H16l3 2.2v-2.4a2.8 2.8 0 0 0 1.5-2.5v-2.8A2.5 2.5 0 0 0 18 12.5h-.5",
    ],
    needsYou: ["M3.5 13.5 6 5.5h12l2.5 8", "M3.5 13.5V18a1.5 1.5 0 0 0 1.5 1.5h14a1.5 1.5 0 0 0 1.5-1.5v-4.5h-5a3.5 3.5 0 0 1-7 0Z"],
    agents: [
      "M10 3.5c.5 4 2 6 6.5 7-4.5 1-6 3-6.5 7-.5-4-2-6-6.5-7 4.5-1 6-3 6.5-7Z",
      "M18 15.5c.2 1.6.9 2.3 2.5 2.7-1.6.4-2.3 1.1-2.5 2.7-.2-1.6-.9-2.3-2.5-2.7 1.6-.4 2.3-1.1 2.5-2.7Z",
    ],
    search: ["M10.5 3.5a7 7 0 1 0 0 14 7 7 0 0 0 0-14Z", "m15.8 15.8 4.7 4.7"],
  };
</script>

<nav aria-label="Destinations" class="flex flex-wrap gap-1 px-[10px] py-1.5">
  {#each DESTINATIONS as d (d.id)}
    {@const on = d.id === selected}
    <button
      type="button"
      data-testid="destination-{d.id}"
      aria-label={destinationLabel(d.id, badgeCount)}
      aria-current={on ? "page" : undefined}
      onclick={() => onSelect(d.id)}
      class="flex min-w-max flex-1 flex-col items-center gap-[3px] rounded-[8px] py-1.5 transition-colors {on
        ? 'bg-accent-soft text-accent'
        : 'text-content-muted hover:bg-surface hover:text-content'}"
    >
      <span class="relative inline-flex" aria-hidden="true">
        <svg
          viewBox="0 0 24 24"
          width="17"
          height="17"
          fill="none"
          stroke="currentColor"
          stroke-width={on ? 2.2 : 1.6}
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          {#each GLYPHS[d.id] as path}<path d={path} />{/each}
        </svg>
        {#if d.id === "needsYou" && badgeCount > 0}
          <span
            data-testid="needs-you-badge"
            class="absolute -top-1.5 -right-2.5 rounded-pill bg-accent px-1 text-meta leading-4 font-semibold tabular-nums text-accent-content"
            >{badgeCount}</span
          >
        {/if}
      </span>
      <span class="whitespace-nowrap text-meta" data-testid="destination-title">{d.title}</span>
    </button>
  {/each}
</nav>
