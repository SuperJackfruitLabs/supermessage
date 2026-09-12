<script lang="ts">
  import type { TimelineDisplayRow } from "../timelineGrouping";
  import { QUICK_REACTIONS } from "../emojiPicker";

  type ItemRow = Extract<TimelineDisplayRow, { type: "item" }>;

  /**
   * The hover action bar: reply, and the quick reactions.
   *
   * **This bar is `absolute top-full`, so it resolves against its nearest
   * POSITIONED ancestor.** Any wrapper marked `group` — which is what
   * drives the bar's `group-hover` reveal — must therefore also be
   * `relative`. When one was not, the bar escaped to whatever was
   * positioned further up and stretched across the whole window, far left
   * of the reading column and detached from its own row. That is exactly
   * what happened to the dispatch card.
   *
   * `timelineActionAnchor.test.ts` enforces the pairing, and it now checks
   * this file for the positioning and every file with a `group` wrapper for
   * `relative` — the two halves live in different components since this
   * bar moved out of `Timeline.svelte`.
   */
  export interface Props {
    row: ItemRow;
    /** Own messages align their bar right; peers' align left. */
    alignEnd: boolean;
    /** Which item's reaction picker is open, if any. */
    pickingReactionFor: string | null;
    onStartReply: (row: ItemRow) => void;
    onToggleReaction: (eventId: string | null, key: string) => void;
    onPickReaction: (itemId: string | null) => void;
  }

  let {
    row,
    alignEnd,
    pickingReactionFor,
    onStartReply,
    onToggleReaction,
    onPickReaction,
  }: Props = $props();

  const item = $derived(row.item);
  const interactive = $derived(row.canReplyOrReact);
</script>

<!-- `alignEnd`: see `reactionsRow`'s note on why this is a parameter. -->
{#if interactive}
  <!--
    Chrome, not content — no `.selectable` here (see this file's
    top-of-script comment on user-select discipline), and rendered
    outside the message container on the sheet ground for the same
    reason `reactionsRow` is; see its comment and `messageBlock`'s.

    Faded out until the *row* is hovered or one of these buttons has
    focus (`focus-within`, not `hover` alone), so tabbing through the
    timeline still reaches every button — opacity, never `display:
    none`, keeps them in the tab order the whole time. The `group` that
    drives that hover is on `messageBlock`'s outermost row precisely so
    that it encloses this detached element: on the container, the
    pointer would leave the group the instant it reached the row being
    revealed. `flex-wrap` so six quick reactions plus "Reply" never
    force the row wider than the reading column.

    The negative margin pulls the outermost button's own padding back so
    the row aligns optically with the message container's edge rather
    than sitting indented from it — left edge for a peer block or a
    dispatch card, right edge for an own bubble. `font-sans` for the
    same reason the reaction chips carry it: this is chrome, and the
    column it now sits directly on is set in the reading serif.

    **It is positioned, not laid out**, and that is worth the extra
    mechanism. In normal flow this row reserved 26px on *every* message
    forever — measured in the running app on 2026-08-17: a one-line
    message came to 142px, of which 54px was the bubble and 88px was
    chrome, 26 of it this row sitting at `opacity: 0`. Reserving space
    was what kept the layout from jumping on hover; overlaying the gap
    below the block achieves the same thing for nothing, because that
    gap is the next message's top padding and is empty by construction.

    `pointer-events-none` until revealed so an invisible row cannot eat
    a click meant for the message underneath it, and `top-full` rather
    than a fixed offset so it always sits immediately beneath its own
    block whatever that block contains.

    **It has to fit the gap it overlays**, and that was measured, not
    guessed: with `mt-1` it ended 6px past where the next message's first
    line begins, so hovering one message painted its controls over the
    text of the next. No margin, and the gap below (`pt-6` on the block
    that follows) is 24px against this row's 22px.
  -->
  <div
    class="pointer-events-none absolute top-full right-0 left-0 z-10 flex flex-wrap items-center gap-0.5 font-sans opacity-0 transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100 {alignEnd
      ? '-mr-1.5 justify-end'
      : '-ml-1.5'}"
  >
    <button
      type="button"
      onclick={() => onStartReply(row)}
      class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
    >
      Reply
    </button>
    {#each QUICK_REACTIONS as emoji (emoji)}
      <button
        type="button"
        onclick={() => onToggleReaction(item.eventId, emoji)}
        aria-label={`React with ${emoji}`}
        class="rounded px-1 py-0.5 text-ui transition-colors hover:bg-surface-sunken"
      >
        {emoji}
      </button>
    {/each}
    <!--
      The six above stay the fast path — one click, no panel. This is for
      everything else, which used to be reachable only if somebody else in
      the room had already reacted with it.
    -->
    <button
      type="button"
      onclick={() => onPickReaction(item.id)}
      aria-label="React with another emoji"
      class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
    >
      +
    </button>
  </div>
{/if}
