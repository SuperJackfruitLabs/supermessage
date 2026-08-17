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
    media: null,
    customPayload: null,
    timestampMs: 1_700_000_000_000,
    isOwn: false,
    sendState: null,
    replyTo: null,
    edited: false,
    reactions: [],
    readBy: [],
    ...overrides,
  };
}

/** Overrides shape shared by every object-argument fixture builder below. */
type ItemOverrides = Partial<TimelineItem> & Pick<TimelineItem, "id">;

// `membership` and `dateDivider` below keep their original (id, ...) call
// shape for the membership-grouping tests above, and additionally accept a
// single overrides object — the shape the sender-run tests need (they set
// `sender`/`timestampMs` directly, which the positional shape has no room
// for) — via a second overload. Same fixture builder, not a second one.
function membership(id: string, detail: string | null, name: string): TimelineItem;
function membership(overrides: ItemOverrides): TimelineItem;
function membership(idOrOverrides: string | ItemOverrides, detail?: string | null, name?: string): TimelineItem {
  if (typeof idOrOverrides === "string") {
    return item({ id: idOrOverrides, kind: "membership", detail: detail ?? null, senderDisplayName: name ?? null });
  }
  return item({ kind: "membership", ...idOrOverrides });
}

// Same two-shape pattern as `membership`/`dateDivider` above: the original
// positional call (used throughout the membership-grouping tests) plus an
// overrides-object overload (used by the sender-run tests below, which need
// `sender`/`timestampMs` set per-call). Previously duplicated as a second
// `msg()` builder; collapsed into one name per code review.
function message(id: string, body?: string): TimelineItem;
function message(overrides: ItemOverrides): TimelineItem;
function message(idOrOverrides: string | ItemOverrides, body = "hi"): TimelineItem {
  if (typeof idOrOverrides === "string") {
    return item({ id: idOrOverrides, kind: "message", msgtype: "m.text", body });
  }
  return item({ kind: "message", msgtype: "m.text", body: "hi", ...idOrOverrides });
}

function dateDivider(id: string): TimelineItem;
function dateDivider(overrides: ItemOverrides): TimelineItem;
function dateDivider(idOrOverrides: string | ItemOverrides): TimelineItem {
  if (typeof idOrOverrides === "string") {
    return item({ id: idOrOverrides, kind: "dateDivider", timestampMs: 1_700_000_000_000 });
  }
  return item({ kind: "dateDivider", ...idOrOverrides });
}

/** A custom-message item (`kind: "customMessage"`) — spec §7's bordered card. */
function custom(overrides: ItemOverrides): TimelineItem {
  return item({ kind: "customMessage", ...overrides });
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

  it("passes non-membership items through unchanged, keyed on their own id and shape", () => {
    const msg = message("msg1");
    const rows = groupTimelineItems([msg]);
    expect(rows).toEqual([{ type: "item", key: "msg1:0", item: msg, continuesRun: false }]);
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
    expect(rows[1]).toEqual({ type: "item", key: "msg1:0", item: msg, continuesRun: false });
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
    expect(rows[1]).toEqual({ type: "item", key: "d1:0", item: divider, continuesRun: false });
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
      { type: "item", key: "msg1:0", item: items[0], continuesRun: false },
      { type: "item", key: "d1:0", item: items[1], continuesRun: false },
      { type: "item", key: "msg2:0", item: items[2], continuesRun: false },
    ]);
  });
});

describe("sender runs", () => {
  it("marks a second message from the same sender within the window", () => {
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 1_000 }),
      message({ id: "$2", sender: "@a:x", timestampMs: 61_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, true]);
  });

  it("breaks a run past the five-minute window", () => {
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      message({ id: "$2", sender: "@a:x", timestampMs: 5 * 60_000 + 1 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });

  it("breaks a run on a different sender", () => {
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      message({ id: "$2", sender: "@b:x", timestampMs: 1_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });

  it("breaks a run across a date divider", () => {
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      // `sender` set to match the surrounding messages — unrealistic for a
      // real dateDivider item (which always carries `sender: null`) but
      // deliberate here: this isolates the "message-shaped" kind check from
      // the sender check. If the divider defaulted to its own sender, the
      // run would break on the sender mismatch alone and this test would
      // stay green even if the kind check were deleted entirely.
      dateDivider({ id: "d1", sender: "@a:x", timestampMs: 500 }),
      message({ id: "$2", sender: "@a:x", timestampMs: 1_000 }),
    ]);
    const flags = rows.filter((r) => r.type === "item").map((r) => r.type === "item" && r.continuesRun);
    expect(flags).toEqual([false, false, false]);
  });

  it("breaks a run across a membership group", () => {
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      membership({ id: "$m", sender: "@c:x", timestampMs: 500 }),
      message({ id: "$2", sender: "@a:x", timestampMs: 1_000 }),
    ]);
    const last = rows.at(-1)!;
    expect(last.type === "item" && last.continuesRun).toBe(false);
  });

  it("never continues a run from an item with a null sender", () => {
    const rows = groupTimelineItems([
      message({ id: "$1", sender: null, timestampMs: 0 }),
      message({ id: "$2", sender: null, timestampMs: 1_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });

  it("does not continue a run into a dispatch card", () => {
    // A custom event is a bordered object of its own (spec §7); it always
    // carries its own header, so it neither continues nor extends a run.
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 0 }),
      custom({ id: "$2", sender: "@a:x", timestampMs: 1_000 }),
      message({ id: "$3", sender: "@a:x", timestampMs: 2_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false, false]);
  });

  it("continues a run when the current message arrives a second earlier than its predecessor", () => {
    // Real Matrix timelines can produce out-of-order timestamps (local echo
    // vs. server time, federation lag) — a few seconds out of order from
    // the same sender should still read as one continuous run.
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 10_000 }),
      message({ id: "$2", sender: "@a:x", timestampMs: 9_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, true]);
  });

  it("breaks a run when the current message arrives a full day earlier than its predecessor", () => {
    const oneDayMs = 24 * 60 * 60_000;
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 10_000 }),
      message({ id: "$2", sender: "@a:x", timestampMs: 10_000 - oneDayMs }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false]);
  });
});

describe("a row's key encodes its shape, not just its identity", () => {
  // virtua stores item sizes PER KEY (its README's FAQ: "Why my items are
  // squashed... Item sizes are stored per key"). A row whose key is unchanged
  // keeps its measured height even when its content grows — and there is no
  // remeasure method on `VListHandle` to ask for better.
  //
  // That was invisible until the core stopped replaying the SDK's
  // remove-then-readd as a literal removal (`collapse_reinsertions`): the row
  // used to be destroyed and rebuilt on every update, which re-measured it for
  // free. With the row updating in place, a reaction arriving grew the content
  // past the cached height and the chip was painted over by the next row.
  //
  // So the key carries what changes the row's *shape*. Identity alone is not
  // enough; the whole item is far too much, because then every timestamp tick
  // would remount the row.

  it("changes when a reaction arrives, so the row is measured again", () => {
    const before = groupTimelineItems([message({ id: "$a", reactions: [] })]);
    const after = groupTimelineItems([
      message({ id: "$a", reactions: [{ key: "✅", count: 1, byMe: false }] }),
    ]);

    expect(before[0]!.key).not.toBe(after[0]!.key);
  });

  it("is stable when only the body changes, so a row is not remounted for nothing", () => {
    const before = groupTimelineItems([message({ id: "$a", body: "hi" })]);
    const after = groupTimelineItems([message({ id: "$a", body: "hi there" })]);

    expect(before[0]!.key).toBe(after[0]!.key);
  });

  it("still distinguishes two different messages", () => {
    const rows = groupTimelineItems([message({ id: "$a" }), message({ id: "$b" })]);

    expect(rows[0]!.key).not.toBe(rows[1]!.key);
  });
});
