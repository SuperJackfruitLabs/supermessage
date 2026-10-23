import { describe, expect, it } from "vitest";
import { createReaderLabels } from "./readerLabel";

const tick = () => new Promise((r) => setTimeout(r, 0));

function harness() {
  const asked: string[][] = [];
  let changes = 0;
  const labels = createReaderLabels(
    async (ids) => {
      asked.push(ids);
      return ids.join(" & ");
    },
    () => changes++,
  );
  return { asked, labels, changes: () => changes };
}

describe("readerLabel", () => {
  it("names the readers the core's way", async () => {
    const h = harness();
    expect(h.labels.label(["@b:x", "@a:x"])).toBeNull();
    await tick();
    expect(h.asked).toEqual([["@b:x", "@a:x"]]);
    expect(h.labels.label(["@b:x", "@a:x"])).toBe("Read by @b:x & @a:x");
    expect(h.changes()).toBe(1);
  });

  it("asks once per set of readers", async () => {
    const h = harness();
    h.labels.label(["@a:x"]);
    h.labels.label(["@a:x"]);
    await tick();
    h.labels.label(["@a:x"]);
    h.labels.label(["@a:x", "@b:x"]);
    await tick();
    expect(h.asked).toEqual([["@a:x"], ["@a:x", "@b:x"]]);
  });

  it("clear forgets", async () => {
    const h = harness();
    h.labels.label(["@a:x"]);
    await tick();
    h.labels.clear();
    expect(h.labels.label(["@a:x"])).toBeNull();
    await tick();
    expect(h.asked).toHaveLength(2);
  });

  it("a failed ask stays null and is not repeated on every render", async () => {
    let calls = 0;
    const labels = createReaderLabels(async () => {
      calls++;
      throw { kind: "notReady", message: "" };
    });
    labels.label(["@a:x"]);
    await tick();
    labels.label(["@a:x"]);
    await tick();
    expect(calls).toBe(1);
    expect(labels.label(["@a:x"])).toBeNull();
  });

  it("an answer that lands after clear is dropped", async () => {
    const h = harness();
    h.labels.label(["@a:x"]);
    h.labels.clear();
    await tick();
    expect(h.labels.label(["@a:x"])).toBeNull();
  });
});
