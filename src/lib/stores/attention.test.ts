import { describe, expect, it } from "vitest";
import type { NeedsYouInbox } from "$lib/ipc";
import { createAttention } from "./attention.svelte";

function inbox(decisions: number, invitations: number): NeedsYouInbox {
  // The core's own shape: `count` includes invitations, `badgeCount` is decisions only.
  return {
    decisions: Array.from({ length: decisions }, () => ({})),
    invitations: Array.from({ length: invitations }, () => ({})),
    count: decisions + invitations,
    badgeCount: decisions,
  } as unknown as NeedsYouInbox;
}

function harness() {
  const sent: number[] = [];
  const attention = createAttention({ setAttention: async (n) => void sent.push(n) });
  return { sent, attention };
}

describe("attention", () => {
  it("the title counts decisions, not invitations", () => {
    const { sent, attention } = harness();
    attention.follow(inbox(2, 1));
    expect(sent).toEqual([2]);
  });

  it("the title is set only when the count changes", () => {
    const { sent, attention } = harness();
    attention.follow(inbox(2, 0));
    attention.follow(inbox(2, 0));
    attention.follow(inbox(2, 1));
    attention.follow(inbox(3, 1));
    attention.follow(null);
    attention.follow(null);
    expect(sent).toEqual([2, 3, 0]);
  });

  it("signing out clears the title", () => {
    const { sent, attention } = harness();
    attention.follow(inbox(2, 0));
    attention.reset();
    expect(sent).toEqual([2, 0]);
  });

  it("after a sign-out the same count is set again for the next account", () => {
    const { sent, attention } = harness();
    attention.follow(inbox(2, 0));
    attention.reset();
    attention.follow(inbox(2, 0));
    expect(sent).toEqual([2, 0, 2]);
  });
});
