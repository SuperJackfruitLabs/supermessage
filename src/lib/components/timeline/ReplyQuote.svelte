<script lang="ts">
  import type { ReplyQuoteView } from "$lib/ipc";

  /**
   * The quoted message above a reply.
   *
   * Pure: the core resolved the sender, the excerpt and the quote's own
   * state before it reached here, so this switches on what it was given and
   * decides nothing.
   */
  export interface Props {
    quote: ReplyQuoteView | null;
    isOwn: boolean;
    /**
     * Set when the quoted message is loaded and can be scrolled to; the
     * quote is then a button. Unset, the quote is inert, exactly as before
     * (iOS `TimelineRowView.swift:80-89`).
     */
    onJump?: (() => void) | null;
  }

  // `aria-description` (ARIA 1.3) is not in Svelte's attribute types yet, so
  // it goes in as a spread rather than a literal attribute.
  const HINT = { "aria-description": "Shows the original message" };

  let { quote, isOwn, onJump = null }: Props = $props();
</script>

{#snippet lines(quote: ReplyQuoteView)}
  <!--
    A 2px rail rather than a filled inset, matching the composer's
    "Replying to" strip (spec §6.4) so the same relationship reads the
    same way in both places. No own/peer colour split any more: the own
    bubble is `--color-accent-soft` with `--color-content` text, not the
    accent fill it used to be, so `--color-content-muted` on
    `--color-border` is legible on either ground — the old
    `accent-content` pair would have been near-invisible on both.
  -->
    {#if quote.state === "available"}
      <!--
        `truncate` alone here (no `break-words`): `truncate` is
        `white-space: nowrap` + `text-overflow: ellipsis` + `overflow:
        hidden`, which never wraps in the first place, so `break-words`
        (a wrapping rule) was dead weight on this line — see this file's
        top-of-script doc comment for why `break-words` *does* matter,
        genuinely, on the two lines below that actually allow wrapping.
      -->
      <span class="block truncate font-sans text-label">{quote.sender}</span>
      {#if quote.excerpt}
        <!-- `quote.excerpt` is already truncated in the core
             (`core::timeline::REPLY_EXCERPT_MAX_CHARS`) — `break-words`
             here guards against a long space-free run within that bound,
             not the length itself. See this file's top-of-script doc
             comment. -->
        <span class="mt-0.5 line-clamp-2 block font-sans text-ui break-words">{quote.excerpt}</span>
      {:else if quote.label}
        <!-- The parent loaded but had nothing to quote (redacted, a
             sticker, a poll, undecryptable, ...) — `quote.label` is the
             same short classification text `core::timeline::
             reply_parent_label` computes for it, so this reads with the
             vocabulary `core::item_view::view_for`'s own placeholders already use. Fixes
             the review finding that this used to render as a bare sender
             name with no indication why. -->
        <!-- Sans, and *not* italic: these two lines share the placeholder
             vocabulary, which is never italic — see this file's
             top-of-script doc comment and spec §6.3.

             `faint` only on a peer block; `muted` inside an own bubble.
             The faint rank is defined against the *reading surface*
             (spec §3 checks it there and nowhere else) and it does not
             survive a tinted ground: composited over
             `--color-accent-soft` it measures **4.26:1 light / 3.52:1
             dark**, under the 4.5:1 floor §9 sets, while `muted` on the
             same ground is 7.07 / 6.95. The rank the own bubble gets for
             its secondary text is therefore `muted`, and the same swap
             is made on the two other faint-on-`accent-soft` lines (the
             `edited` marker and the image placeholder below). Measured
             by compositing the layer stack in a canvas — the numbers a
             token-pair calculator gives for `faint` on `surface` (4.92)
             do not describe this ground at all. -->
        <span
          class="mt-0.5 block font-sans text-meta break-words {isOwn
            ? 'text-content-muted'
            : 'text-content-faint'}"
        >
          {quote.label}
        </span>
      {/if}
    {:else}
      <span
        class="block font-sans text-meta break-words {isOwn
          ? 'text-content-muted'
          : 'text-content-faint'}"
      >
        Original message unavailable
      </span>
    {/if}
{/snippet}

{#if quote}
  {#if onJump}
    <!--
      A real button, so Tab reaches it and Enter or Space activates it. It
      wraps only the quote, and the click is not stopped: the rest of the row
      keeps its own interactions. `aria-description` is the hint iOS gives
      ("Shows the original message").
    -->
    <button
      type="button"
      class="mb-1.5 block w-full cursor-pointer rounded-control border-l-2 border-border pl-2 text-left text-content-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      {...HINT}
      onclick={onJump}
    >
      {@render lines(quote)}
    </button>
  {:else}
    <div class="mb-1.5 border-l-2 border-border pl-2 text-content-muted">
      {@render lines(quote)}
    </div>
  {/if}
{/if}
