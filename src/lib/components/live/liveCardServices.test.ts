import { afterEach, describe, expect, it, vi } from "vitest";
import { clockOf, ipcServices, systemClock, type LiveCardServices } from "./liveCardServices";
import { storyServices, STORY_NOW, STORY_TURN_START } from "./liveCardStoryServices";
import { elapsedMs } from "./liveCardView";
import type { TurnSummary } from "$lib/ipc";

afterEach(() => vi.useRealTimers());

describe("the live card's clock seam", () => {
  it("reads the wall clock when the services carry none, and keeps ticking", () => {
    vi.useFakeTimers();
    vi.setSystemTime(5_000);
    const now = clockOf(ipcServices);
    expect(now()).toBe(5_000);
    vi.setSystemTime(8_000);
    expect(now()).toBe(8_000);
    expect(systemClock()).toBe(8_000);
  });

  it("reads the services' clock when they carry one, however the wall moves", () => {
    vi.useFakeTimers();
    const pinned: LiveCardServices = { ...ipcServices, now: () => 42 };
    vi.setSystemTime(1_000);
    expect(clockOf(pinned)()).toBe(42);
    vi.setSystemTime(9_000_000);
    expect(clockOf(pinned)()).toBe(42);
  });

  it("pins every story frame to the same elapsed time", () => {
    const s = { startedAtMs: STORY_TURN_START, lastActivityMs: STORY_TURN_START } as TurnSummary;
    expect(clockOf(storyServices)()).toBe(STORY_NOW);
    expect(elapsedMs(s, clockOf(storyServices)(), false)).toBe(3000);
  });
});
