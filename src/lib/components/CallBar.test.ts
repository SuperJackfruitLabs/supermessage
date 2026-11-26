import { render } from "svelte/server";
import { describe, expect, it } from "vitest";

import type { CallView } from "$lib/ipc";
import CallBar from "./CallBar.svelte";

const base: CallView = {
  roomId: "!r:s",
  agentUserId: "@agent_x:s",
  agentName: "✦ Ada — Researcher",
  phase: "live",
  agentState: "thinking",
  startedAtMs: 1_000,
  muted: false,
};

const html = (call: Partial<CallView> = {}) =>
  render(CallBar, {
    props: { call: { ...base, ...call }, onMute: () => {}, onHangUp: () => {}, nowMs: 1_000 + 161_000 },
  }).body;

describe("CallBar", () => {
  it("draws the design's line: who, how long, the agent's state, Mute and Hang up", () => {
    const out = html();
    expect(out).toContain("Call with ✦ Ada — Researcher");
    expect(out).toContain("02:41");
    expect(out).toContain("Thinking…");
    expect(out).toContain(">Mute<");
    expect(out).toContain("Hang up");
    expect(out).toContain('aria-pressed="false"');
  });

  it("says Unmute, pressed, while muted", () => {
    const out = html({ muted: true });
    expect(out).toContain("Unmute");
    expect(out).toContain('aria-pressed="true"');
  });

  it("while connecting, the clock waits and Mute is not offered yet", () => {
    const out = html({ phase: "connecting", startedAtMs: null, agentState: null });
    expect(out).toContain("Connecting…");
    expect(out).toContain("00:00");
    // The attribute, not the `disabled:` utility class every render carries.
    expect(out).toMatch(/<button[^>]*aria-pressed="false"[^>]*\sdisabled(=""|\s|>)/);
  });

  it("offers Mute once live", () => {
    expect(html()).not.toMatch(/<button[^>]*aria-pressed="false"[^>]*\sdisabled(=""|\s|>)/);
  });
});
