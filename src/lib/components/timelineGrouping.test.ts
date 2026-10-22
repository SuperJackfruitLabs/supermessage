// Covers `groupTimelineItems`'s walk over the core's grouping fields. The
// rules themselves (which membership changes share a sentence, what a stretch
// says, when a sender run continues) are pinned in Rust, in
// `timeline_grouping.rs`; the fixtures here carry the core's fields set by
// hand, and these tests check only that the host draws what it is given.

import { describe, expect, it } from "vitest";
import type { MembershipPlacement, TimelineRow } from "$lib/ipc";
import { groupTimelineItems, shouldShift } from "./timelineGrouping";
import {
  custom,
  dateDivider,
  item,
  membership,
  message,
} from "$lib/fixtures/timeline";

/** A membership fixture the core has placed: `runText` on a run's first row only. */
function placed(row: TimelineRow, placement: MembershipPlacement): TimelineRow {
  return { ...row, membership: placement };
}

/**
 * Five changes, three sentences, one collapsed stretch, as the core sends
 * them: the stretch line on the first row, each run's sentence on its first.
 */
function fiveChanges(): TimelineRow[] {
  const stretch = { stretchId: "stretch:m1" };
  return [
    placed(membership("m1", "was invited", "Strategy Sam"), {
      runId: "group:m1",
      runText: "Strategy Sam was invited, then joined the room",
      ...stretch,
      stretchText: "5 membership changes · Strategy Sam, Rakesh and 1 other",
    }),
    placed(membership("m2", "joined the room", "Strategy Sam"), { runId: "group:m1", ...stretch }),
    placed(membership("m3", "updated their membership", "Rakesh"), {
      runId: "group:m3",
      runText: "Rakesh updated their membership",
      ...stretch,
    }),
    placed(membership("m4", "left the room", "Annapurna"), {
      runId: "group:m4",
      runText: "Annapurna left the room",
      ...stretch,
    }),
    placed(membership("m5", "left the room", "Annapurna"), { runId: "group:m4", ...stretch }),
  ];
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
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ type: "item", key: "msg1:0", continuesRun: false });
    // The same object, not a copy: `$effect`s elsewhere compare by identity.
    expect(rows[0]!.type === "item" && rows[0]!.item).toBe(msg.item);
  });


  it("draws a placed membership row as its run's sentence, keyed on the run", () => {
    const rows = groupTimelineItems([
      placed(membership("m1", "joined", "Alice"), { runId: "group:m1", runText: "Alice and Bob joined the room" }),
      placed(membership("m2", "joined", "Bob"), { runId: "group:m1" }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ type: "membershipGroup", key: "group:m1", text: "Alice and Bob joined the room" });
    if (rows[0]!.type === "membershipGroup") expect(rows[0]!.items.map((i) => i.id)).toEqual(["m1", "m2"]);
  });

  it("a collapsed stretch shows one line until opened", () => {
    const rows = groupTimelineItems(fiveChanges());
    expect(rows).toEqual([
      {
        type: "membershipStretch",
        key: "stretch:m1",
        text: "5 membership changes · Strategy Sam, Rakesh and 1 other",
      },
    ]);
  });

  it("an opened stretch shows each run's line", () => {
    const rows = groupTimelineItems(fiveChanges(), new Set(["stretch:m1"]));
    expect(rows.map((r) => [r.type, r.key, r.type === "membershipGroup" ? r.text : null])).toEqual([
      ["membershipGroup", "group:m1", "Strategy Sam was invited, then joined the room"],
      ["membershipGroup", "group:m3", "Rakesh updated their membership"],
      ["membershipGroup", "group:m4", "Annapurna left the room"],
    ]);
    const first = rows[0]!;
    expect(first.type === "membershipGroup" && first.items.map((i) => i.id)).toEqual(["m1", "m2"]);
  });

  it("opening one stretch leaves another collapsed", () => {
    const other = fiveChanges().map((row) =>
      placed(
        { ...row, item: { ...row.item, id: `x${row.item.id}` } },
        {
          ...row.membership!,
          runId: row.membership!.runId.replace("m", "xm"),
          stretchId: "stretch:xm1",
        },
      ),
    );
    const rows = groupTimelineItems([...fiveChanges(), message("between"), ...other], new Set(["stretch:m1"]));
    expect(rows.map((r) => r.type)).toEqual([
      "membershipGroup",
      "membershipGroup",
      "membershipGroup",
      "item",
      "membershipStretch",
    ]);
  });

  it("keeps the run's key as it grows (stable for virtua)", () => {
    const first = placed(membership("m1", "joined", "Alice"), { runId: "group:m1", runText: "Alice joined the room" });
    const grown = [
      placed(membership("m1", "joined", "Alice"), { runId: "group:m1", runText: "Alice and Bob joined the room" }),
      placed(membership("m2", "joined", "Bob"), { runId: "group:m1" }),
    ];
    expect(groupTimelineItems([first])[0]!.key).toBe("group:m1");
    expect(groupTimelineItems(grown)[0]!.key).toBe("group:m1");
  });

  it("gives a new run (started after a break) its own key", () => {
    const rows = groupTimelineItems([
      placed(membership("m1", "joined", "Alice"), { runId: "group:m1", runText: "Alice joined the room" }),
      message("msg1"),
      placed(membership("m2", "joined", "Bob"), { runId: "group:m2", runText: "Bob joined the room" }),
    ]);
    expect(rows.filter((r) => r.type === "membershipGroup").map((r) => r.key)).toEqual(["group:m1", "group:m2"]);
  });

  it("draws a membership row the core did not place as the ordinary row it is", () => {
    const rows = groupTimelineItems([membership("m1", "joined", "Alice")]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ type: "item", key: "m1:0" });
  });


  it("drops a membership row the core marked as drawing nothing", () => {
    // A join -> join with no profile change (`MembershipChange::None`). The
    // core decides it is noise by giving it `render: "none"`; grouping must
    // not turn it back into "Krishna updated their membership".
    const noop = { ...membership("m1", "none", "Krishna"), view: { render: "none" } as const };
    expect(groupTimelineItems([noop])).toEqual([]);
  });

  it("does not let a hidden membership row split the run around it", () => {
    const noop = { ...membership("m2", "none", "Krishna"), view: { render: "none" } as const };
    const rows = groupTimelineItems([
      placed(membership("m1", "joined", "Alice"), { runId: "group:m1", runText: "Alice and Bob joined the room" }),
      noop,
      placed(membership("m3", "joined", "Bob"), { runId: "group:m1" }),
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ type: "membershipGroup", text: "Alice and Bob joined the room" });
    if (rows[0]!.type === "membershipGroup") expect(rows[0]!.items.map((i) => i.id)).toEqual(["m1", "m3"]);
  });

  it("drops any other row the core marked as drawing nothing", () => {
    const hidden = { ...message({ id: "$voice", sender: "@a:x", timestampMs: 2_000 }), view: { render: "none" } as const };
    const rows = groupTimelineItems([message({ id: "$text", sender: "@a:x", timestampMs: 1_000 }), hidden]);
    expect(rows.map((r) => r.type === "item" && r.item.id)).toEqual(["$text"]);
  });

  it("continuesRun is read from the row, not worked out from its neighbours", () => {
    // Two messages that the old rule would have joined (same sender, a minute
    // apart): the core said the second does not continue, so it does not.
    // And the reverse: a different sender the core said continues.
    const rows = groupTimelineItems([
      message({ id: "$1", sender: "@a:x", timestampMs: 1_000 }),
      { ...message({ id: "$2", sender: "@a:x", timestampMs: 61_000 }), continuesRun: false },
      { ...message({ id: "$3", sender: "@b:x", timestampMs: 62_000 }), continuesRun: true },
      message({ id: "$4", sender: "@b:x", timestampMs: 63_000 }),
    ]);
    expect(rows.map((r) => r.type === "item" && r.continuesRun)).toEqual([false, false, true, false]);
  });

  it("endsRun is read from the row, not worked out from the row after it", () => {
    // The next row continues by the old rule, but the core said this one ends;
    // and the reverse. An absent field is false (the wire omits it).
    const rows = groupTimelineItems([
      { ...message({ id: "$1", sender: "@a:x", timestampMs: 1_000 }), endsRun: true },
      { ...message({ id: "$2", sender: "@a:x", timestampMs: 2_000 }), continuesRun: true },
      { ...message({ id: "$3", sender: "@a:x", timestampMs: 3_000 }), continuesRun: true, endsRun: true },
    ]);
    expect(rows.map((r) => r.type === "item" && r.endsRun)).toEqual([true, false, true]);
  });

  it("carries the core's attribution fields through unchanged", () => {
    // 2026-10-03: the desktop headed Super Chotu's messages with its raw
    // `@agent_super-chotu:…` id because it read `senderDisplayName` instead
    // of the name the core resolves for every row.
    const source = message("m1", "Hi");
    const named = { ...source, senderName: "Super Chotu (Hermes on Guild)", senderShort: "Super Chotu" };
    expect(groupTimelineItems([named])[0]).toMatchObject({
      senderName: "Super Chotu (Hermes on Guild)",
      senderShort: "Super Chotu",
    });
  });


  it("handles an empty item list", () => {
    expect(groupTimelineItems([])).toEqual([]);
  });

  it("handles a timeline with no membership items at all", () => {
    const items = [message("msg1"), dateDivider("d1"), message("msg2")];
    const rows = groupTimelineItems(items);
    expect(rows).toEqual([
      { type: "item", key: "msg1:0", item: items[0]!.item, view: items[0]!.view, canReplyOrReact: true, replyQuote: null, senderName: "@someone:example.org", senderShort: "@someone:example.org", senderInitial: "?", replyPreview: null, continuesRun: false, endsRun: false },
      { type: "item", key: "d1:0", item: items[1]!.item, view: items[1]!.view, canReplyOrReact: true, replyQuote: null, senderName: "@someone:example.org", senderShort: "@someone:example.org", senderInitial: "?", replyPreview: null, continuesRun: false, endsRun: false },
      { type: "item", key: "msg2:0", item: items[2]!.item, view: items[2]!.view, canReplyOrReact: true, replyQuote: null, senderName: "@someone:example.org", senderShort: "@someone:example.org", senderInitial: "?", replyPreview: null, continuesRun: false, endsRun: false },
    ]);
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
      message({ id: "$a", reactions: [{ key: "✅", displayKey: "✅", count: 1, byMe: false, senders: [] }] }),
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

// `shouldShift` — the value handed to virtua's `shift` prop.
//
// The regression these guard: `shift` used to be hard-coded `true`, which told
// virtua that *every* change happened at the head of the list. Measured in the
// running app on 2026-08-17, one appended message moved every cached offset
// down by its own height (108px), virtua's range computation then pointed at
// the wrong rows, and up to 602px of the 911px viewport painted no row at all
// and stayed that way. The same probe with `shift` off peaked at a 125px
// one-frame transient. See the module doc comment for the contract.
describe("shouldShift", () => {
  it("is off for an appended message — the case that blanked the viewport", () => {
    expect(shouldShift(["$a:0", "$b:0"], ["$a:0", "$b:0", "$c:0"])).toBe(false);
  });

  it("is on for history inserted under a date divider the SDK keeps at the top", () => {
    // Measured 2026-10-03 in "Buddhimaan": paginating back 38 -> 48 rows kept
    // the day's divider `20:0` at index 0 and inserted the older events after
    // it. Anchoring on the divider read that as a change at the tail, `shift`
    // stayed off, and the reader was left looking at the new history instead
    // of what they had been reading.
    const dividers = new Set(["20:0"]);
    expect(
      shouldShift(["20:0", "21:1", "22:0"], ["20:0", "38:1", "39:0", "21:1", "22:0"], dividers),
    ).toBe(true);
  });

  it("still reads an append under a pinned divider as a tail change", () => {
    const dividers = new Set(["20:0"]);
    expect(shouldShift(["20:0", "21:1"], ["20:0", "21:1", "22:0"], dividers)).toBe(false);
  });

  it("is on for back-paginated history, which is what the prop is for", () => {
    expect(shouldShift(["$c:0", "$d:0"], ["$a:0", "$b:0", "$c:0", "$d:0"])).toBe(true);
  });

  it("is off when nothing moved", () => {
    expect(shouldShift(["$a:0", "$b:0"], ["$a:0", "$b:0"])).toBe(false);
  });

  it("is off on the first load, when there is no previous list to anchor to", () => {
    expect(shouldShift([], ["$a:0", "$b:0"])).toBe(false);
  });

  it("is off when the list empties, since there is nothing left to hold in place", () => {
    expect(shouldShift(["$a:0", "$b:0"], [])).toBe(false);
  });

  it("is off when no row survives — a room switch, not a change to this list", () => {
    expect(shouldShift(["$a:0", "$b:0"], ["$x:0", "$y:0"])).toBe(false);
  });

  it("is off when a reaction rewrites the last row's key, which is an end change", () => {
    // `rowKey` puts the reaction count in the key, so a 👀 landing on the
    // newest message changes that row's key without moving anything above it.
    expect(shouldShift(["$a:0", "$b:0"], ["$a:0", "$b:1"])).toBe(false);
  });

  it("is off when a reaction rewrites a middle row's key", () => {
    expect(shouldShift(["$a:0", "$b:0", "$c:0"], ["$a:0", "$b:1", "$c:0"])).toBe(false);
  });

  it("is on when a limited sync unloads the head and pagination puts more back", () => {
    // The `reset_shrank` path (`core::timeline`): the oldest rows are dropped
    // and older history is paginated back in, so the surviving rows end up
    // further down than they started.
    expect(shouldShift(["$c:0", "$d:0", "$e:0"], ["$a:0", "$b:0", "$d:0", "$e:0"])).toBe(true);
  });

  it("is on when rows are only removed from the head, so the tail stays put", () => {
    // virtua's prop covers items "added or removed from the beginning"; the
    // reader is looking at the tail either way.
    expect(shouldShift(["$a:0", "$b:0", "$c:0"], ["$c:0"])).toBe(true);
  });

  it("judges by the first row that survived, not by the one that vanished", () => {
    // `$a` dropped off the head and `$d` arrived at the tail in the same
    // batch. The head change is the one that decides it: `$b` moved up, so
    // the tail has to be held in place.
    expect(shouldShift(["$a:0", "$b:0", "$c:0"], ["$b:0", "$c:0", "$d:0"])).toBe(true);
  });
});

describe("undecryptable runs", () => {
  // 2026-10-03, a new device before recovery: Krishna's room was a column of
  // identical "Encrypted message — this device has no key for it" lines, one
  // per message, with nothing saying what to do about them.
  function utd(id: string) {
    return {
      ...message(id),
      view: {
        render: "placeholder" as const,
        kind: { about: "unableToDecrypt" as const },
        text: "Encrypted message — this device has no key for it",
      },
    };
  }

  it("collapses consecutive undecryptable messages into one row with a count", () => {
    const rows = groupTimelineItems([utd("u1"), utd("u2"), utd("u3")]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ type: "undecryptableGroup", key: "utd:u1", count: 3 });
  });

  it("keeps a lone one as a group of one, so it carries the same way out", () => {
    expect(groupTimelineItems([utd("u1")])[0]).toMatchObject({ type: "undecryptableGroup", count: 1 });
  });

  it("is split by anything readable in between", () => {
    const rows = groupTimelineItems([utd("u1"), message("m1"), utd("u2"), utd("u3")]);
    expect(rows.map((row) => row.type)).toEqual(["undecryptableGroup", "item", "undecryptableGroup"]);
    expect(rows[2]).toMatchObject({ count: 2 });
  });
});
