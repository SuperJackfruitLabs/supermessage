import { describe, expect, it, vi } from "vitest";
import { navigateTo } from "./navigate";

describe("navigateTo", () => {
  it("a needsYou navigation selects the Needs you destination", () => {
    const choose = vi.fn();
    navigateTo("needsYou", choose);
    expect(choose).toHaveBeenCalledWith("needsYou");
  });

  it("a name that is no destination moves nothing", () => {
    const choose = vi.fn();
    navigateTo("settings", choose);
    expect(choose).not.toHaveBeenCalled();
  });
});
