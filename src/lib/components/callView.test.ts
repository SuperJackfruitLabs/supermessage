import { describe, expect, it } from "vitest";
import type { CallSnapshot, CallView } from "$lib/ipc";
import { callButtonShown, callTitle, elapsedLabel, noticeFor, stateWord } from "./callView";

function call(over: Partial<CallView> = {}): CallView {
  return {
    roomId: "!r:s",
    agentUserId: "@agent_x:s",
    agentName: "Ada",
    phase: "live",
    agentState: null,
    startedAtMs: 1_000,
    muted: false,
    ...over,
  };
}

describe("callView", () => {
  it("names the agent's state the way the design's bar does", () => {
    expect(stateWord(call({ phase: "connecting" }))).toBe("Connecting…");
    expect(stateWord(call({ agentState: null }))).toBe("Connecting…");
    expect(stateWord(call({ agentState: "connecting" }))).toBe("Connecting…");
    expect(stateWord(call({ agentState: "listening" }))).toBe("Listening");
    expect(stateWord(call({ agentState: "thinking" }))).toBe("Thinking…");
    expect(stateWord(call({ agentState: "speaking" }))).toBe("Speaking");
    // Still joining: whatever the agent said earlier is not shown yet.
    expect(stateWord(call({ phase: "connecting", agentState: "speaking" }))).toBe("Connecting…");
  });

  it("formats elapsed time as mm:ss, and h:mm:ss past an hour", () => {
    expect(elapsedLabel(null, 5_000)).toBe("00:00");
    expect(elapsedLabel(1_000, 1_000)).toBe("00:00");
    expect(elapsedLabel(1_000, 1_000 + 161_000)).toBe("02:41");
    expect(elapsedLabel(1_000, 1_000 + 59_999)).toBe("00:59");
    expect(elapsedLabel(0, 3_600_000 + 62_000)).toBe("1:01:02");
    // A clock behind the start never reads negative.
    expect(elapsedLabel(10_000, 1_000)).toBe("00:00");
  });

  it("titles the bar with the agent's name", () => {
    expect(callTitle(call({ agentName: "Ada — Researcher" }))).toBe("Call with Ada — Researcher");
  });

  it("shows the Call button only with the setting on, an agent counterpart, a joined room and no call", () => {
    const agent = { userId: "@agent_x:s", name: "Ada" };
    expect(callButtonShown(true, agent, true, null)).toBe(true);
    expect(callButtonShown(false, agent, true, null)).toBe(false);
    expect(callButtonShown(true, null, true, null)).toBe(false);
    expect(callButtonShown(true, agent, false, null)).toBe(false);
    expect(callButtonShown(true, agent, true, call())).toBe(false);
  });

  it("shows a notice only in the room it is about", () => {
    const snapshot: CallSnapshot = {
      enabled: true,
      call: null,
      notice: { roomId: "!r:s", text: "Calls are not available for this agent yet" },
    };
    expect(noticeFor(snapshot, "!r:s")).toBe("Calls are not available for this agent yet");
    expect(noticeFor(snapshot, "!other:s")).toBeNull();
    expect(noticeFor(snapshot, null)).toBeNull();
    expect(noticeFor({ ...snapshot, notice: null }, "!r:s")).toBeNull();
  });
});
