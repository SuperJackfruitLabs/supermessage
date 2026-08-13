// Covers `groupTimelineItems`'s collapsing of consecutive membership items.
// See that module's doc comment for the design (run boundaries, the
// group-by-verb choice, and why keys are anchored on the first item in a
// run).

import { describe, expect, it } from "vitest";
import { groupTimelineItems } from "./timelineGrouping";
import type { TimelineItem } from "$lib/ipc";

function item(overrides: Partial<TimelineItem> & Pick<TimelineItem, "kind" | "id">): TimelineItem {
  return {
    msgtype: null,
    detail: null,
    sender: "@someone:example.org",
    senderDisplayName: null,
    body: null,
    formattedBody: null,
    timestampMs: 1_700_000_000_000,
    isOwn: false,
    sendState: null,
    ...overrides,
  };
}

function membership(id: string, detail: string | null, name: string): TimelineItem {
  return item({ id, kind: "membership", detail, senderDisplayName: name });
}

function message(id: string, body = "hi"): TimelineItem {
  return item({ id, kind: "message", msgtype: "m.text", body });
}

function dateDivider(id: string): TimelineItem {
  return item({ id, kind: "dateDivider", timestampMs: 1_700_000_000_000 });
}

describe("groupTimelineItems", () => {
  it("never mutates or reorders the source array", () => {
    const items = [membership("m1", "joined", "Alice"), message("msg1")];
    const copy = items.slice();
    groupTimelineItems(items);
    expect(items).toEqual(copy);
    expect(items[0]).toBe(copy[0]);
    expect(items[1]).toBe(copy[1]);
  });

  it("passes non-membership items through unchanged, keyed on their own id", () => {
    const msg = message("msg1");
    const rows = groupTimelineItems([msg]);
    expect(rows).toEqual([{ type: "item", key: "msg1", item: msg }]);
  });

  it("wraps a single membership item as a group that reads naturally (not 'and 0 others')", () => {
    const rows = groupTimelineItems([membership("m1", "joined", "Alice")]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ type: "membershipGroup", text: "Alice joined the room" });
  });

  it("collapses a run of several same-verb membership items into one line", () => {
    const items = [
      membership("m1", "joined", "Alice"),
      membership("m2", "joined", "Bob"),
      membership("m3", "joined", "Carol"),
      membership("m4", "joined", "Dave"),
      membership("m5", "joined", "Eve"),
    ];
    const rows = groupTimelineItems(items);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({
      type: "membershipGroup",
      text: "Alice, Bob and 3 others joined the room",
    });
    if (rows[0]!.type === "membershipGroup") {
      expect(rows[0]!.items).toEqual(items);
    }
  });

  it("names exactly two people with singular 'other' for a run of three", () => {
    const rows = groupTimelineItems([
      membership("m1", "joined", "Alice"),
      membership("m2", "joined", "Bob"),
      membership("m3", "joined", "Carol"),
    ]);
    expect(rows[0]).toMatchObject({ text: "Alice, Bob and 1 other joined the room" });
  });

  it("joins exactly two names with 'and', no 'others'", () => {
    const rows = groupTimelineItems([
      membership("m1", "joined", "Alice"),
      membership("m2", "joined", "Bob"),
    ]);
    expect(rows[0]).toMatchObject({ text: "Alice and Bob joined the room" });
  });

  it("a run split by a real message stays two separate groups", () => {
    const msg = message("msg1", "hello");
    const rows = groupTimelineItems([
      membership("m1", "joined", "Alice"),
      membership("m2", "joined", "Bob"),
      msg,
      membership("m3", "joined", "Carol"),
    ]);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toMatchObject({ type: "membershipGroup", text: "Alice and Bob joined the room" });
    expect(rows[1]).toEqual({ type: "item", key: "msg1", item: msg });
    expect(rows[2]).toMatchObject({ type: "membershipGroup", text: "Carol joined the room" });
  });

  it("a run split by a date divider stays two separate groups, and the divider survives untouched", () => {
    const divider = dateDivider("d1");
    const rows = groupTimelineItems([
      membership("m1", "joined", "Alice"),
      divider,
      membership("m2", "joined", "Bob"),
    ]);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toMatchObject({ type: "membershipGroup", text: "Alice joined the room" });
    expect(rows[1]).toEqual({ type: "item", key: "d1", item: divider });
    expect(rows[2]).toMatchObject({ type: "membershipGroup", text: "Bob joined the room" });
  });

  it("does not merge adjacent membership items with different verbs into one misleading sentence", () => {
    const rows = groupTimelineItems([
      membership("m1", "joined", "Alice"),
      membership("m2", "joined", "Bob"),
      membership("m3", "left", "Carol"),
      membership("m4", "joined", "Dave"),
    ]);
    expect(rows).toHaveLength(3);
    expect(rows[0]).toMatchObject({ text: "Alice and Bob joined the room" });
    expect(rows[1]).toMatchObject({ text: "Carol left the room" });
    expect(rows[2]).toMatchObject({ text: "Dave joined the room" });
    // No row's text ever mentions both verbs at once.
    for (const row of rows) {
      if (row.type === "membershipGroup") {
        const mentionsJoined = row.text.includes("joined");
        const mentionsLeft = row.text.includes("left");
        expect(mentionsJoined && mentionsLeft).toBe(false);
      }
    }
  });

  it("keeps the group key anchored to the first item as the run grows (stable for virtua)", () => {
    const base = [membership("m1", "joined", "Alice"), membership("m2", "joined", "Bob")];
    const grown = [...base, membership("m3", "joined", "Carol")];

    const rowsBefore = groupTimelineItems(base);
    const rowsAfter = groupTimelineItems(grown);

    expect(rowsBefore).toHaveLength(1);
    expect(rowsAfter).toHaveLength(1);
    expect(rowsBefore[0]!.key).toBe(rowsAfter[0]!.key);
    expect(rowsBefore[0]!.key).toBe("group:m1");
  });

  it("gives a new run (started after a break) a fresh key, not the interrupted run's key", () => {
    const rows = groupTimelineItems([
      membership("m1", "joined", "Alice"),
      message("msg1"),
      membership("m2", "joined", "Bob"),
    ]);
    const groupKeys = rows.filter((r) => r.type === "membershipGroup").map((r) => r.key);
    expect(groupKeys).toEqual(["group:m1", "group:m2"]);
  });

  it("falls back to a generic verb phrase when detail is missing, and still groups matching nulls together", () => {
    const rows = groupTimelineItems([membership("m1", null, "Alice"), membership("m2", null, "Bob")]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ text: "Alice and Bob updated their membership" });
  });

  it("falls back to the raw sender id when a member has no display name", () => {
    const rows = groupTimelineItems([
      item({ id: "m1", kind: "membership", detail: "left", sender: "@bob:example.org" }),
    ]);
    expect(rows[0]).toMatchObject({ text: "@bob:example.org left the room" });
  });

  it("handles an empty item list", () => {
    expect(groupTimelineItems([])).toEqual([]);
  });

  it("handles a timeline with no membership items at all", () => {
    const items = [message("msg1"), dateDivider("d1"), message("msg2")];
    const rows = groupTimelineItems(items);
    expect(rows).toEqual([
      { type: "item", key: "msg1", item: items[0] },
      { type: "item", key: "d1", item: items[1] },
      { type: "item", key: "msg2", item: items[2] },
    ]);
  });
});
