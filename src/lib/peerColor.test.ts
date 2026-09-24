import { describe, expect, it } from "vitest";
import { PEER_COUNT, peerColorIndex } from "./peerColor";

describe("peerColorIndex", () => {
  it("matches the core's pinned vectors", () => {
    // crates/supermessage-core/src/peer_color.rs asserts the same five.
    expect(peerColorIndex("@agent_krishna:id.agentpod.dev")).toBe(2);
    expect(peerColorIndex("@agent_strategy-sam:id.agentpod.dev")).toBe(3);
    expect(peerColorIndex("@strategy-sam:guild.example.org")).toBe(5);
    expect(peerColorIndex("@rakesh:id.agentpod.dev")).toBe(5);
    expect(peerColorIndex("")).toBe(2);
  });

  it("stays in range", () => {
    for (let i = 0; i < 500; i++) {
      const index = peerColorIndex(`@user${i}:example.org`);
      expect(index).toBeGreaterThanOrEqual(0);
      expect(index).toBeLessThan(PEER_COUNT);
    }
  });
});
