import { describe, expect, it } from "vitest";
import {
  GLIDE_EASING,
  GLIDE_MS,
  glideDistance,
  glideKeyframes,
  glideOptions,
  shouldGlide,
} from "./glide";

describe("the glide's timing is iOS's", () => {
  it("0.32 s on the curve (0.25, 0.1, 0.25, 1), additive", () => {
    expect(GLIDE_MS).toBe(320);
    expect(GLIDE_EASING).toBe("cubic-bezier(0.25, 0.1, 0.25, 1)");
    expect(glideOptions()).toEqual({ duration: 320, easing: "cubic-bezier(0.25, 0.1, 0.25, 1)", composite: "add" });
  });

  it("animates transform and nothing else", () => {
    const frames = glideKeyframes(40);
    expect(frames).toEqual([{ transform: "translateY(40px)" }, { transform: "translateY(0)" }]);
    for (const f of frames) expect(Object.keys(f)).toEqual(["transform"]);
  });
});

describe("glideDistance", () => {
  it("is how far the scroller moved down, when it moved more than half a pixel", () => {
    expect(glideDistance(100, 140)).toBe(40);
    expect(glideDistance(100, 100.4)).toBe(0);
    expect(glideDistance(100, 100.6)).toBeCloseTo(0.6);
  });
  it("never glides a scroller that moved up", () => {
    expect(glideDistance(140, 100)).toBe(0);
  });
});

describe("shouldGlide", () => {
  const ok = { streaming: true, following: true, reducedMotion: false };
  it("glides while a turn streams at the newest end", () => {
    expect(shouldGlide(ok, 10)).toBe(true);
  });
  it("not without growth", () => expect(shouldGlide(ok, 0)).toBe(false));
  it("not outside a turn", () => expect(shouldGlide({ ...ok, streaming: false }, 10)).toBe(false));
  it("not when the reader has scrolled back", () => expect(shouldGlide({ ...ok, following: false }, 10)).toBe(false));
  it("not under reduced motion", () => expect(shouldGlide({ ...ok, reducedMotion: true }, 10)).toBe(false));
});
