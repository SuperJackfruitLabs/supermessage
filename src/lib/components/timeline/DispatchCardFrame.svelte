<script lang="ts">
  import type { Snippet } from "svelte";

  /**
   * The reading measure the timeline wraps a dispatch card in.
   *
   * **Story-support, and load-bearing.** `max-w-[68ch]` and `min-w-0` are
   * the card's wrap guard, and they live on this wrapper in
   * `Timeline.svelte` rather than on the card itself. A story that renders
   * the card without them showed a 90-character unbroken field value
   * pushing the card to 1147px instead of 369 — demonstrating the guard's
   * absence while claiming to show it holding.
   *
   * `font-sans` is here too, so `68ch` resolves against the face card
   * values are actually read in and a card is exactly as wide as a peer
   * message.
   */
  let { children }: { children: Snippet } = $props();
</script>

<div class="flex justify-start">
  <div class="group relative min-w-0 max-w-[68ch] flex-1 font-sans text-body text-content">
    {@render children()}
  </div>
</div>
