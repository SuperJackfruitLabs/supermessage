<script lang="ts">
  // Reacting with anything, not just the six offered inline.
  //
  // A centred panel over the timeline rather than a popover anchored to the
  // message. The timeline is virtualized (`VList`), so growing a row to hold a
  // grid would change its measured height mid-scroll — the one thing that
  // surface is most fragile about. A panel that floats above the list touches
  // no row's geometry at all.
  //
  // The list and the matching live in `emojiPicker.ts`, which is where the
  // parts worth testing are: what "fire" ranks first, what an empty query
  // means, and that the inline six are all reachable here too.

  import { searchEmoji } from "./emojiPicker";

  let {
    onPick,
    onClose,
  }: {
    /** Called with the chosen character. The caller closes and sends. */
    onPick: (emoji: string) => void;
    onClose: () => void;
  } = $props();

  let query = $state("");
  const results = $derived(searchEmoji(query));

  /** Focuses the search box on open, so typing works without a click. */
  function focusOnMount(node: HTMLInputElement) {
    node.focus();
  }
</script>

<!--
  The backdrop is a button so that dismissing by clicking away is reachable
  without a mouse and announced as an action, rather than a div with a click
  handler that assistive technology cannot see.
-->
<div class="fixed inset-0 z-50 flex items-center justify-center p-4">
  <button
    type="button"
    aria-label="Close the emoji picker"
    class="absolute inset-0 bg-black/40"
    onclick={onClose}
  ></button>

  <!--
    `tabindex="-1"` because the dialog itself carries the Escape handler: a
    role with behaviour has to be able to hold focus for that key to reach it,
    even though nothing tabs to it directly (the search box takes focus on
    open).
  -->
  <div
    role="dialog"
    tabindex="-1"
    aria-label="Pick a reaction"
    class="relative z-10 flex max-h-[70vh] w-full max-w-md flex-col gap-3 rounded-lg border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    }}
  >
    <input
      use:focusOnMount
      bind:value={query}
      type="text"
      placeholder="Search reactions…"
      aria-label="Search reactions"
      class="w-full rounded-md border border-border bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none"
    />

    {#if results.length === 0}
      <!--
        An empty grid, said plainly. Falling back to the whole list here would
        read as the search having silently failed.
      -->
      <p class="py-6 text-center text-ui text-content-muted">
        Nothing matches “{query.trim()}”.
      </p>
    {:else}
      <div class="grid grid-cols-8 gap-1 overflow-y-auto">
        {#each results as emoji (emoji.char)}
          <button
            type="button"
            title={emoji.name}
            aria-label={`React with ${emoji.name}`}
            onclick={() => onPick(emoji.char)}
            class="rounded p-1.5 text-xl transition-colors hover:bg-surface-sunken focus:bg-surface-sunken focus:outline-none"
          >
            {emoji.char}
          </button>
        {/each}
      </div>
    {/if}
  </div>
</div>
