import { describe, expect, it } from "vitest";
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
