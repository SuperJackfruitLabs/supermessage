import { describe, expect, it } from "vitest";
import { groupTimelineItems } from "../timelineGrouping";
import { message } from "$lib/fixtures";
import { arrivedKeys, ARRIVAL_LIMIT, isArrivingRender } from "./arrivals";

const all = () => true;

describe("arrivedKeys", () => {
  it("one new message animates", () => {
    expect(arrivedKeys(["a", "b"], ["a", "b", "c"], true, all)).toEqual(new Set(["c"]));
  });
  it("arrivedKeys is empty when not following", () => {
    expect(arrivedKeys(["a", "b"], ["a", "b", "c"], false, all).size).toBe(0);
  });
  it("arrivedKeys is empty for more than three", () => {
    expect(arrivedKeys(["a"], ["a", "b", "c", "d", "e"], true, all).size).toBe(0);
    expect(ARRIVAL_LIMIT).toBe(3);
  });
  it("three new messages all animate", () => {
    expect(arrivedKeys(["a"], ["a", "b", "c", "d"], true, all)).toEqual(new Set(["b", "c", "d"]));
  });
  it("arrivedKeys is empty on the first fill", () => {
    expect(arrivedKeys([], ["a", "b"], true, all).size).toBe(0);
  });
  it("a date divider never animates", () => {
    expect(arrivedKeys(["a", "b"], ["a", "b", "d", "c"], true, (k) => k !== "d")).toEqual(new Set(["c"]));
  });
  it("a divider does not count toward the limit", () => {
    expect(arrivedKeys(["a"], ["a", "d", "b", "c", "e"], true, (k) => k !== "d")).toEqual(
      new Set(["b", "c", "e"]),
    );
  });
  it("pagination prepends do not animate", () => {
    expect(arrivedKeys(["b", "c"], ["a", "b", "c"], false, all).size).toBe(0);
  });
});

const ids = (items: ReturnType<typeof message>[]) =>
  groupTimelineItems(items).flatMap((r) => (r.type === "item" ? [r.item.id] : []));

describe("arrivals by identity", () => {
  const reaction = { key: "👍", displayKey: "👍", count: 1, byMe: false, senders: [] };
  it("a reaction on an existing message is not an arrival", () => {
    const before = [message({ id: "a", eventId: "$a" }), message({ id: "b", eventId: "$b" })];
    const after = [before[0]!, message({ id: "b", eventId: "$b", reactions: [reaction] })];
    // The row key changes with the reaction count (the precondition)...
    const keys = (items: typeof before) => groupTimelineItems(items).map((r) => r.key);
    expect(keys(after)).not.toEqual(keys(before));
    // Fed row keys, the reaction reads as an arrival (the bug this prevents)...
    expect(arrivedKeys(keys(before), keys(after), true, all).size).toBe(1);
    // ...fed item ids, it is not.
    expect(arrivedKeys(ids(before), ids(after), true, all).size).toBe(0);
  });
  it("a send's server echo is not a second arrival", () => {
    const pending = [message({ id: "a", eventId: "$a" }), message({ id: "u1", eventId: null, sendState: "notSentYet" })];
    const echoed = [pending[0]!, message({ id: "u1", eventId: "$real", sendState: "sent" })];
    expect(arrivedKeys(ids(pending), ids(echoed), true, all).size).toBe(0);
  });
  it("reduced motion animates nothing", () => {
    expect(arrivedKeys(["a"], ["a", "b"], true, all, true).size).toBe(0);
  });
});

describe("isArrivingRender", () => {
  it("is true only for message renders", () => {
    for (const r of ["bubble", "image", "mediaFile", "audio", "customEvent"]) {
      expect(isArrivingRender(r)).toBe(true);
    }
    for (const r of ["dateDivider", "system", "unreadMarker", "placeholder", "none", "emote"]) {
      expect(isArrivingRender(r)).toBe(false);
    }
  });
});
