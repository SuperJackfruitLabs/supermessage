import { describe, expect, it } from "vitest";
import { message } from "$lib/fixtures";
import type { TimelineDisplayRow } from "../timelineGrouping";
import { menuActions } from "./messageMenu";

type ItemRow = Extract<TimelineDisplayRow, { type: "item" }>;

/** A display row over a fixture message; only the fields the menu reads matter. */
function rowOf(overrides: Record<string, unknown>, canReplyOrReact = true): ItemRow {
  const base = message({ id: "m1", eventId: "$e1", ...overrides });
  return { type: "item", key: "m1", ...base, canReplyOrReact } as unknown as ItemRow;
}

describe("menuActions", () => {
  it("copyOfferedForText", () => {
    expect(menuActions(rowOf({ body: "hi" })).copy).toBe(true);
  });
  it("copyNotOfferedWithoutBody", () => {
    expect(menuActions(rowOf({ body: null })).copy).toBe(false);
    expect(menuActions(rowOf({ body: "" })).copy).toBe(false);
  });
  it("deleteOfferedForOwnReplyable", () => {
    expect(menuActions(rowOf({ isOwn: true })).del).toBe(true);
  });
  it("deleteNotOfferedForOthers", () => {
    expect(menuActions(rowOf({ isOwn: false })).del).toBe(false);
  });
  it("deleteNotOfferedWhenNotReplyable", () => {
    expect(menuActions(rowOf({ isOwn: true }, false)).del).toBe(false);
  });
  it("deleteNotOfferedWithoutEventId", () => {
    expect(menuActions(rowOf({ isOwn: true, eventId: null })).del).toBe(false);
  });
});
