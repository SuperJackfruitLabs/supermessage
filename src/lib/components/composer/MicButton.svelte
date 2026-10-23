<script lang="ts">
  import { createMicGesture, realTimers, type Timers } from "$lib/voice/micGesture";

  /**
   * The mic (iOS `ComposerView.swift:395-440`): a tap records, a press held 300 ms records held
   * (release sends, a slide of 80px left cancels). Shown when the field is empty and nothing is
   * staged. Recording is `danger`, never amber.
   */
  export interface Props {
    /** True while a held recording is in progress: the mic stays under the pointer, in danger. */
    recording?: boolean;
    onTap: () => void;
    onHoldBegan: () => void;
    onHoldEnded: (cancelled: boolean) => void;
    /** Stories pass a fake clock for the hold. */
    timers?: Timers;
  }

  let { recording = false, onTap, onHoldBegan, onHoldEnded, timers }: Props = $props();

  const gesture = createMicGesture(
    {
      tapped: () => onTap(),
      holdBegan: () => onHoldBegan(),
      holdEnded: (cancelled) => onHoldEnded(cancelled),
    },
    // Read lazily: a story may swap the clock.
    {
      set: (fn, ms) => (timers ?? realTimers).set(fn, ms),
      clear: (handle) => (timers ?? realTimers).clear(handle),
    },
  );
</script>

<button
  type="button"
  aria-label="Record voice message"
  title="Record a voice message. Tap, or hold and release to send."
  data-testid="mic-button"
  class="flex size-9 shrink-0 touch-none select-none items-center justify-center rounded-full transition-colors {recording
    ? 'scale-110 bg-danger text-accent-content'
    : 'text-content-muted hover:bg-surface-sunken hover:text-content'}"
  onpointerdown={(e) => {
    if (e.button !== 0) return;
    e.currentTarget.setPointerCapture?.(e.pointerId);
    gesture.down(e.clientX);
  }}
  onpointerup={(e) => gesture.up(e.clientX)}
  onpointercancel={() => gesture.abort()}
  onkeydown={(e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      onTap();
    }
  }}
>
  <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <rect x="9" y="2" width="6" height="12" rx="3" fill={recording ? "currentColor" : "none"} />
    <path d="M5 11a7 7 0 0 0 14 0M12 18v4" />
  </svg>
</button>
