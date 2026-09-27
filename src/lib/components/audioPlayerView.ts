// How an audio message's player is drawn — presentation only.
//
// Everything a reader is *told* about the note (that it is a voice message,
// its length at rest, its waveform, what a screen reader says) is
// `core::audio`'s, and arrives as `AudioView`. What is left here is how this
// host draws those facts while a note plays: how many bars are lit, which
// clock is showing, and where a click on the waveform lands.

import { audioClockLabel } from "$lib/audioClock";

/**
 * The most bars drawn. The core hands over up to 120; at the player's width
 * that would be 1px bars on a half-pixel gap, which a display cannot draw
 * sharply. Averaged down, the same way `core::audio::normalise_waveform`
 * averages a long list down to 120.
 */
export const DRAWN_BARS = 48;

/**
 * What stands in for a missing waveform: an even set of bars, not a shape
 * invented to look like speech (`AudioView.waveform` is `null`).
 */
export const PLACEHOLDER_LEVEL = 0.3;

/** The lowest bar drawn, so silence still reads as part of the note. */
export const MIN_LEVEL = 0.08;

/** How far one arrow key moves a playing note. */
export const SEEK_STEP_MS = 5000;

/** The bars to draw for `waveform`, at most {@link DRAWN_BARS}, each in `MIN_LEVEL..=1`. */
export function drawnBars(waveform: readonly number[] | null, max = DRAWN_BARS): number[] {
  if (waveform === null || waveform.length === 0) {
    return Array.from({ length: max }, () => PLACEHOLDER_LEVEL);
  }
  const n = waveform.length;
  const count = Math.min(n, max);
  return Array.from({ length: count }, (_, bar) => {
    const start = Math.floor((bar * n) / count);
    const end = Math.max(Math.floor(((bar + 1) * n) / count), start + 1);
    let sum = 0;
    for (let i = start; i < end; i++) sum += waveform[i];
    return Math.min(1, Math.max(MIN_LEVEL, sum / (end - start)));
  });
}

/**
 * How many of `count` bars are lit at `progress` (0..1). A bar lights once
 * playback has passed its whole slice, so the first lights after 1/count of
 * the note and the last only at the very end.
 */
export function filledBars(progress: number, count: number): number {
  if (!Number.isFinite(progress) || progress <= 0) return 0;
  if (progress >= 1) return count;
  return Math.floor(progress * count);
}

/** Playback progress 0..1, or 0 when the length is not known. */
export function progressOf(positionMs: number, durationMs: number | null): number {
  if (durationMs === null || durationMs <= 0) return 0;
  return Math.min(1, Math.max(0, positionMs / durationMs));
}

/** Which state the player is in, for one note. */
export type PlayerStatus = "idle" | "loading" | "playing" | "paused" | "error";

/**
 * The clock beside the waveform: the core's length label at rest, the
 * elapsed time once the note has been started — playing, or paused part way.
 * `null` when there is nothing to say (no length, not started).
 */
export function clockText(
  status: PlayerStatus,
  positionMs: number,
  lengthLabel: string | null,
): string | null {
  const started = status === "playing" || (status === "paused" && positionMs > 0);
  return started ? audioClockLabel(positionMs) : lengthLabel;
}

/** Where on the waveform a pointer landed, as a fraction of its width. */
export function pointerFraction(clientX: number, left: number, width: number): number {
  if (width <= 0) return 0;
  return Math.min(1, Math.max(0, (clientX - left) / width));
}

/**
 * The position an arrow key, Home or End moves to — or `null` for a key the
 * slider does not handle, so the event is left alone.
 */
export function keyboardSeekMs(key: string, positionMs: number, durationMs: number): number | null {
  const clamp = (ms: number) => Math.min(durationMs, Math.max(0, ms));
  switch (key) {
    case "ArrowLeft":
    case "ArrowDown":
      return clamp(positionMs - SEEK_STEP_MS);
    case "ArrowRight":
    case "ArrowUp":
      return clamp(positionMs + SEEK_STEP_MS);
    case "Home":
      return 0;
    case "End":
      return durationMs;
    default:
      return null;
  }
}

/** The play button's name for a screen reader. */
export function toggleLabel(status: PlayerStatus, isVoice: boolean): string {
  if (status === "playing") return "Pause";
  if (status === "loading") return isVoice ? "Loading voice message" : "Loading audio";
  return isVoice ? "Play voice message" : "Play audio";
}
