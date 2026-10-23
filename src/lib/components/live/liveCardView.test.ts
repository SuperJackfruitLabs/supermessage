import { describe, expect, it } from "vitest";
import {
  elapsedMs,
  failedLabel,
  showsCurrentStep,
  stateWord,
  statusTone,
  stepsDoneLabel,
  stepsToggleLabel,
  toolGlyph,
  writerName,
} from "./liveCardView";
import { groupTimelineItems } from "../timelineGrouping";
import { message } from "$lib/fixtures/timeline";
import type { TurnSummary } from "$lib/ipc";

function summary(over: Partial<TurnSummary> = {}): TurnSummary {
  return {
    roomId: "!a:x.org",
    turnId: 1,
    revision: 1,
    inProgress: true,
    endedBy: null,
    startedAtMs: 1_000,
    endedAtMs: null,
    lastActivityMs: 9_000,
    answering: false,
    hasThought: false,
    totalSteps: 0,
    completedSteps: 0,
    failedStep: null,
    currentStepTitle: "",
    stepIsTool: false,
    recordLabel: null,
    ...over,
  };
}

describe("labels", () => {
  it("count steps the way iOS says them", () => {
    expect(stepsDoneLabel(1)).toBe("1 step done");
    expect(stepsDoneLabel(0)).toBe("0 steps done");
    expect(stepsDoneLabel(3)).toBe("3 steps done");
    expect(stepsToggleLabel(1)).toBe("1 step");
    expect(stepsToggleLabel(4)).toBe("All 4 steps");
    expect(failedLabel("run pnpm check")).toBe("run pnpm check failed");
  });

  it("the state word is Working until the turn is ended", () => {
    expect(stateWord(summary())).toBe("Working");
    expect(stateWord(summary({ endedBy: "done" }))).toBe("Done");
    expect(stateWord(null)).toBe("Working");
  });

  it("only a timed-out turn is neutral; done and reply are ok; going is the accent", () => {
    expect(statusTone(summary())).toBe("working");
    expect(statusTone(null)).toBe("working");
    expect(statusTone(summary({ endedBy: "done" }))).toBe("ok");
    expect(statusTone(summary({ endedBy: "reply" }))).toBe("ok");
    expect(statusTone(summary({ endedBy: "timedOut" }))).toBe("neutral");
  });

  it("queued and unknown steps share the clock", () => {
    expect(toolGlyph("done")).toBe("done");
    expect(toolGlyph("failed")).toBe("failed");
    expect(toolGlyph("running")).toBe("running");
    expect(toolGlyph("queued")).toBe("waiting");
    expect(toolGlyph("unknown")).toBe("waiting");
  });
});

describe("showsCurrentStep", () => {
  it("a tool step shows even with answer text", () => {
    expect(showsCurrentStep(summary({ stepIsTool: true }), true)).toBe(true);
  });
  it("a non-tool step is hidden once text shows, and shown before", () => {
    expect(showsCurrentStep(summary({ stepIsTool: false }), true)).toBe(false);
    expect(showsCurrentStep(summary({ stepIsTool: false }), false)).toBe(true);
  });
  it("a finished turn never names a step", () => {
    expect(showsCurrentStep(summary({ endedBy: "done", stepIsTool: true }), false)).toBe(false);
  });
  it("text before any summary still says Starting", () => {
    expect(showsCurrentStep(null, false)).toBe(true);
  });
});

describe("elapsedMs", () => {
  it("runs to now while going", () => {
    expect(elapsedMs(summary(), 5_000, false)).toBe(4_000);
  });
  it("is measured to the last event when still or finished", () => {
    expect(elapsedMs(summary(), 5_000, true)).toBe(8_000);
    expect(elapsedMs(summary({ endedBy: "done" }), 99_000, false)).toBe(8_000);
  });
  it("never goes negative", () => {
    expect(elapsedMs(summary({ startedAtMs: 9_000 }), 1_000, false)).toBe(0);
  });
});

describe("writerName", () => {
  const rows = groupTimelineItems([
    { ...message("a"), senderName: "Krishna (openclaw @ ashram)" },
    { ...message("b"), item: { ...message("b").item, isOwn: true }, senderName: "Me" },
  ]);
  it("the room's agent name wins", () => {
    expect(writerName("Atlas", rows)).toBe("Atlas");
  });
  it("else the newest peer's name", () => {
    expect(writerName(null, rows)).toBe("Krishna (openclaw @ ashram)");
  });
  it("else Agent", () => {
    expect(writerName(null, [])).toBe("Agent");
  });
});
