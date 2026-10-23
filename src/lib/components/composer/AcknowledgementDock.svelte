<script lang="ts">
  import { fly } from "svelte/transition";
  import type { Acknowledgement } from "$lib/ipc";
  import { dockGlyph } from "./ackView";

  /**
   * "Sent to {agent}", then "{agent} is on it…" (iOS `TimelineView.swift:198-236`): a floating
   * capsule above the composer. The words are the core's; this draws the glyph and the capsule.
   * It is a polite live region, so a screen reader hears the change without being interrupted.
   * It enters from below with a fade, and not at all under reduced motion.
   */
  export interface Props {
    ack: Acknowledgement | null;
  }

  let { ack }: Props = $props();
  const glyph = $derived(ack === null ? null : dockGlyph(ack.kind));
  const still =
    typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
</script>

<div role="status" aria-live="polite" aria-atomic="true" class="max-w-full">
  {#if ack !== null && glyph !== null}
    <div
      in:fly={{ y: 8, duration: still ? 0 : 220 }}
      class="pointer-events-auto inline-flex max-w-full items-center gap-[7px] rounded-pill border border-border bg-surface-raised px-3 py-[7px] shadow-overlay"
      data-testid="ack-dock"
      data-kind={ack.kind}
    >
      {#if glyph.glyph === "check"}
        <svg class="size-3.5 shrink-0 {glyph.tone}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" data-testid="ack-check">
          <path d="M3 8.5l3.2 3.2L13 4.8" />
        </svg>
      {:else}
        <span class="ack-ellipsis shrink-0 text-meta {glyph.tone}" aria-hidden="true" data-testid="ack-ellipsis">···</span>
      {/if}
      <span class="truncate whitespace-nowrap text-meta text-content">{ack.text}</span>
    </div>
  {/if}
</div>

<style>
  @media (prefers-reduced-motion: no-preference) {
    .ack-ellipsis {
      animation: ack-steps 1.2s steps(3, end) infinite;
    }
  }
  @keyframes ack-steps {
    0% {
      opacity: 0.3;
    }
    50% {
      opacity: 1;
    }
    100% {
      opacity: 0.3;
    }
  }
</style>
