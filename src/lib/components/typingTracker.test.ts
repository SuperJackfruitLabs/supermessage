import { describe, expect, it } from "vitest";
import { TYPING_SEND_INTERVAL_MS, TypingTracker } from "./typingTracker";

describe("TypingTracker.onType", () => {
  it("sends on the very first keystroke", () => {
    const tracker = new TypingTracker();
    expect(tracker.onType(0)).toBe(true);
  });

  it("does not send again for a keystroke within the throttle interval", () => {
    const tracker = new TypingTracker();
    tracker.onType(0);
    expect(tracker.onType(TYPING_SEND_INTERVAL_MS - 1)).toBe(false);
  });

  it("sends again once the throttle interval has elapsed", () => {
    const tracker = new TypingTracker();
    tracker.onType(0);
    expect(tracker.onType(TYPING_SEND_INTERVAL_MS)).toBe(true);
  });

  it("a burst of keystrokes inside the interval produces exactly one send", () => {
    const tracker = new TypingTracker();
    let sends = 0;
    for (let ms = 0; ms < TYPING_SEND_INTERVAL_MS; ms += 100) {
      if (tracker.onType(ms)) sends += 1;
    }
    expect(sends).toBe(1);
  });

  it("resets the throttle window after an intervening stop", () => {
    const tracker = new TypingTracker();
    tracker.onType(0);
    tracker.onStop();
    // Well within what would have been the old throttle window, but typing
    // was reported stopped in between — a fresh burst must send again
    // immediately rather than waiting out the original window.
    expect(tracker.onType(500)).toBe(true);
  });
});

describe("TypingTracker.onStop", () => {
  it("is a no-op when nothing has been typed yet", () => {
    const tracker = new TypingTracker();
    expect(tracker.onStop()).toBe(false);
  });

  it("sends false once, after typing was active", () => {
    const tracker = new TypingTracker();
    tracker.onType(0);
    expect(tracker.onStop()).toBe(true);
  });

  it("is a no-op on a second consecutive stop", () => {
    const tracker = new TypingTracker();
    tracker.onType(0);
    tracker.onStop();
    expect(tracker.onStop()).toBe(false);
  });
});
