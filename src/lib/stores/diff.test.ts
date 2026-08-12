import { describe, expect, it } from "vitest";
import { applyOps, DiffTracker, type DiffEnvelope } from "./diff";

describe("applyOps", () => {
  it("appends", () => expect(applyOps([1], [{ op: "append", values: [2, 3] }])).toEqual([1, 2, 3]));
  it("clears", () => expect(applyOps([1, 2], [{ op: "clear" }])).toEqual([]));
  it("pushes front", () => expect(applyOps([2], [{ op: "pushFront", value: 1 }])).toEqual([1, 2]));
  it("pushes back", () => expect(applyOps([1], [{ op: "pushBack", value: 2 }])).toEqual([1, 2]));
  it("pops front", () => expect(applyOps([1, 2], [{ op: "popFront" }])).toEqual([2]));
  it("pops back", () => expect(applyOps([1, 2], [{ op: "popBack" }])).toEqual([1]));
  it("inserts", () => expect(applyOps([1, 3], [{ op: "insert", index: 1, value: 2 }])).toEqual([1, 2, 3]));
  it("sets", () => expect(applyOps([1, 9], [{ op: "set", index: 1, value: 2 }])).toEqual([1, 2]));
  it("removes", () => expect(applyOps([1, 2, 3], [{ op: "remove", index: 1 }])).toEqual([1, 3]));
  it("truncates", () => expect(applyOps([1, 2, 3], [{ op: "truncate", length: 2 }])).toEqual([1, 2]));
  it("resets", () => expect(applyOps([1, 2], [{ op: "reset", values: [9] }])).toEqual([9]));

  it("applies a batch in order", () => {
    expect(applyOps([1], [{ op: "pushBack", value: 2 }, { op: "popFront" }])).toEqual([2]);
  });

  it("does not mutate its input", () => {
    const original = [1, 2];
    applyOps(original, [{ op: "clear" }]);
    expect(original).toEqual([1, 2]);
  });
});

describe("applyOps out-of-range handling (mirrors dto::apply_ops)", () => {
  it("ignores set/remove with an out-of-bounds index instead of throwing", () => {
    expect(applyOps([1, 2], [{ op: "remove", index: 5 }])).toEqual([1, 2]);
    expect(applyOps([1, 2], [{ op: "set", index: 5, value: 9 }])).toEqual([1, 2]);
  });

  it("ignores an out-of-range insert, but permits index === length as an append", () => {
    expect(applyOps([1, 2], [{ op: "insert", index: 5, value: 9 }])).toEqual([1, 2]);
    expect(applyOps([1, 2], [{ op: "insert", index: 2, value: 3 }])).toEqual([1, 2, 3]);
  });

  it("ignores pop on an empty list instead of throwing", () => {
    expect(applyOps([], [{ op: "popFront" }])).toEqual([]);
    expect(applyOps([], [{ op: "popBack" }])).toEqual([]);
  });
});

describe("DiffTracker gap detection", () => {
  const env = (seq: number, ops: DiffEnvelope<number>["ops"]): DiffEnvelope<number> =>
    ({ channel: "rooms", subject: "", seq, ops });

  it("accepts sequential envelopes", () => {
    const t = new DiffTracker<number>();
    expect(t.apply(env(1, [{ op: "pushBack", value: 1 }]))).toBe("ok");
    expect(t.apply(env(2, [{ op: "pushBack", value: 2 }]))).toBe("ok");
    expect(t.items).toEqual([1, 2]);
  });

  it("reports a gap and leaves state untouched when an envelope is missed", () => {
    const t = new DiffTracker<number>();
    t.apply(env(1, [{ op: "pushBack", value: 1 }]));
    expect(t.apply(env(3, [{ op: "pushBack", value: 3 }]))).toBe("gap");
    // a gap must not apply partial state
    expect(t.items).toEqual([1]);
  });

  it("recovers after a resync", () => {
    const t = new DiffTracker<number>();
    t.apply(env(1, [{ op: "pushBack", value: 1 }]));
    expect(t.apply(env(5, []))).toBe("gap");
    t.reset([7, 8], 5);
    expect(t.items).toEqual([7, 8]);
    expect(t.apply(env(6, [{ op: "pushBack", value: 9 }]))).toBe("ok");
    expect(t.items).toEqual([7, 8, 9]);
  });

  it("ignores a duplicate envelope rather than applying it twice", () => {
    const t = new DiffTracker<number>();
    t.apply(env(1, [{ op: "pushBack", value: 1 }]));
    expect(t.apply(env(1, [{ op: "pushBack", value: 1 }]))).toBe("ok");
    expect(t.items).toEqual([1]);
  });
});
