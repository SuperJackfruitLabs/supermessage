import { describe, expect, it, vi } from "vitest";
import { createMicGesture, type Timers } from "./micGesture";

function rig() {
  let pending: (() => void) | null = null;
  const timers: Timers = {
    set: (fn) => ((pending = fn), 1),
    clear: () => (pending = null),
  };
  const h = { tapped: vi.fn(), holdBegan: vi.fn(), holdEnded: vi.fn() };
  const g = createMicGesture(h, timers);
  return { g, h, fire: () => pending?.() };
}

describe("micGesture", () => {
  it("a quick press is a tap", () => {
    const { g, h, fire } = rig();
    g.down(100);
    g.up(100);
    fire();
    expect(h.tapped).toHaveBeenCalledTimes(1);
    expect(h.holdBegan).not.toHaveBeenCalled();
  });
  it("a press held 300 ms records held and release sends", () => {
    const { g, h, fire } = rig();
    g.down(100);
    fire();
    expect(h.holdBegan).toHaveBeenCalledTimes(1);
    g.up(90);
    expect(h.holdEnded).toHaveBeenCalledWith(false);
    expect(h.tapped).not.toHaveBeenCalled();
  });
  it("a slide past 80px left cancels, exactly 80 does not (iOS: width < -80)", () => {
    const a = rig();
    a.g.down(200);
    a.fire();
    a.g.up(119);
    expect(a.h.holdEnded).toHaveBeenCalledWith(true);
    const b = rig();
    b.g.down(200);
    b.fire();
    b.g.up(120);
    expect(b.h.holdEnded).toHaveBeenCalledWith(false);
  });
  it("a lost pointer during a hold cancels it", () => {
    const { g, h, fire } = rig();
    g.down(0);
    fire();
    g.abort();
    expect(h.holdEnded).toHaveBeenCalledWith(true);
  });
});
