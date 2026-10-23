<script lang="ts">
  // "Thought for 12s · 2 steps", inside the agent's answer row once its turn has
  // finished (iOS `Timeline/WhatIDidFooter.swift`). Collapsed: between the
  // agent's name and its text, because that is the order it happened in. Open,
  // the reasoning, then each step with the core's own word for how it ended.
  //
  // The label is the core's (`recordLabel`); this only lays it out.

  import type { TurnStep } from "$lib/ipc";
  import { toolGlyph, type ToolGlyph } from "./liveCardView";

  export interface Props {
    label: string;
    thought: string | null;
    steps: TurnStep[];
  }

  let { label, thought, steps }: Props = $props();
</script>

{#snippet glyph(kind: ToolGlyph)}
  <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="mt-0.5 shrink-0">
    {#if kind === "done"}
      <circle cx="8" cy="8" r="6.25" /><path d="M5.3 8.2l1.9 1.9 3.6-3.9" />
    {:else if kind === "failed"}
      <circle cx="8" cy="8" r="6.25" /><path d="M5.9 5.9l4.2 4.2M10.1 5.9l-4.2 4.2" />
    {:else if kind === "running"}
      <path d="M13.2 6.2A5.4 5.4 0 0 0 3.4 5M2.8 9.8A5.4 5.4 0 0 0 12.6 11" /><path d="M3.2 2.4v3h3M12.8 13.6v-3h-3" />
    {:else}
      <circle cx="8" cy="8" r="6.25" /><path d="M8 4.6V8l2.2 1.4" />
    {/if}
  </svg>
{/snippet}

<!-- Padding 12/6, a sunken ground, at most 600px wide. -->
<details
  data-testid="what-i-did"
  class="what-i-did mb-1 w-full max-w-[600px] rounded-card bg-surface-sunken px-3 py-1.5 font-sans"
>
  <summary class="cursor-pointer text-ui text-content-muted">{label}</summary>
  <div class="mt-1.5 flex flex-col gap-1.5">
    {#if thought}
      <p class="selectable text-ui whitespace-pre-wrap break-words text-content-muted">{thought}</p>
    {/if}
    {#each steps as step (step.id)}
      {@const g = toolGlyph(step.phase)}
      <p class="flex items-start gap-1.5">
        <span class={g === "failed" ? "text-danger" : "text-content-faint"}>{@render glyph(g)}</span>
        <span class="line-clamp-2 min-w-0 flex-1 text-ui text-content-muted">{step.title}</span>
        <span class="shrink-0 text-meta {g === 'failed' ? 'text-danger' : 'text-content-faint'}">{step.statusLabel}</span>
      </p>
    {/each}
  </div>
</details>

<style>
  /* Fades open (opacity only), unless the reader asked for less motion. */
  @media (prefers-reduced-motion: no-preference) {
    .what-i-did[open] > div {
      animation: what-i-did-open 250ms ease-out;
    }
  }
  @keyframes what-i-did-open {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }
</style>
