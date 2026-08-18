/**
 * A hover action bar must be anchored to the row it belongs to.
 *
 * The bar (the `messageActions` snippet) is positioned
 * `absolute top-full left-0 right-0`, so it resolves against its nearest
 * *positioned* ancestor. The `group` class on a row wrapper is what drives the
 * bar's `group-hover` reveal — so a wrapper marked `group` is, by definition, a
 * row that hosts the bar, and it must also be `relative`.
 *
 * When it isn't, the bar escapes to whatever is positioned further up and
 * stretches across the whole window, far left of the reading column and
 * detached from its own row. That is exactly what happened to the dispatch
 * card: the message row carried `relative`, the turn-activity card's wrapper
 * did not, and its reaction bar rendered out in the gutter.
 *
 * This is asserted on the source rather than a rendered DOM because
 * `vite.config.js` pins `environment: "node"` — the timeline is tested through
 * extracted pure modules, not by mounting components. The bar also lives in a
 * snippet rendered far from the wrappers, so no structural check would see the
 * two together anyway.
 */

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";

const TIMELINE = fileURLToPath(new URL("./Timeline.svelte", import.meta.url));

/** Every `class="group…"` attribute in the file, as written. */
function groupClassAttrs(source: string): string[] {
  return source.split('class="group').slice(1).map((chunk) => {
    const end = chunk.indexOf('"');
    return `group${chunk.slice(0, end)}`;
  });
}

describe("hover action bars are anchored to their row", () => {
  test("the action bar is positioned, so its host must be too", () => {
    // If the bar stops being absolutely positioned, the invariant below is no
    // longer the thing keeping it in place, and this test should be revisited.
    expect(readFileSync(TIMELINE, "utf8")).toContain("absolute top-full");
  });

  test("every group wrapper declares relative", () => {
    const attrs = groupClassAttrs(readFileSync(TIMELINE, "utf8"));

    // Guard the guard: a vacuous pass would protect nothing.
    expect(attrs.length).toBeGreaterThan(0);

    expect(attrs.filter((a) => !/\brelative\b/.test(a))).toEqual([]);
  });
});
