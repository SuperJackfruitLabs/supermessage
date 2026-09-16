import { beforeEach, describe, expect, it, vi } from "vitest";

// Only the webview boundary is mocked: exercise the routing and actual IPC
// wrapper together, without sending a real message to a Matrix room.
const invoke = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

import { sendDecisionReply } from "./decisionReply";

const gate = {
  subject: "gate_4e8b",
  prompt: "Promote build 214?",
  options: [
    { id: "approve", label: "Approve" },
    { id: "request_changes", label: "Request changes" },
    { id: "reject", label: "Reject" },
  ],
};

beforeEach(() => { invoke.mockReset(); invoke.mockResolvedValue(undefined); });

describe("dispatch-card answers", () => {
  it.each(["approve", "request_changes", "reject"])("sends gate %s with its subject and Matrix event reference", async (optionId) => {
    await sendDecisionReply("!room:example.org", "$gate-event", gate, optionId);
    expect(invoke.mock.calls).toEqual([["send_gate_decision", {
      roomId: "!room:example.org", gateId: "gate_4e8b", optionId,
      comment: null, inReplyTo: "$gate-event", prompt: "Promote build 214?",
    }]]);
  });

  it("keeps subjectless AgentPod permission replies as ordinary text", async () => {
    await sendDecisionReply("!room:example.org", "$permission-event", {
      subject: null, prompt: "Allow this tool?", options: [{ id: "Allow once", label: "Allow once" }],
    }, "Allow once");
    expect(invoke.mock.calls).toEqual([["send_message", {
      roomId: "!room:example.org", body: "Allow once", mentions: [],
    }]]);
  });

  it("refuses an unconfirmed gate without sending a plain-text substitute", async () => {
    await expect(sendDecisionReply("!room:example.org", null, gate, "approve")).rejects.toThrow("event");
    expect(invoke).not.toHaveBeenCalled();
  });

  it("propagates gate-send failures without retrying as ordinary text", async () => {
    invoke.mockRejectedValueOnce(new Error("room changed"));
    await expect(sendDecisionReply("!room:example.org", "$gate-event", gate, "approve")).rejects.toThrow("room changed");
    expect(invoke).toHaveBeenCalledTimes(1);
    expect(invoke.mock.calls[0][0]).toBe("send_gate_decision");
  });
});
