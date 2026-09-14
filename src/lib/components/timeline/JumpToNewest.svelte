<script lang="ts">
  /**
   * The route back to the newest message.
   *
   * Its own component so that something can photograph it. Inline in
   * `Timeline.svelte` it was reachable only by scrolling a real list far
   * enough to clear `BOTTOM_THRESHOLD`, which no story can do — and Android
   * had just taught what that costs: the same button there was fixed, and the
   * fix passed 281 tests and 61 frames without one of them looking at it.
   */
  let { onJump }: { onJump: () => void } = $props();
</script>

<button
  type="button"
  onclick={onJump}
  aria-label="Jump to newest"
  class="pointer-events-auto flex h-11 w-11 items-center justify-center rounded-full border border-border bg-surface text-content-muted shadow-overlay transition-colors hover:bg-surface-raised hover:text-content"
>
  <!--
    An inline SVG, not a text arrow. `design-language.md` §7: a glyph alone in
    a control of fixed size must not grow with the reader's text size, and
    Android's version of this button was `Text("↓")` precisely so — it outgrew
    its own button at large `fontScale`.

    The path is Material's `keyboard_arrow_down`, the same glyph Android
    draws, so the two platforms with no system icon set of their own agree.
    iOS keeps `arrow.down`; see platform-parity.md §7.1.

    44px square — `h-11 w-11` — because that is the floor on every platform,
    and iOS's version of this control was found eight points under it.
  -->
  <svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false">
    <path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z" />
  </svg>
</button>
