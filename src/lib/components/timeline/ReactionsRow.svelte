<script lang="ts">
  import type { TimelineItem } from "$lib/ipc";

  /**
   * The reactions under a message.
   *
   * A reaction's key is arbitrary sender-controlled text, not necessarily a
   * single emoji — `Reaction.key`'s own doc comment says so — which is why
   * every chip carries `break-words` and the row is allowed to wrap.
   *
   * `interactive` is false where the row is shown but cannot be changed;
   * `alignEnd` is a parameter rather than derived from `item.isOwn` because
   * some callers align a peer's row as though it were the reader's.
   */
  export interface Props {
    item: TimelineItem;
    interactive: boolean;
    alignEnd: boolean;
    /** Adding or removing the reader's own reaction. */
    onToggle: (eventId: string | null, key: string) => void;
  }

  let { item, interactive, alignEnd, onToggle }: Props = $props();
</script>

<!--
  `alignEnd` defaults to `item.isOwn` — an own bubble's affordances hang
  off its right edge — but it is a *parameter*, not a read of `isOwn`,
  because one caller genuinely differs: the dispatch card is left-aligned
  regardless of sender (spec §7), so its rows must be too. See the card's
  call sites. Do not "simplify" this back to `item.isOwn`: `isOwn` is
  account-scoped (`event.sender() == own_user` in the core), so any other
  session signed in as this account can produce an own custom event, and
  a right-hanging row under a left-anchored card is then reachable, not
  hypothetical.

  This row renders *outside* the message container, on the sheet ground,
  tucked under the container's bottom edge — see `messageBlock`. A
  reaction is chrome that acts on a message, not part of it, and this
  file already refuses to mix the two anywhere else. Positive offsets
  rather than a negative one that would overlap the container's edge:
  an overlap only reads as "tucked into the corner" against a container
  that *has* a visible corner, and of the three that call this, only the
  own bubble does — a peer block and the space under a dispatch card
  would just get a chip sitting too close to the text above it.
-->
{#if item.reactions.length > 0}
  <div class="mt-1.5 flex flex-wrap gap-1 {alignEnd ? 'justify-end' : ''}">
    {#each item.reactions as reaction (reaction.key)}
      {@const chipClass = reaction.byMe
        ? "reaction-chip-mine border-accent font-medium text-accent"
        : "border-border bg-surface-sunken text-content-muted hover:border-border-strong hover:text-content"}
      <!--
        `displayReactionKey` caps a reaction key's rendered length (a key
        is arbitrary sender-controlled text, not necessarily one emoji);
        `break-words` guards the chip itself against a long run within
        that cap, same reasoning as the reply excerpt above. `byMe` gets a
        visually distinct style so a reader can tell at a glance which
        chips they've already added to. A real `<button>`, not a `<span>`
        with a click handler, so it's keyboard-operable with an accessible
        name on its own — `aria-pressed` mirrors `byMe` for the same
        reason a toggle button conventionally exposes its own state.
        Clicking never mutates `item.reactions` itself; see this file's
        top-of-script doc comment.

        `font-sans` explicitly: a chip is chrome, and it sits inside a
        message block that sets its own face so its `ch`-based measure
        resolves in the reading face.

        The "mine" fill is `.reaction-chip-mine` (in the style block at
        the foot of this file) rather than a `bg-accent/15` utility, and
        that is a contrast fix. A translucent fill composites against
        whatever happens to be behind it, and this one snippet renders on
        **four** different grounds: `--color-surface` (a peer block),
        `--color-accent-soft` (an own bubble), `--color-surface-raised`
        (a dispatch card) and `--color-signal-soft` (a pending one). The
        tint measured 5.55:1 on the first and 4.26:1 on the second, and
        the fix that branched on `item.isOwn` still left the two card
        grounds unmeasured — where they came in at 5.00:1 resting and
        4.53:1 on hover, under the 5.0:1 bar. Branching per ground does
        not scale and is how this was missed twice. `.reaction-chip-mine`
        instead paints the accent tint over its *own* opaque
        `--color-surface`, so the chip's contrast is a single number on
        every ground it can ever land on, present or future.

        Numbers are composited by the browser, not modelled: Tailwind
        emits `/15` as `color-mix(in oklab, … , transparent)`, so
        anything that reads `getComputedStyle().backgroundColor` and
        expects `rgba()` silently measures the wrong ground. Paint the
        layer stack into a canvas and read the pixel back.
      -->
      <button
        type="button"
        disabled={!interactive}
        onclick={() => onToggle(item.eventId, reaction.key)}
        aria-pressed={reaction.byMe}
        aria-label={`${reaction.displayKey}, ${reaction.count} ${reaction.count === 1 ? "reaction" : "reactions"}${reaction.byMe ? ", including yours" : ""} — toggle`}
        class="rounded-pill border px-2 py-0.5 font-sans text-ui break-words transition-colors disabled:cursor-not-allowed disabled:opacity-60 {chipClass}"
      >
        {reaction.displayKey} {reaction.count}
      </button>
    {/each}
  </div>
{/if}

<style>
/*
  * The "mine" reaction chip's fill — see `reactionsRow`'s comment for why
  * this is here rather than a `bg-accent/15` utility. The short version:
  * one snippet, four possible grounds, and a translucent fill takes its
  * contrast from whichever one it lands on.
  *
  * The trick is one line: an opaque `background-color` with the accent
  * tint painted over it as a `background-image`. `background-image` sits
  * *above* `background-color` on the same element, so the tint composites
  * against `--color-surface` here and never against the ground behind the
  * chip — the chip stops caring what it is sitting on. A `linear-gradient`
  * between two identical colour stops is the standard way to express "a
  * flat layer" as an image; there is no gradient in it.
  *
  * Tokens only, no literal colours (spec §3), and the tint percentages
  * are the measured ones: 15% resting and 20% on hover give accent text
  * 5.60:1 / 5.16:1 in light and 5.55:1 / 5.02:1 in dark, on every ground.
  */
  .reaction-chip-mine {
    background-color: var(--color-surface);
    background-image: linear-gradient(
      color-mix(in oklab, var(--color-accent) 15%, transparent),
      color-mix(in oklab, var(--color-accent) 15%, transparent)
    );
  }

  .reaction-chip-mine:hover {
    background-image: linear-gradient(
      color-mix(in oklab, var(--color-accent) 20%, transparent),
      color-mix(in oklab, var(--color-accent) 20%, transparent)
    );
  }
</style>
