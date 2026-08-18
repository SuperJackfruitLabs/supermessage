// Turning an agent's answer from a series of jumps into writing.
//
// The wire is deliberately chunky. `apps/hub`'s streaming policy holds a
// delta until it has at least 24 characters *and* reaches a sentence boundary,
// or until 1.5s has passed — so the text a reader sees can sit perfectly still
// for a second and a half and then gain a whole paragraph at once. That is the
// right thing to send (every delta is a to-device message to every one of the
// reader's devices) and the wrong thing to show.
//
// So the client keeps its own clock: it is always some way behind what it has
// been told, and it spends that debt down at a steady rate.

import { describe, expect, it } from "vitest";
import { createPacer, MAX_LAG_CHARS, REVEAL_CHARS_PER_SECOND } from "./pacer";

describe("pacer", () => {
  it("shows nothing before anything has been said", () => {
    const pacer = createPacer();
    expect(pacer.visible).toBe("");
  });

  it("reveals at a steady rate rather than in the jumps it was handed", () => {
    const pacer = createPacer();
    // Long enough that neither the end of the text nor `MAX_LAG_CHARS` is
    // reached during the window being measured — this test is about the rate,
    // and both of those are deliberately *not* the rate.
    pacer.receive("z".repeat(300));
    expect(pacer.visible).toBe("");

    pacer.advance(100);
    const firstWindow = pacer.visible.length;
    const before = pacer.visible.length;
    pacer.advance(200);
    const secondWindow = pacer.visible.length - before;

    expect(firstWindow).toBeGreaterThan(0);
    // Steady: twice the time reveals twice the text, give or take the
    // fractional character carried between advances.
    expect(Math.abs(secondWindow - firstWindow * 2)).toBeLessThanOrEqual(2);
  });

  it("reveals the text it was given, in order, never a rearrangement of it", () => {
    const pacer = createPacer();
    pacer.receive("The node is online.");
    pacer.advance(400);
    expect("The node is online.".startsWith(pacer.visible)).toBe(true);
  });

  it("catches up rather than falling ever further behind", () => {
    const pacer = createPacer();
    pacer.receive("x".repeat(2_000));
    // Far more than `MAX_LAG_CHARS` outstanding: the rate gives way, because a
    // reader waiting on a long answer should not be made to watch it typed.
    pacer.advance(200);
    expect(pacer.pending).toBeLessThanOrEqual(MAX_LAG_CHARS);
  });

  it("never shows more than it was told", () => {
    const pacer = createPacer();
    pacer.receive("short");
    pacer.advance(10_000);
    expect(pacer.visible).toBe("short");
    expect(pacer.pending).toBe(0);
  });

  it("keeps going when the next delta extends the answer", () => {
    const pacer = createPacer();
    pacer.receive("First part. ");
    pacer.advance(10_000);
    expect(pacer.visible).toBe("First part. ");

    // Deltas are cumulative on the wire — each carries the whole answer.
    pacer.receive("First part. Second part.");
    expect(pacer.visible).toBe("First part. ");
    pacer.advance(10_000);
    expect(pacer.visible).toBe("First part. Second part.");
  });

  it("shows everything at once when the turn ends, so nothing is left owing", () => {
    // The real message is about to land in the room. A pacer still dribbling
    // out the tail would be overtaken by it, and the reader would watch the
    // answer finish twice.
    const pacer = createPacer();
    pacer.receive("A long and complete answer.");
    pacer.finish();
    expect(pacer.visible).toBe("A long and complete answer.");
    expect(pacer.pending).toBe(0);
  });

  it("starts clean for the next turn", () => {
    const pacer = createPacer();
    pacer.receive("Previous turn.");
    pacer.advance(10_000);
    pacer.reset();
    expect(pacer.visible).toBe("");
    expect(pacer.pending).toBe(0);
  });

  it("does not rewind when a delta arrives out of order", () => {
    // To-device delivery is at-least-once and unordered, which is why deltas
    // carry a seq. A late, shorter delta must not un-write text.
    const pacer = createPacer();
    pacer.receive("One two three");
    pacer.receive("One two");
    pacer.advance(10_000);
    expect(pacer.visible).toBe("One two three");
  });

  it("reveals a whole answer in a sensible span, not a crawl", () => {
    // Sanity on the constant: a 600-character reply should finish revealing in
    // a couple of seconds, not thirty. Guards the rate against being tuned
    // into a novelty typewriter.
    const pacer = createPacer();
    pacer.receive("y".repeat(600));
    pacer.advance(3_000);
    expect(pacer.pending).toBe(0);
    expect(REVEAL_CHARS_PER_SECOND).toBeGreaterThanOrEqual(200);
  });
});
