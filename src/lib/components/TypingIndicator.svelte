<script lang="ts">
  // Who's typing in the focused room, as a floating pill over the bottom of the timeline
  // (iOS `TimelineView.swift:238-257`). Always mounted (not remounted per room, unlike
  // `Timeline`/`RoomInfoPanel`) — `typingStore` already scopes itself to whichever room is
  // focused, so there is no per-instance state a room switch needs to reset.
  //
  // It is an overlay: the page hands it to `Timeline`'s `overlay` slot, which draws it over the
  // list and keeps a constant reserve under the last row. Appearing or going therefore moves
  // nothing in the history. It takes no room itself when nobody is typing. Chrome, not content:
  // no `.selectable` here.
  //
  // The pill is `bg-surface-raised` with a hairline: over the sunken field a sunken capsule would
  // be invisible. The text stays `--color-content-muted`, the quietest line that is still read.

  import { fade } from "svelte/transition";

  /**
   * The finished line, or `null` when no one is typing. Composed by the
   * core (`typing_view`) and handed in by the route; this component only
   * renders it.
   */
  export interface Props {
    text: string | null;
  }

  let { text }: Props = $props();
  const still =
    typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches;
</script>

<!--
  A pill (iOS `TimelineView.swift:238-257`): a speech-bubble glyph, the core's line, one line,
  as a raised capsule. It takes no room when nobody is typing. `aria-live="polite"` on a wrapper that is always mounted, so the line is heard
  when it appears.
-->
<div aria-live="polite" class="max-w-full">
  {#if text}
    <div
      in:fade={{ duration: still ? 0 : 220 }}
      class="pointer-events-auto inline-flex max-w-full items-center gap-1.5 rounded-pill border border-border bg-surface-raised px-2.5 py-[5px] text-meta text-content-muted"
      data-testid="typing-pill"
    >
      <svg class="size-3.5 shrink-0 text-content-faint" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" aria-hidden="true">
        <path d="M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2z" />
      </svg>
      <span class="truncate whitespace-nowrap">{text}</span>
    </div>
  {/if}
</div>
