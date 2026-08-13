<script lang="ts">
  // Who's typing in the focused room, shown as a single line below the
  // timeline. Always mounted (not remounted per room, unlike `Timeline`/
  // `RoomInfoPanel`) — `typingStore` already scopes itself to whichever room
  // is focused (see `typing.svelte.ts`'s doc comment), so there is no
  // per-instance state here that a room switch needs to reset.
  //
  // Fixed height regardless of content — reserves the space whether or not
  // anyone is typing, so it never shifts the timeline above it when the
  // indicator appears or disappears (this task's brief). Chrome, not
  // content: no `.selectable` here, matching `messageActions`'s discipline
  // in `Timeline.svelte`.
  //
  // `bg-surface-sunken` — the field, not the shell's `--color-surface`.
  // This strip runs the full width of the pane between the timeline's
  // sunken field and the composer's sunken tray, and inheriting `surface`
  // made it a lit bar across that gap: the same "two lit regions" defect
  // `+page.svelte`'s header comment describes, one element lower and
  // blinking in and out as somebody types. The sheet is the pane's only
  // lit surface and it is a column; a bar the width of the pane cannot
  // join it, so this joins the field instead.
  //
  // The text stays `--color-content-faint`. On the sunken ground it
  // measures 4.52:1 light / 4.91:1 dark — over §9's 4.5:1 floor, and the
  // same faint-on-sunken pair the composer's `›` prompt, its placeholder
  // and the info panel's sigils already use. This is the quietest line in
  // the app by design; it is not a control, and unlike the disabled `Send`
  // (see `Composer.svelte`) it has no second channel that would let the
  // rank move up without saying something it does not mean.

  import { typingStore } from "$lib/stores/typing.svelte";
  import { typingIndicatorText } from "./typingView";

  const text = $derived(typingIndicatorText(typingStore.users));
</script>

<!--
  Ground edge to edge, contents in the timeline's own `72ch` column (spec
  §6.3.0) — the same alignment the composer takes, and for the same reason:
  this line names who is typing *in the conversation above it*, so it has to
  start where that conversation starts rather than at the pane's edge.
-->
<div
  class="h-6 shrink-0 bg-surface-sunken px-4 font-mono text-meta text-content-faint"
  aria-live="polite"
>
  <div class="mx-auto flex h-full w-full max-w-[72ch] items-center">
    {#if text}
      <span class="truncate">{text}</span>
    {/if}
  </div>
</div>
