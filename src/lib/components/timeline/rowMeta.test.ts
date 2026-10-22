import { describe, expect, it } from "vitest";
import {
  dayDivider,
  ownMessageFailed,
  ownMessageSending,
  senderRun,
  message,
} from "$lib/fixtures";
import { groupTimelineItems } from "../timelineGrouping";
import {
  endsRun,
  HIGHLIGHT_MS,
  parentIndex,
  ownMeta,
  readerFaces,
  readersFor,
  revealsTime,
  rowEdgeClass,
  type ItemRow,
} from "./rowMeta";

describe("rowEdgeClass", () => {
  it("rowEdgeClass reserves the first and last rows", () => {
    expect(rowEdgeClass(0, 5)).toBe("pt-4");
    expect(rowEdgeClass(4, 5)).toBe("pb-4");
    expect(rowEdgeClass(2, 5)).toBe("");
    expect(rowEdgeClass(0, 1)).toBe("pt-4 pb-4");
  });
});

function itemRows(rows: Parameters<typeof groupTimelineItems>[0]): ItemRow[] {
  return groupTimelineItems(rows).filter((r): r is ItemRow => r.type === "item");
}

function ownRun(): ItemRow[] {
  return itemRows([
    message({ id: "o1", isOwn: true, sender: "@me:x", eventId: "$1", timestampMs: 1_700_000_000_000 }),
    message({ id: "o2", isOwn: true, sender: "@me:x", eventId: "$2", timestampMs: 1_700_000_030_000 }),
  ]);
}

describe("endsRun", () => {
  it("endsRun is false when the next row continues", () => {
    const rows = groupTimelineItems(senderRun);
    expect(rows[1]).toMatchObject({ continuesRun: true });
    expect(endsRun(rows, 0)).toBe(false);
    expect(endsRun(rows, 1)).toBe(true);
  });
});

describe("ownMeta", () => {
  it("ownMeta shows the time only at the end of a run", () => {
    const [first, last] = ownRun();
    expect(ownMeta(first!, false)).toEqual({ state: null, edited: false, showTime: false });
    expect(ownMeta(last!, true).showTime).toBe(true);
  });

  it("ownMeta always speaks for a failed send", () => {
    const [failed] = itemRows([ownMessageFailed]);
    expect(ownMeta(failed!, false)).toEqual({ state: "Not sent", edited: false, showTime: true });
    const [sending] = itemRows([ownMessageSending]);
    expect(ownMeta(sending!, false)).toMatchObject({ state: "Sending…", showTime: true });
  });
});

describe("revealsTime", () => {
  it("revealsTime skips a peer run start", () => {
    const rows = itemRows(senderRun);
    expect(revealsTime(rows[0]!, false)).toBe(false);
  });

  it("revealsTime offers a peer continuation", () => {
    const rows = itemRows(senderRun);
    expect(revealsTime(rows[1]!, true)).toBe(true);
  });

  it("revealsTime offers an own row whose time is hidden, and skips one that shows it", () => {
    const [first, last] = ownRun();
    expect(revealsTime(first!, false)).toBe(true);
    expect(revealsTime(last!, true)).toBe(false);
  });

  it("revealsTime skips a date divider", () => {
    const [divider] = itemRows([dayDivider]);
    expect(revealsTime(divider!, true)).toBe(false);
  });
});

describe("revealsTime by render", () => {
  const continued = (view: unknown, timestampMs: number | null = 1_700_000_000_000): ItemRow => {
    const [first, second] = itemRows(senderRun);
    void first;
    return { ...second!, item: { ...second!.item, timestampMs }, view } as ItemRow;
  };
  const stub = { blocks: [], muted: false };

  it("revealsTime offers image, mediaFile and audio rows", () => {
    for (const render of ["bubble", "image", "mediaFile", "audio"]) {
      expect(revealsTime(continued({ render, ...stub }), true), render).toBe(true);
    }
  });

  it("revealsTime skips a dispatch card, which shows its own time", () => {
    expect(revealsTime(continued({ render: "customEvent" }), true)).toBe(false);
  });

  it("revealsTime skips a row with no timestamp", () => {
    expect(revealsTime(continued({ render: "bubble", ...stub }, null), true)).toBe(false);
  });
});

describe("readersFor", () => {
  it("readersFor ignores peers' messages", () => {
    const [peer] = itemRows([message({ id: "p", sender: "@a:x", readBy: ["@b:x"] })]);
    const faces = readerFaces([peer!]);
    expect(readersFor(peer!.item, faces)).toEqual([]);
  });

  it("readersFor uses the sender's first message face and falls back for an unseen reader", () => {
    const rows = groupTimelineItems([
      { ...message({ id: "a", sender: "@a:x", senderAvatar: "mxc://x/a" }), senderInitial: "A" },
      message({ id: "me", isOwn: true, sender: "@me:x", readBy: ["@a:x", "@ghost:x"] }),
    ]);
    const own = rows.filter((r): r is ItemRow => r.type === "item")[1]!;
    expect(readersFor(own.item, readerFaces(rows))).toEqual([
      { userId: "@a:x", mxcUri: "mxc://x/a", initial: "A" },
      { userId: "@ghost:x", mxcUri: null, initial: null },
    ]);
  });
});

describe("parentIndex", () => {
  const rows = groupTimelineItems([
    dayDivider,
    message({ id: "m1", eventId: "$one", sender: "@a:x", timestampMs: 1_700_000_000_000 }),
    message({ id: "m2", eventId: "$two", sender: "@b:x", timestampMs: 1_700_000_100_000 }),
  ]);

  it("parentIndex finds the parent by event id", () => {
    expect(parentIndex(rows, "$one")).toBe(1);
    expect(parentIndex(rows, "$two")).toBe(2);
  });

  it("parentIndex is -1 for an unloaded parent", () => {
    expect(parentIndex(rows, "$elsewhere")).toBe(-1);
  });

  it("parentIndex is -1 for null", () => {
    expect(parentIndex(rows, null)).toBe(-1);
  });

  it("HIGHLIGHT_MS is the iOS wash duration", () => {
    expect(HIGHLIGHT_MS).toBe(1400);
  });
});
