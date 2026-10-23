/**
 * A reported message leaves the timeline (the core marks it hidden), and every action pill can report.
 *
 * Asserted on the source, as `timelineActionAnchor.test.ts` does, because the
 * vitest environment is `node` and the timeline is not mounted in tests.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, test } from "vitest";

const source = readFileSync(fileURLToPath(new URL("./Timeline.svelte", import.meta.url)), "utf8");

describe("Timeline and reports", () => {
  test("rows reported on this device are the core's to hide, not filtered here", () => {
    // The core groups as if a reported row were absent and marks it `hidden`;
    // `groupTimelineItems` skips it (timelineGrouping.test.ts). A host filter
    // before or after grouping would be a second copy of that rule.
    expect(source).toContain("groupTimelineItems(timelineStore.items, expanded");
    expect(source).not.toContain("safetyStore.visible(");
  });

  test("every MessageActions tag carries onReport", () => {
    const tags = source.split("<MessageActions").slice(1).map((chunk) => chunk.slice(0, chunk.indexOf("/>")));
    // Guard the guard: a vacuous pass protects nothing.
    expect(tags.length).toBeGreaterThanOrEqual(2);
    for (const tag of tags) expect(tag).toContain("onReport=");
  });
});
