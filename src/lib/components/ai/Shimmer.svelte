<script lang="ts">
  // Text that is being produced, rather than text that is waiting.
  //
  // Ported from svelte-ai-elements' `shimmer`, with its shadcn theme variables
  // mapped onto this app's tokens (`--color-background` → `--color-surface`,
  // `--color-muted-foreground` → `--color-content-muted`) and its unused
  // `runed` import dropped. The mechanism is unchanged: a transparent-clipped
  // background gradient swept across the text, so the glare travels through
  // the glyphs instead of behind them.
  //
  // **Why this rather than `animate-pulse`.** A pulse says "something is
  // happening somewhere"; a sweep says "this specific text is being written",
  // which is the honest claim while an agent streams. It also reads as motion
  // at a glance without pulling the eye the way a fading block does — this sits
  // in the reading column, next to prose someone is trying to read.
  //
  // The sweep is decorative and `prefers-reduced-motion` turns it off, leaving
  // the muted colour it would otherwise animate. Nothing is lost: the text says
  // what it says either way.

  import type { Snippet } from "svelte";
  import type { HTMLAttributes } from "svelte/elements";

  interface Props extends HTMLAttributes<HTMLElement> {
    children: Snippet;
    /** The element to render — a `<p>` in prose, a `<span>` inline. */
    as?: keyof HTMLElementTagNameMap;
    /** Seconds for one sweep. */
    duration?: number;
    /** Width of the glare, as a multiple of `contentLength`. */
    spread?: number;
    /**
     * Roughly how many characters the label is.
     *
     * The glare has to scale with the text or it reads wrong at the extremes:
     * a fixed spread crawls across a long line and washes out a short one.
     */
    contentLength?: number;
  }

  let {
    children,
    as = "p",
    class: className = "",
    duration = 2,
    spread = 2,
    contentLength = 30,
    ...rest
  }: Props = $props();

  const glare = $derived(contentLength * spread);
</script>

<svelte:element
  this={as}
  class="ai-shimmer relative inline-block bg-clip-text text-transparent {className}"
  style="--ai-shimmer-glare: {glare}px; --ai-shimmer-duration: {duration}s;"
  {...rest}
>
  {@render children()}
</svelte:element>

<style>
  .ai-shimmer {
    /*
     * Two layers: the moving glare, and the resting colour beneath it. Both
     * are clipped to the glyphs by `bg-clip-text` with transparent text, so
     * the element paints nothing outside its own letterforms.
     */
    background-image:
      linear-gradient(
        90deg,
        transparent calc(50% - var(--ai-shimmer-glare)),
        var(--color-content),
        transparent calc(50% + var(--ai-shimmer-glare))
      ),
      linear-gradient(var(--color-content-muted), var(--color-content-muted));
    background-repeat: no-repeat, padding-box;
    background-size:
      250% 100%,
      auto;
    background-position: 100% center;
    animation: ai-shimmer var(--ai-shimmer-duration, 2s) linear infinite;
  }

  @keyframes ai-shimmer {
    from {
      background-position: 100% center;
    }
    to {
      background-position: 0% center;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .ai-shimmer {
      animation: none;
      /* Without the sweep there is no reason to clip: paint the text plainly. */
      background-image: none;
      color: var(--color-content-muted);
      -webkit-text-fill-color: var(--color-content-muted);
    }
  }
</style>
