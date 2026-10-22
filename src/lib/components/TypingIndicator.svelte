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
  on the sunken ground. It takes no room when nobody is typing; the page's dock row holds
  the spacing. `aria-live="polite"` on a wrapper that is always mounted, so the line is heard
  when it appears.
-->
<div aria-live="polite" class="max-w-full">
  {#if text}
    <div
      in:fade={{ duration: still ? 0 : 220 }}
      class="inline-flex max-w-full items-center gap-1.5 rounded-pill bg-surface-sunken px-2.5 py-[5px] text-meta text-content-muted"
      data-testid="typing-pill"
    >
      <svg class="size-3.5 shrink-0 text-content-faint" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" aria-hidden="true">
        <path d="M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2z" />
      </svg>
      <span class="truncate whitespace-nowrap">{text}</span>
    </div>
  {/if}
</div>
