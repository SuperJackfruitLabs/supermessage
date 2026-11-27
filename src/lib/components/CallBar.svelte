<script lang="ts">
  import type { CallView } from "$lib/ipc";
  import {
    HANG_UP_LABEL,
    MUTE_LABEL,
    UNMUTE_LABEL,
    callTitle,
    elapsedLabel,
    stateWord,
  } from "./callView";

  /**
   * The bar while a call runs (design, "SuperMessage desktop (Linux)"):
   *
   *   ● Call with ✦ Ada — Researcher   02:41   Thinking…   [Mute] [Hang up]
   *
   * The state is the agent's own (`superdial.state`, read by the core from the agent's
   * participant only), so a long wait reads as working rather than broken. The clock counts
   * from when we joined, on a one-second timer of its own; `nowMs` pins it for a story.
   */
  export interface Props {
    call: CallView;
    onMute: (muted: boolean) => void;
    onHangUp: () => void;
    /** A fixed "now", for stories and tests; the bar ticks by itself without it. */
    nowMs?: number;
  }

  let { call, onMute, onHangUp, nowMs }: Props = $props();

  let ticking = $state(Date.now());
  $effect(() => {
    if (nowMs !== undefined) return;
    const timer = setInterval(() => (ticking = Date.now()), 1000);
    return () => clearInterval(timer);
  });

  const elapsed = $derived(elapsedLabel(call.startedAtMs, nowMs ?? ticking));
  const word = $derived(stateWord(call));
  const live = $derived(call.phase === "live");
</script>

<div
  role="region"
  aria-label="Call"
  data-testid="call-bar"
  data-agent-state={call.agentState ?? "none"}
  class="flex min-w-0 items-center gap-3 border-b border-border bg-surface-raised px-4 py-2"
>
  <span
    aria-hidden="true"
    class="size-2 shrink-0 rounded-pill {live ? 'bg-ok' : 'bg-content-faint'}"
  ></span>
  <span class="min-w-0 flex-1 truncate text-ui text-content">{callTitle(call)}</span>
  <span class="shrink-0 font-mono text-meta tabular-nums text-content-muted" data-testid="call-elapsed">
    {elapsed}
  </span>
  <span
    class="w-24 shrink-0 truncate text-meta text-content-muted"
    role="status"
    aria-live="polite"
    data-testid="call-state">{word}</span
  >
  <button
    type="button"
    aria-pressed={call.muted}
    disabled={!live}
    onclick={() => onMute(!call.muted)}
    class="shrink-0 rounded-control border border-border px-3 py-1 text-ui text-content transition-colors hover:bg-surface-sunken disabled:opacity-50 {call.muted
      ? 'bg-surface-sunken'
      : ''}"
  >
    {call.muted ? UNMUTE_LABEL : MUTE_LABEL}
  </button>
  <button
    type="button"
    onclick={onHangUp}
    class="shrink-0 rounded-control border border-danger px-3 py-1 text-ui font-medium text-danger transition-colors hover:bg-surface-sunken"
  >
    {HANG_UP_LABEL}
  </button>
</div>
