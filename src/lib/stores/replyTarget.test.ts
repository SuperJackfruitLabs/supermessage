// Regression coverage for the hazard `replyTarget.svelte.ts`'s doc comment
// describes: a pending reply target that leaks across a room switch would
// let a reply typed after switching rooms still carry an `in_reply_to`
// event id from the room that was focused when "Reply" was clicked — the
// same class of bug `draftTracker.test.ts` covers for drafts, but worse,
// since the resulting reply could name an event id that doesn't exist in
// the room it's actually posted to.
//
// Fakes only — no Tauri runtime, no Svelte component mounting (this
// project's vitest runs with `environment: "node"`; `$state` still works
// directly in a plain module, see `createReplyTargetStore`'s doc comment).

import { describe, expect, it } from "vitest";
import { createReplyTargetStore, type PendingReply } from "./replyTarget.svelte";
import type { TimelineItem, TimelineRow } from "$lib/ipc";

const ROOM_A = "!a:example.org";
const ROOM_B = "!b:example.org";

function pendingReply(eventId: string): PendingReply {
  return { eventId, sender: "Alice", excerpt: "hello" };
}

describe("replyTargetStore: per-room scoping", () => {
  it("starts with no pending reply for any room", () => {
    const store = createReplyTargetStore();
    expect(store.get(ROOM_A)).toBeNull();
    expect(store.get(ROOM_B)).toBeNull();
  });

  it("does not leak a reply target set for one room into another", () => {
    const store = createReplyTargetStore();

    // The reader clicks "Reply" on a message in room A.
    store.set(ROOM_A, pendingReply("$a1:example.org"));

    // Room B — which the reader may switch to next — must not see it. This
    // is the exact scenario the task brief calls out: replying to an event
    // from room A while room B is focused would target an event id that
    // isn't even in room B.
    expect(store.get(ROOM_B)).toBeNull();
    expect(store.get(ROOM_A)).toEqual(pendingReply("$a1:example.org"));
  });

  it("keeps each room's target independent when both have one set", () => {
    const store = createReplyTargetStore();
    store.set(ROOM_A, pendingReply("$a1:example.org"));
    store.set(ROOM_B, pendingReply("$b1:example.org"));

    expect(store.get(ROOM_A)?.eventId).toBe("$a1:example.org");
    expect(store.get(ROOM_B)?.eventId).toBe("$b1:example.org");
  });

  it("restores a room's own pending reply after switching away and back", () => {
    // Mirrors `draftTracker.test.ts`'s round-trip: room A gets a target,
    // the reader switches to B (which must show nothing pending), then
    // switches back to A, which must still show its own target — not B's,
    // and not nothing.
    const store = createReplyTargetStore();
    store.set(ROOM_A, pendingReply("$a1:example.org"));

    expect(store.get(ROOM_B)).toBeNull(); // "switch to B"
    expect(store.get(ROOM_A)).toEqual(pendingReply("$a1:example.org")); // "switch back to A"
  });

  it("clearing one room's target leaves every other room's untouched", () => {
    const store = createReplyTargetStore();
    store.set(ROOM_A, pendingReply("$a1:example.org"));
    store.set(ROOM_B, pendingReply("$b1:example.org"));

    store.clear(ROOM_A);

    expect(store.get(ROOM_A)).toBeNull();
    expect(store.get(ROOM_B)).toEqual(pendingReply("$b1:example.org"));
  });

  it("clearing a room with no pending target is a safe no-op", () => {
    const store = createReplyTargetStore();
    expect(() => store.clear(ROOM_A)).not.toThrow();
    expect(store.get(ROOM_A)).toBeNull();
  });

  it("overwrites a room's previous target with a new one rather than merging", () => {
    const store = createReplyTargetStore();
    store.set(ROOM_A, pendingReply("$a1:example.org"));
    store.set(ROOM_A, pendingReply("$a2:example.org"));

    expect(store.get(ROOM_A)?.eventId).toBe("$a2:example.org");
  });
});

describe("replyTargetStore: fromItem", () => {
  /**
   * A row as the core delivers one.
   *
   * `senderName` and `replyPreview` are the core's answers now — the
   * attribution chain and the excerpt's bounding live in `core::item_view`,
   * with their own tests. What is left to check here, and what these tests
   * still catch, is that `fromItem` reads the right field: handing the
   * composer a raw sender id where a display name was resolved, or the body
   * where a bounded preview was, is a real bug and a plausible one.
   */
  function row(overrides: Partial<TimelineItem> = {}): TimelineRow {
    const dto = item(overrides);
    return {
      item: dto,
      view: { render: "bubble", muted: false, blocks: [] },
      senderName: dto.senderDisplayName ?? dto.sender ?? "Someone",
      membershipVerb: null,
      replyQuote: null,
      canReplyOrReact: true,
      replyPreview: dto.body === null || dto.body.trim() === "" ? null : dto.body.trim(),
    };
  }

  function item(overrides: Partial<TimelineItem> = {}): TimelineItem {
    return {
      id: "$e1:example.org",
      kind: "message",
      msgtype: "m.text",
      detail: null,
      sender: "@alice:example.org",
      senderDisplayName: "Alice",
      body: "hello there",
      formattedBody: null,
      media: null,
      customPayload: null,
      timestampMs: 1_700_000_000_000,
      isOwn: false,
      sendState: null,
      replyTo: null,
      edited: false,
      reactions: [],
      readBy: [],
      ...overrides,
    };
  }

  it("shows the name the core resolved, not a chain of its own", () => {
    const store = createReplyTargetStore();
    expect(store.fromItem(row()).sender).toBe("Alice");
    expect(store.fromItem(row({ senderDisplayName: null })).sender).toBe("@alice:example.org");
    expect(store.fromItem(row({ senderDisplayName: null, sender: null })).sender).toBe("Someone");
  });

  it("carries the event id through as the reply target", () => {
    const store = createReplyTargetStore();
    expect(store.fromItem(row({ id: "$xyz:example.org" })).eventId).toBe("$xyz:example.org");
  });

  it("shows the preview the core bounded, not the raw body", () => {
    const store = createReplyTargetStore();
    expect(store.fromItem(row({ body: "hello there" })).excerpt).toBe("hello there");
    expect(store.fromItem(row({ body: null })).excerpt).toBeNull();
  });
});
