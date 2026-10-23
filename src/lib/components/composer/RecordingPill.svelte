<script lang="ts">
  import { elapsedLabel } from "$lib/ipc";

  /**
   * What the field becomes while recording: how long, and a way out (iOS
   * `ComposerView.swift:448-500`). A pulsing `danger` dot (still under reduced motion), the
   * elapsed time worded by the core, "Slide left to cancel" while held or "Recording", and
   * "Discard recording" when it was tapped.
   */
  export interface Props {
    startedAt: number;
    held: boolean;
    onDiscard: () => void;
    /** The clock, in epoch milliseconds. Stories pass a fixed one. */
    now?: () => number;
    /** `core::turn_label::elapsed_label`. Stories answer it without a Tauri runtime. */
    elapsed?: (ms: number) => Promise<string>;
    /** Measured once, not ticking: a still frame. */
    still?: boolean;
  }

  let { startedAt, held, onDiscard, now = Date.now, elapsed = elapsedLabel, still = false }: Props = $props();

  let label = $state("");

  $effect(() => {
    const from = startedAt;
    let live = true;
    const tick = () => {
      elapsed(Math.max(0, now() - from))
        .then((t) => {
          if (live) label = t;
        })
        .catch(() => {});
    };
    tick();
    const timer = still ? null : setInterval(tick, 1000);
    return () => {
      live = false;
      if (timer !== null) clearInterval(timer);
    };
  });
</script>

<div class="flex min-h-10 min-w-0 flex-1 items-center gap-2.5 px-2" data-testid="recording-pill">
  <span class="recording-dot size-[9px] shrink-0 rounded-full bg-danger" aria-hidden="true"></span>
  <span class="font-mono text-ui tabular-nums text-content" data-testid="recording-elapsed">{label}</span>
  <span class="min-w-0 truncate text-meta text-content-muted" data-testid="recording-hint">
    {held ? "Slide left to cancel" : "Recording"}
  </span>
  <span class="flex-1"></span>
  {#if !held}
    <button
      type="button"
      onclick={onDiscard}
      aria-label="Discard recording"
      title="Discard recording"
      class="flex shrink-0 items-center justify-center rounded-control px-2.5 py-1.5 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
    >
      <span aria-hidden="true">✕</span>
    </button>
  {/if}
</div>

<style>
  /* The dot pulses unless the reader asked for less motion. */
  @media (prefers-reduced-motion: no-preference) {
    .recording-dot {
      animation: recording-pulse 1.6s ease-in-out infinite;
    }
  }
  @keyframes recording-pulse {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.35;
    }
  }
</style>
