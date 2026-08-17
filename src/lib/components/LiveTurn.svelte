<script lang="ts">
  // An agent's answer as it is being written, before the room has it.
  //
  // **Why this sits outside the timeline.** A streaming message grows its own
  // height continuously, and the virtual list stores measured sizes per key
  // with no way to ask for a remeasure — the fault that clipped a reaction chip
  // on 2026-08-17, but arriving several times a second instead of once. Putting
  // live text inside `VList` would mean fighting that on every delta. Outside
  // it, in ordinary flow between the timeline and the composer, the browser
  // lays it out for free and the virtual list never learns this exists.
  //
  // The seam is honest rather than merely convenient: this is **not history**.
  // It has not been stored in the room, it cannot be replied to, reacted to,
  // searched or scrolled back to, and it disappears the instant the real
  // message arrives. Rendering it among the messages would claim otherwise.
  //
  // It reads as a peer message deliberately — same serif face, same measure —
  // because that is what it is about to become. What marks it as provisional is
  // the label and the muted ink, not a different shape, so the answer does not
  // appear to jump between two designs when it lands.

  import { liveStore } from "$lib/stores/live.svelte";

  let { roomId }: { roomId: string | null } = $props();

  const text = $derived(liveStore.get(roomId));
</script>

{#if text !== null}
  <div
    class="shrink-0 border-t border-border bg-surface px-4 py-3 lg:px-8"
    role="status"
    aria-live="polite"
    aria-label="The agent is writing"
  >
    <div class="mx-auto w-full max-w-[calc(72ch+2rem)] min-w-0 lg:max-w-[calc(72ch+4rem)]">
      <p class="font-mono text-label uppercase text-content-muted">Writing…</p>
      <!--
        `whitespace-pre-wrap` because an agent's answer arrives with its own
        paragraph breaks and losing them mid-stream would make the text reflow
        when the real message lands, which reads as a flicker at exactly the
        moment the reader is watching most closely.
      -->
      <p class="mt-1 max-w-[68ch] font-serif text-body whitespace-pre-wrap text-content-muted">
        {text}
      </p>
    </div>
  </div>
{/if}
