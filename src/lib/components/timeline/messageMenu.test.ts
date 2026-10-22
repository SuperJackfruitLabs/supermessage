import { describe, expect, it } from "vitest";
import { message } from "$lib/fixtures";
import type { TimelineDisplayRow } from "../timelineGrouping";
import { menuActions, menuOpensUp } from "./messageMenu";

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
  it("pendingMessageWithTextStillGetsABarWithCopyOnly", () => {
    // A local echo cannot be replied to or reacted to, but its text can be copied (iOS offers Copy
    // whenever the body is non-empty, whatever `canReplyOrReact` says).
    const a = menuActions(rowOf({ body: "hi", isOwn: true, eventId: null }, false));
    expect(a).toEqual({ react: false, copy: true, edit: false, del: false, report: false, more: true, bar: true });
  });
  it("pendingMessageWithoutTextHasNoBar", () => {
    expect(menuActions(rowOf({ body: null }, false)).bar).toBe(false);
  });
  it("settledMessageGetsReactAndBar", () => {
    const a = menuActions(rowOf({ body: null }, true));
    expect(a.react).toBe(true);
    expect(a.bar).toBe(true);
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

describe("menuActions edit and more", () => {
  it("editOfferedWhenEditable", () => {
    expect(menuActions(rowOf({ editable: true, eventId: "$e" })).edit).toBe(true);
  });
  it("editNotOfferedWhenNotEditable", () => {
    expect(menuActions(rowOf({ isOwn: true, editable: false })).edit).toBe(false);
  });
  it("editNotOfferedWithoutEventId", () => {
    expect(menuActions(rowOf({ editable: true, eventId: null })).edit).toBe(false);
  });
  it("moreOfferedForCopyOnly", () => {
    const a = menuActions(rowOf({ body: "hi", isOwn: false }, false));
    expect(a.more).toBe(true);
    expect(a.react).toBe(false);
  });
  it("noMoreWhenNothingToOffer", () => {
    expect(menuActions(rowOf({ body: null, isOwn: false, sender: null }, true)).more).toBe(false);
  });
});

describe("menuActions report", () => {
  it("reportOfferedOnSomeoneElsesSentMessage", () => {
    expect(menuActions(rowOf({ isOwn: false, sender: "@a:x" })).report).toBe(true);
  });
  it("reportNotOfferedOnYourOwn", () => {
    expect(menuActions(rowOf({ isOwn: true, sender: "@me:x" })).report).toBe(false);
  });
  it("reportNotOfferedWhilePending", () => {
    expect(menuActions(rowOf({ isOwn: false, sender: "@a:x" }, false)).report).toBe(false);
  });
  it("reportNotOfferedWithoutAnEventId", () => {
    expect(menuActions(rowOf({ isOwn: false, sender: "@a:x", eventId: null })).report).toBe(false);
  });
  it("reportNotOfferedWithoutASender", () => {
    expect(menuActions(rowOf({ isOwn: false, sender: null })).report).toBe(false);
  });
  it("reportAloneOpensMore", () => {
    const a = menuActions(rowOf({ body: null, editable: false, isOwn: false, sender: "@a:x" }));
    expect(a).toMatchObject({ copy: false, edit: false, del: false, report: true, more: true });
  });
});

describe("menuOpensUp", () => {
  it("menuOpensUp near the bottom edge", () => {
    expect(menuOpensUp({ top: 880, bottom: 906 }, { top: 60, bottom: 906 }, 120)).toBe(true);
  });
  it("menuOpensDown with room below", () => {
    expect(menuOpensUp({ top: 100, bottom: 126 }, { top: 60, bottom: 906 }, 120)).toBe(false);
  });
  it("menuOpensDown when neither fits", () => {
    expect(menuOpensUp({ top: 100, bottom: 126 }, { top: 60, bottom: 200 }, 120)).toBe(false);
  });
});
