// A port of `core::audio::audio_clock_label`, and nothing else.
//
// A playing note's elapsed time changes several times a second, and the
// webview cannot ask the core for a string at that rate. So the one clock
// format lives here too — and `audioClock.test.ts` reads the very table the
// Rust test reads (`crates/supermessage-core/tests/fixtures/audio-clock.json`),
// so the two cannot drift apart without one of them failing.
//
// The length *at rest* is not formatted here: that is the core's
// `AudioView.lengthLabel`, rounded rather than truncated, and shown as-is.

/**
 * A position as a clock: `m:ss` under an hour, `h:mm:ss` from one.
 * **Truncates** to the whole second — a note 6.9 s in has played six.
 */
export function audioClockLabel(ms: number): string {
  const total = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor(total / 60) % 60;
  const ss = String(total % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}
