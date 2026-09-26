import { describe, expect, test } from "vitest";
import {
  clockText,
  DRAWN_BARS,
  drawnBars,
  filledBars,
  keyboardSeekMs,
  MIN_LEVEL,
  PLACEHOLDER_LEVEL,
  pointerFraction,
  progressOf,
  SEEK_STEP_MS,
  toggleLabel,
} from "./audioPlayerView";

describe("the lit part of the waveform", () => {
  test("nothing is lit before the note starts, everything at its end", () => {
    expect(filledBars(0, 48)).toBe(0);
    expect(filledBars(1, 48)).toBe(48);
    expect(filledBars(1.2, 48)).toBe(48);
    expect(filledBars(-0.1, 48)).toBe(0);
    expect(filledBars(NaN, 48)).toBe(0);
  });

  test("a bar lights once its whole slice has played, not before", () => {
    // 10 bars: bar 1 covers 0–10%.
    expect(filledBars(0.099, 10)).toBe(0);
    expect(filledBars(0.1, 10)).toBe(1);
    expect(filledBars(0.55, 10)).toBe(5);
    expect(filledBars(0.999, 10)).toBe(9);
  });

  test("progress is position over length, and nothing without a length", () => {
    expect(progressOf(3500, 7000)).toBe(0.5);
    expect(progressOf(9000, 7000)).toBe(1);
    expect(progressOf(3500, null)).toBe(0);
    expect(progressOf(3500, 0)).toBe(0);
  });
});

describe("the bars drawn", () => {
  test("a missing waveform is an even placeholder, not an invented shape", () => {
    const bars = drawnBars(null);
    expect(bars).toHaveLength(DRAWN_BARS);
    expect(new Set(bars)).toEqual(new Set([PLACEHOLDER_LEVEL]));
    expect(drawnBars([])).toHaveLength(DRAWN_BARS);
  });

  test("a short waveform is drawn bar for bar, silence kept visible", () => {
    expect(drawnBars([0, 0.5, 1])).toEqual([MIN_LEVEL, 0.5, 1]);
  });

  test("the core's 120 bars are averaged down, keeping their shape", () => {
    const loud = Array.from({ length: 120 }, (_, i) => (i < 60 ? 0.2 : 1));
    const bars = drawnBars(loud);
    expect(bars).toHaveLength(DRAWN_BARS);
    expect(bars[0]).toBeCloseTo(0.2);
    expect(bars[DRAWN_BARS - 1]).toBe(1);
    // The step between the halves stays in the middle.
    expect(bars.filter((b) => b === 1)).toHaveLength(DRAWN_BARS / 2);
  });
});

describe("the clock beside the waveform", () => {
  test("at rest it is the core's length label, as given", () => {
    expect(clockText("idle", 0, "0:07")).toBe("0:07");
    expect(clockText("loading", 0, "0:07")).toBe("0:07");
    expect(clockText("error", 0, "0:07")).toBe("0:07");
    expect(clockText("idle", 0, null)).toBeNull();
  });

  test("once started it is the elapsed time, truncated", () => {
    expect(clockText("playing", 0, "0:07")).toBe("0:00");
    expect(clockText("playing", 3999, "0:07")).toBe("0:03");
    expect(clockText("paused", 3000, "0:07")).toBe("0:03");
  });

  test("paused at the very start is still at rest", () => {
    expect(clockText("paused", 0, "0:07")).toBe("0:07");
  });
});

describe("seeking", () => {
  test("a click lands at its fraction of the waveform, clamped to it", () => {
    expect(pointerFraction(150, 100, 200)).toBe(0.25);
    expect(pointerFraction(50, 100, 200)).toBe(0);
    expect(pointerFraction(400, 100, 200)).toBe(1);
    expect(pointerFraction(150, 100, 0)).toBe(0);
  });

  test("arrow keys step, Home and End jump, other keys are left alone", () => {
    expect(keyboardSeekMs("ArrowRight", 1000, 60000)).toBe(1000 + SEEK_STEP_MS);
    expect(keyboardSeekMs("ArrowUp", 1000, 60000)).toBe(1000 + SEEK_STEP_MS);
    expect(keyboardSeekMs("ArrowLeft", 1000, 60000)).toBe(0);
    expect(keyboardSeekMs("ArrowRight", 58000, 60000)).toBe(60000);
    expect(keyboardSeekMs("Home", 30000, 60000)).toBe(0);
    expect(keyboardSeekMs("End", 30000, 60000)).toBe(60000);
    expect(keyboardSeekMs("Enter", 30000, 60000)).toBeNull();
  });
});

describe("the play button's name", () => {
  test("says what pressing it does", () => {
    expect(toggleLabel("idle", true)).toBe("Play voice message");
    expect(toggleLabel("paused", true)).toBe("Play voice message");
    expect(toggleLabel("playing", true)).toBe("Pause");
    expect(toggleLabel("idle", false)).toBe("Play audio");
    expect(toggleLabel("loading", true)).toBe("Loading voice message");
  });
});
