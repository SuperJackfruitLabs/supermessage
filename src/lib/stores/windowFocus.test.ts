import { describe, expect, it } from "vitest";
import { createWindowFocus } from "./windowFocus.svelte";

function fakes(initial: boolean) {
  const win = new EventTarget();
  const doc = Object.assign(new EventTarget(), { has: initial, hasFocus() { return this.has; } });
  return { win, doc };
}

describe("windowFocus", () => {
  it("follows focus and blur, and tells listeners only about a change", () => {
    const { win, doc } = fakes(false);
    const focus = createWindowFocus(win as unknown as Window, doc as unknown as Document);
    const seen: boolean[] = [];
    focus.onChange((f) => seen.push(f));
    const stop = focus.start();
    expect(focus.focused).toBe(false);
    doc.has = true;
    win.dispatchEvent(new Event("focus"));
    win.dispatchEvent(new Event("focus"));
    expect(focus.focused).toBe(true);
    doc.has = false;
    doc.dispatchEvent(new Event("visibilitychange"));
    expect(seen).toEqual([true, false]);
    stop();
    doc.has = true;
    win.dispatchEvent(new Event("focus"));
    expect(seen).toEqual([true, false]);
  });
});
