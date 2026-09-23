<script lang="ts">
  import type { TimelineItem } from "$lib/ipc";

  /**
   * "Seen" / "Seen by N", on the reader's own latest message only.
   *
   * Per `TimelineItemDto::read_by`: no per-message avatar stack, and never
   * shown on anyone else's message. `lastOwnMessageId` is what scopes this
   * to the last own item rather than rendering every item's own `read_by`,
   * so it is passed in — this component checks only that the item IS that
   * one and that somebody else has actually read it.
   *
   * `--color-content-muted`, not the `accent-content/70` this used to
   * carry: that value only made sense against the accent-*filled* own
   * bubble it sat on. The own bubble is `--color-accent-soft` with
   * `--color-content` text now, and white-at-70% on that ground is
   * effectively invisible. Sans, like every other meta line.
   */
  export interface Props {
    item: TimelineItem;
    /** The id of the reader's most recent own message. */
    lastOwnMessageId: string | null;
    /** Own messages align their marker right; peers' align left. */
    alignEnd: boolean;
  }

  let { item, lastOwnMessageId, alignEnd }: Props = $props();
</script>

<!-- `alignEnd`: see `reactionsRow`'s note on why this is a parameter.


  "Seen"/"Seen by N" — the reader's own latest message only, per
  `TimelineItemDto::read_by`'s doc comment (`core::dto`): no per-message
  avatar stack, and never shown on anyone else's message. `lastOwnMessageId`
  (top-of-script) is what scopes this to "the last own item" rather than
  every item's own `read_by` being rendered — the check here only needs to
  confirm this specific item is that one and that at least one other
  member has actually read it yet.
-->
{#if item.id === lastOwnMessageId && item.readBy.length > 0}
  <!--
    `--color-content-muted`, not the `accent-content/70` this used to
    carry: that value only ever made sense against the accent-*filled*
    own bubble it sat on. The own bubble is now `--color-accent-soft`
    with `--color-content` text, and white-at-70% on that ground is
    effectively invisible. Sans, like every other meta line.
  -->
  <p class="mt-1 font-sans text-meta tabular-nums text-content-muted {alignEnd ? 'text-right' : 'text-left'}">
    {item.readBy.length === 1 ? "Seen" : `Seen by ${item.readBy.length}`}
  </p>
{/if}
