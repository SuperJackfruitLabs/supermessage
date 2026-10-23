<script lang="ts">
  /**
   * The first screen of a first launch (iOS `WelcomeView`): the mark, the name, one line about what
   * this is for, and a single way forward. The mark arrives by scale only (0.94 to 1 on a springy
   * curve); it is never faded, so it is fully there on the first frame. None under reduced motion.
   */
  export interface Props {
    onContinue: () => void;
    /** The mark's address. Only a story changes it: Storybook serves its own `/favicon.svg`. */
    markSrc?: string;
  }

  let { onContinue, markSrc = "/favicon.svg" }: Props = $props();
</script>

<main
  class="flex min-h-dvh flex-col items-center bg-surface p-7 text-content"
  style="padding-top: calc(1.75rem + var(--inset-top)); padding-bottom: calc(1.75rem + var(--inset-bottom));"
  data-testid="welcome"
>
  <div class="flex w-full max-w-[460px] flex-1 flex-col items-center">
    <div class="min-h-6 flex-1"></div>
    <img src={markSrc} alt="supermessage" width="132" class="welcome-mark w-[132px]" data-testid="welcome-mark" />
    <h1 class="mt-5 text-2xl font-bold">supermessage</h1>
    <p class="mt-2.5 px-2 text-center text-ui text-content-muted">
      Chat with your team and your agents — and approve their work without leaving the conversation.
    </p>
    <div class="min-h-6 flex-1"></div>
    <button
      type="button"
      data-testid="welcome-continue"
      onclick={onContinue}
      class="w-full rounded-card bg-accent px-4 py-3 text-ui-lg font-semibold text-accent-content transition-opacity hover:opacity-90"
    >
      Sign in
    </button>
  </div>
</main>

<style>
  /* Scale only: an opacity ramp would flash the mark through half-transparent on a quiet screen. */
  @media (prefers-reduced-motion: no-preference) {
    .welcome-mark {
      animation: welcome-mark-in 600ms cubic-bezier(0.34, 1.56, 0.64, 1) both;
    }
  }
  @keyframes welcome-mark-in {
    from {
      transform: scale(0.94);
    }
    to {
      transform: scale(1);
    }
  }
</style>
