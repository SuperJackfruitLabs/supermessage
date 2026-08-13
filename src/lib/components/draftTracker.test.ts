import { describe, expect, it } from "vitest";
import { DraftTracker } from "./draftTracker";

describe("DraftTracker.switchTo", () => {
  it("starts a fresh room with an empty draft", () => {
    const tracker = new DraftTracker();
    expect(tracker.switchTo("room-a", "")).toBe("");
  });

  it("does not save anything on the very first switch (no prior room)", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", "should not be persisted anywhere");
    // Switching to a second room must not somehow inherit the first room's
    // typed-but-never-saved text.
    expect(tracker.switchTo("room-b", "")).toBe("");
  });

  it("is the fix for the wrong-recipient bug: leaving a room saves its draft, and the incoming room starts clean", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", ""); // focus room A
    // The reader types a draft in room A, then switches to room B without
    // sending. The old bug: a shared `value` would still read "hello" once
    // room B is focused, and pressing Enter would send it to room B.
    const restoredForB = tracker.switchTo("room-b", "hello, wrong room");
    expect(restoredForB).toBe(""); // room B must never see room A's text
  });

  it("restores a room's draft when the reader switches back to it", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", "");
    tracker.switchTo("room-b", "draft for A"); // leaving A, saving its draft
    const restoredForA = tracker.switchTo("room-a", ""); // back to A
    expect(restoredForA).toBe("draft for A");
  });

  it("keeps each room's draft independent across repeated switching", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", "");
    tracker.switchTo("room-b", "A's draft");
    tracker.switchTo("room-c", "B's draft");
    expect(tracker.switchTo("room-a", "C's draft")).toBe("A's draft");
    expect(tracker.switchTo("room-b", "A's draft again")).toBe("B's draft");
    expect(tracker.switchTo("room-c", "B's draft again")).toBe("C's draft");
  });

  it("is a no-op that returns the given text unchanged when the room hasn't changed", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", "");
    expect(tracker.switchTo("room-a", "still typing")).toBe("still typing");
    // A later real switch away must see the latest text, proving the
    // repeated same-room call above didn't clobber anything.
    expect(tracker.switchTo("room-b", "still typing")).toBe("");
    expect(tracker.switchTo("room-a", "")).toBe("still typing");
  });

  it("persists an emptied draft (e.g. after a successful send) rather than resurrecting stale text", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", "");
    tracker.switchTo("room-b", "unsent draft for A"); // leaving A, saving its draft
    tracker.switchTo("room-a", ""); // back to A; the composer would now show the restored draft
    // The reader sends it — the composer clears its value to "" — then
    // leaves again. The "" must overwrite the earlier saved draft, not be
    // skipped in favor of it.
    tracker.switchTo("room-b", "");
    expect(tracker.switchTo("room-a", "")).toBe("");
  });
});

describe("DraftTracker.setDraftFor", () => {
  it("clears a room's draft without disturbing the currently focused room's live text", () => {
    const tracker = new DraftTracker();
    tracker.switchTo("room-a", "");
    tracker.switchTo("room-b", "unsent draft for A"); // leaving A, saving its draft

    // Simulates an in-flight send from room A resolving after the reader
    // has already switched to (and started typing in) room B.
    tracker.setDraftFor("room-a", "");

    // Room B's own in-progress text (not yet saved via switchTo) is
    // unaffected — setDraftFor never touches the currently-focused room's
    // live value, only the stored map. Prove it round-trips intact.
    expect(tracker.switchTo("room-c", "typing in B, untouched")).toBe("");
    expect(tracker.switchTo("room-b", "")).toBe("typing in B, untouched");

    // And room A's stale draft is gone, not resurrected on return.
    expect(tracker.switchTo("room-a", "")).toBe("");
  });
});
