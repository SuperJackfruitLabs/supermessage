import { describe, expect, it } from "vitest";
import { rowEdgeClass } from "./rowMeta";

describe("rowEdgeClass", () => {
  it("rowEdgeClass reserves the first and last rows", () => {
    expect(rowEdgeClass(0, 5)).toBe("pt-4");
    expect(rowEdgeClass(4, 5)).toBe("pb-4");
    expect(rowEdgeClass(2, 5)).toBe("");
    expect(rowEdgeClass(0, 1)).toBe("pt-4 pb-4");
  });
});
