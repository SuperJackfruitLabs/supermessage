<script lang="ts">
  import ContentUnavailable from "./layout/ContentUnavailable.svelte";

  /**
   * The inbox with nothing waiting (iOS `CaughtUpView`): a seal with a check in the ok colour, one
   * bounce 250 ms after it appears, and none for a reader who asked for less motion. The class is
   * decided here as well as in the CSS, so a story can see which it is.
   */
  const reduced =
    typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
</script>

<ContentUnavailable
  role="status"
  testid="caught-up"
  title="You're all caught up"
  description="Decisions and invitations that need you land here."
>
  {#snippet glyph()}
    <svg
      viewBox="0 0 24 24"
      width="56"
      height="56"
      class="text-ok {reduced ? '' : 'seal-bounce'}"
      data-testid="caught-up-seal"
      fill="none"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
    >
      <path
        d="M12 2.5l2.2 1.6 2.7-.1 1 2.5 2.3 1.5-.5 2.7 1.1 2.4-1.8 2 -.3 2.7-2.6.8-1.7 2.1-2.5-1.1-2.5 1.1-1.7-2.1-2.6-.8-.3-2.7-1.8-2 1.1-2.4-.5-2.7 2.3-1.5 1-2.5 2.7.1Z"
      />
      <path d="M8.5 12.2l2.5 2.5 4.6-5" />
    </svg>
  {/snippet}
</ContentUnavailable>

<style>
  @media (prefers-reduced-motion: no-preference) {
    .seal-bounce {
      animation: seal-bounce 450ms ease-out 250ms 1 both;
    }
  }
  @keyframes seal-bounce {
    0%,
    100% {
      transform: scale(1);
    }
    40% {
      transform: scale(1.18);
    }
  }
</style>
