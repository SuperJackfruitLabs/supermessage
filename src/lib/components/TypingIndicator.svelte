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

  import { typingStore } from "$lib/stores/typing.svelte";
  import { typingIndicatorText } from "./typingView";

  const text = $derived(typingIndicatorText(typingStore.users));
</script>

<div class="flex h-6 shrink-0 items-center px-4 text-xs text-content-muted" aria-live="polite">
  {#if text}
    <span class="truncate">{text}</span>
  {/if}
</div>
