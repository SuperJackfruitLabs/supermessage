import { describe, expect, it } from "vitest";
import type { FirstRunScript, FirstRunStep } from "$lib/ipc";
import { createFirstRunDemo, demoCardItem } from "./firstRun";

const script = { stepDelaysMs: [1100, 900], agentName: "Atlas" } as unknown as FirstRunScript;

// The core's table (`core::first_run`), faked: the machine is tested against it, not the other way.
const advance = async (s: FirstRunStep): Promise<FirstRunStep> =>
  s === "greeting" ? "asking" : s === "asking" ? "waiting" : s;
const answer = async (s: FirstRunStep): Promise<FirstRunStep | null> => (s === "waiting" ? "approved" : null);

describe("createFirstRunDemo", () => {
  it("it only gets past the card on the reader's answer", async () => {
    const demo = createFirstRunDemo({ script, advance, answer, sleep: async () => {} });
    expect(await demo.answer("approve")).toBe(false);
    expect(demo.step).toBe("greeting");
    await demo.run();
    expect(demo.step).toBe("waiting");
    expect(await demo.answer("approve")).toBe(true);
    expect(demo.step).toBe("approved");
    expect(demo.chosen).toBe("approve");
    expect(await demo.answer("reject")).toBe(false);
    expect(demo.chosen).toBe("approve");
  });

  it("the pace is the script's", async () => {
    const slept: number[] = [];
    const demo = createFirstRunDemo({
      script,
      advance,
      answer,
      sleep: async (ms) => void slept.push(ms),
    });
    await demo.run();
    expect(slept).toEqual([1100, 900]);
  });

  it("disposing stops the pace", async () => {
    let advanced = 0;
    let sleeps = 0;
    const demo = createFirstRunDemo({
      script,
      advance: async (s) => (advanced++, advance(s)),
      answer,
      sleep: async () => {
        if (++sleeps === 2) demo.dispose();
      },
    });
    await demo.run();
    expect(advanced).toBe(1);
    expect(demo.step).toBe("asking");
  });

  it("an answer that lands after disposal changes nothing", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const demo = createFirstRunDemo({
      script,
      advance,
      answer: async (s) => (await gate, answer(s)),
      sleep: async () => {},
      initialStep: "waiting",
    });
    const pending = demo.answer("approve");
    demo.dispose();
    release();
    expect(await pending).toBe(false);
    expect(demo.step).toBe("waiting");
  });

  it("tells a view when the step changes", async () => {
    let calls = 0;
    const demo = createFirstRunDemo({ script, advance, answer, sleep: async () => {}, onChange: () => calls++ });
    await demo.run();
    expect(calls).toBe(2);
  });
});

describe("demoCardItem", () => {
  it("has no event id, so nothing in it can be sent to a room", () => {
    const item = demoCardItem(script, 5);
    expect(item.eventId).toBeNull();
    expect(item.timestampMs).toBe(5);
    expect(item.sender).toBe("Atlas");
  });
});
