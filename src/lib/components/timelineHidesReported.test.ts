/**
 * A reported message leaves the timeline at once, and every action pill can report.
 *
 * Asserted on the source, as `timelineActionAnchor.test.ts` does, because the
 * vitest environment is `node` and the timeline is not mounted in tests.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";

const source = readFileSync(fileURLToPath(new URL("./Timeline.svelte", import.meta.url)), "utf8");

describe("Timeline and reports", () => {
  test("rows reported on this device are dropped before grouping", () => {
    expect(source).toContain("groupTimelineItems(safetyStore.visible(");
  });

  test("every MessageActions tag carries onReport", () => {
    const tags = source.split("<MessageActions").slice(1).map((chunk) => chunk.slice(0, chunk.indexOf("/>")));
    // Guard the guard: a vacuous pass protects nothing.
    expect(tags.length).toBeGreaterThanOrEqual(2);
    for (const tag of tags) expect(tag).toContain("onReport=");
  });
});
