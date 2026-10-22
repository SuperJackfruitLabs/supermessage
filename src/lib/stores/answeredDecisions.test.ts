import { describe, expect, it } from "vitest";

import { createAnsweredDecisions } from "./answeredDecisions.svelte";

const approve = { option: { id: "approve", label: "Approve" }, atMs: 5 };

describe("answeredDecisions", () => {
  it("keeps the answer by event id, so a remounted row still sees it", () => {
    const store = createAnsweredDecisions();
    expect(store.get("$gate")).toBeNull();
    store.record("$gate", approve);
    expect(store.get("$gate")).toEqual(approve);
    expect(store.get("$other")).toBeNull();
  });

  it("cannot key an unconfirmed event", () => {
    const store = createAnsweredDecisions();
    store.record(null, approve);
    expect(store.get(null)).toBeNull();
  });

  it("clear forgets everything", () => {
    const store = createAnsweredDecisions();
    store.record("$gate", approve);
    store.clear();
    expect(store.get("$gate")).toBeNull();
  });

  it("is per instance, never shared between rooms' timelines", () => {
    const a = createAnsweredDecisions();
    const b = createAnsweredDecisions();
    a.record("$gate", approve);
    expect(b.get("$gate")).toBeNull();
  });
});
