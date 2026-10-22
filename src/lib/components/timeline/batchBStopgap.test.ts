import { describe, expect, it } from "vitest";
import { message } from "$lib/fixtures";
import type { TimelineDisplayRow } from "../timelineGrouping";
import { stopgapFacts } from "./batchBStopgap";

const item = (id: string, over: Record<string, unknown> = {}): TimelineDisplayRow =>
  ({ type: "item", key: id, continuesRun: false, item: message({ id, eventId: `$${id}`, sender: "@a:x", ...over }).item }) as never;

describe("stopgapFacts", () => {
  it("knows an agent by the room's cast", () => {
    const rows = [item("1"), item("2", { sender: "@p:x" })];
    expect(stopgapFacts(rows, 0, ["@a:x"]).isAgent).toBe(true);
    expect(stopgapFacts(rows, 1, ["@a:x"]).isAgent).toBe(false);
    expect(stopgapFacts(rows, 0, []).isAgent).toBe(false);
  });

  it("a long read is more than 600 characters", () => {
    const rows = [item("1", { body: "x".repeat(600) }), item("2", { body: "x".repeat(601) })];
    expect(stopgapFacts(rows, 0, []).isLongRead).toBe(false);
    expect(stopgapFacts(rows, 1, []).isLongRead).toBe(true);
  });

  it("the quote hides only directly under its parent", () => {
    const reply = { eventId: "$1" } as never;
    const rows = [item("1"), item("2", { replyTo: reply }), item("3"), item("4", { replyTo: reply })];
    expect(stopgapFacts(rows, 1, []).hidesQuote).toBe(true);
    expect(stopgapFacts(rows, 3, []).hidesQuote).toBe(false);
    expect(stopgapFacts(rows, 0, []).hidesQuote).toBe(false);
  });

  it("the runtime shows once the room has several agents", () => {
    const rows = [item("1")];
    expect(stopgapFacts(rows, 0, ["@a:x"]).showsRuntime).toBe(false);
    expect(stopgapFacts(rows, 0, ["@a:x", "@b:x"]).showsRuntime).toBe(true);
  });
});
