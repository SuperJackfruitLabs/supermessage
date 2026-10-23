<script lang="ts">
  import AgentLabel from "./AgentLabel.svelte";

  /**
   * The line that heads a peer's message, audio row and failed turn
   * (iOS `TimelineRowView.swift:381-406`): the sender's face at 22, the name
   * (one line, in the sender's colour), "Agent" for an agent, the time and
   * "edited". The name truncates; everything else keeps its width.
   */
  export interface Props {
    /** The attribution: `attributionFor(row)`. */
    name: string;
    /** The full attribution, for the tooltip. */
    title: string;
    initial: string;
    /** A displayable avatar, or null for the initial. */
    face: string | null;
    /** A `var(--color-peer-*)` or undefined. */
    color?: string;
    isAgent: boolean;
    time: string;
    edited: boolean;
  }

  let { name, title, initial, face, color, isAgent, time, edited }: Props = $props();
</script>

<p class="mb-1 flex items-center gap-2 font-sans text-meta tabular-nums text-content-muted" data-testid="sender-header">
  {#if face}
    <img src={face} alt="" class="h-[22px] w-[22px] shrink-0 rounded-pill object-cover" />
  {:else}
    <span
      class="flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-pill bg-surface text-label leading-none font-medium text-content-muted"
      aria-hidden="true">{initial}</span
    >
  {/if}
  <span class="min-w-0 truncate text-label" style:color {title}>{name}</span>
  {#if isAgent}<AgentLabel />{/if}
  <span class="shrink-0 text-content-faint">{time}</span>
  {#if edited}<span class="shrink-0 text-content-faint">edited</span>{/if}
</p>
