import { describe, expect, test } from "vitest";
import { reasoningLabel } from "./reasoningLabel";

describe("what the reasoning header says", () => {
  test("while the agent is still thinking, it says so in the present tense", () => {
    // The tense is the information: a reader glancing at this needs to know
    // whether to wait.
    expect(reasoningLabel({ streaming: true, seconds: 0 })).toBe("Thinking…");
    expect(reasoningLabel({ streaming: true, seconds: 12 })).toBe("Thinking…");
  });

  test("once it has finished, it says how long it took", () => {
    expect(reasoningLabel({ streaming: false, seconds: 12 })).toBe("Thought for 12 seconds");
  });

  test("one second is not 1 seconds", () => {
    expect(reasoningLabel({ streaming: false, seconds: 1 })).toBe("Thought for 1 second");
  });

  test("a duration too short to have counted is described, not rounded to zero", () => {
    // "Thought for 0 seconds" reads as a bug. It also isn't true — the turn
    // took *some* time, just less than the resolution we report in.
    expect(reasoningLabel({ streaming: false, seconds: 0 })).toBe("Thought for a moment");
  });

  test("a duration we were never told is not invented", () => {
    // The thought channel carries no timing of its own; a turn whose start we
    // missed (a device asleep, a late subscribe) has no honest number.
    expect(reasoningLabel({ streaming: false, seconds: undefined })).toBe("Thought about it");
  });

  test("long thinking is reported in minutes, because 3600 seconds means nothing", () => {
    expect(reasoningLabel({ streaming: false, seconds: 60 })).toBe("Thought for 1 minute");
    expect(reasoningLabel({ streaming: false, seconds: 154 })).toBe("Thought for 2 minutes");
  });
});
