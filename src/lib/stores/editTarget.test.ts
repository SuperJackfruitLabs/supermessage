import { describe, expect, it } from "vitest";
import { createEditTargetStore } from "./editTarget.svelte";

const row = (editable: boolean, eventId: string | null, body: string | null) => ({
  item: { editable, eventId, body },
});

describe("editTargetStore", () => {
  it("fromRow refuses a non-editable row", () => {
    expect(createEditTargetStore().fromRow(row(false, "$e", "hi"))).toBeNull();
  });
  it("fromRow refuses a row without an event id", () => {
    expect(createEditTargetStore().fromRow(row(true, null, "hi"))).toBeNull();
  });
  it("fromRow seeds an empty body", () => {
    expect(createEditTargetStore().fromRow(row(true, "$e", null))).toEqual({ eventId: "$e", body: "" });
  });
  it("fromRow seeds the body", () => {
    expect(createEditTargetStore().fromRow(row(true, "$e", "hi"))).toEqual({ eventId: "$e", body: "hi" });
  });
  it("scopes per room", () => {
    const s = createEditTargetStore();
    s.set("!a", { eventId: "$a", body: "x" });
    expect(s.get("!b")).toBeNull();
    s.clear("!a");
    expect(s.get("!a")).toBeNull();
  });
  it("clearAll empties every room", () => {
    const s = createEditTargetStore();
    s.set("!a", { eventId: "$a", body: "x" });
    s.set("!b", { eventId: "$b", body: "y" });
    s.clearAll();
    expect(s.get("!a")).toBeNull();
    expect(s.get("!b")).toBeNull();
  });
});
