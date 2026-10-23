<script lang="ts">
  import type { RoomStatus } from "$lib/ipc";
  import { statusDotClass, statusPillClass } from "./roomHeaderView";

  /**
   * What an agent's room is doing, as a small pill (iOS `RoomScreen.swift:222-266`).
   * The word is the core's (`roomStatusWord`); the dot and the ground only colour it.
   * Working pulses (opacity) unless the reader asked for less motion. The word is
   * the accessible name, so the dot is hidden from assistive technology.
   */
  export interface Props {
    status: RoomStatus;
    word: string;
    compact?: boolean;
  }

  let { status, word, compact = true }: Props = $props();
  const colours = $derived(statusPillClass(status));
</script>

<span
  class="inline-flex max-w-full items-center rounded-pill {colours.ground} {compact
    ? 'gap-1 px-[7px] py-px text-meta'
    : 'gap-1.5 px-2.5 py-1 text-ui'}"
  data-testid="status-pill"
  data-status={status}
>
  <span
    class="shrink-0 rounded-pill {statusDotClass(status)} {compact ? 'h-1.5 w-1.5' : 'h-2 w-2'}"
    class:status-pulse={status === "working"}
    aria-hidden="true"
  ></span>
  <span class="truncate whitespace-nowrap {colours.word}">{word}</span>
</span>

<style>
  @media (prefers-reduced-motion: no-preference) {
    .status-pulse {
      animation: status-pulse 1.6s ease-in-out infinite;
    }
  }
  @keyframes status-pulse {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.35;
    }
  }
</style>
