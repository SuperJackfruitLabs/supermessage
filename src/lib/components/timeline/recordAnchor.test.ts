import { describe, expect, it } from "vitest";
import { groupTimelineItems } from "../timelineGrouping";
import { recordAnchorKey } from "./recordAnchor";
import { message } from "$lib/fixtures/timeline";
import type { TimelineRow } from "$lib/ipc";

const as = (row: TimelineRow, render: "bubble" | "image" | "customEvent", isOwn = false): TimelineRow =>
  ({
    ...row,
    item: { ...row.item, isOwn },
    view:
      render === "bubble"
        ? { render: "bubble", muted: false, blocks: [] }
        : render === "image"
          ? { render: "image", alt: "a", width: null, height: null, caption: null }
          : { render: "customEvent", view: { status: "placeholder", text: "x" } },
  }) as unknown as TimelineRow;

const rows = (...items: TimelineRow[]) => groupTimelineItems(items);

describe("recordAnchorKey", () => {
  it("the newest agent bubble carries the record", () => {
    const r = rows(as(message("a"), "bubble"), as(message("b"), "bubble"), as(message("c"), "bubble", true));
    expect(recordAnchorKey(r)).toBe(r[1]!.key);
  });

  it("an own message never does", () => {
    expect(recordAnchorKey(rows(as(message("a"), "bubble", true)))).toBeNull();
  });

  it("an image or a card from the agent does not", () => {
    const r = rows(as(message("a"), "bubble"), as(message("b"), "image"), as(message("c"), "customEvent"));
    expect(recordAnchorKey(r)).toBe(r[0]!.key);
  });

  it("is null for no rows", () => {
    expect(recordAnchorKey([])).toBeNull();
  });
});
