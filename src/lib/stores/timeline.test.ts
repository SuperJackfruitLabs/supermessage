// Regression test for the room-switch race described in
// `timeline.svelte.ts`'s module doc comment: a room-A envelope arriving in
// the window between `subscribeTo("!b")` resetting local tracking and the
// core actually installing room B's subscription.
//
// Before this was fixed, that envelope read as a gap (seq 12 against a
// tracker expecting 1), the resync it triggered — a fast mutex read — beat
// the slow `room.timeline()` build and was served out of room A's
// still-installed handle, and room B's stream then started at seq 1 and was
// discarded as duplicates. Room A's messages rendered under room B's header
// until the next room switch.
//
// The fix is that the timeline channel's `subject` (the room id, stamped by
// the core on every envelope and now returned by `timeline_resync` too) is
// actually read: anything whose subject isn't the focused room is dropped,
// rather than mistaken for a gap in the focused room's stream.
//
// Fakes only — no Tauri runtime.

import { describe, expect, it, vi } from "vitest";
import { createTimelineStore } from "./timeline.svelte";
import type { DiffEnvelope } from "./diff";
import type { TimelineItem, TimelineRow } from "$lib/ipc";

const ROOM_A = "!a:example.org";
const ROOM_B = "!b:example.org";

/**
 * A row, as the core now delivers them. The store tracks whole rows, so a
 * fixture that produced a bare DTO would be testing a shape the channel no
 * longer carries.
 */
function item(id: string): TimelineRow {
  return {
    item: dto(id),
    view: { render: "bubble", muted: false, blocks: [] },
    senderName: "@someone:example.org",
    membershipVerb: null,
    replyQuote: null,
    canReplyOrReact: true,
    replyPreview: null,
  };
}

function dto(id: string): TimelineItem {
  return {
    id,
    kind: "message",
    msgtype: "m.text",
    detail: null,
    sender: "@someone:example.org",
    senderDisplayName: null,
    body: id,
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
  };
}

function env(
  subject: string,
  seq: number,
  ops: DiffEnvelope<TimelineRow>["ops"],
): DiffEnvelope<TimelineRow> {
  return { channel: "timeline", subject, seq, ops };
}

/** Fake `sm://timeline/diff` channel; captures the handler synchronously. */
function makeChannel() {
  let handler: ((env: DiffEnvelope<TimelineRow>) => void) | null = null;
  return {
    onTimelineDiff: (onEnvelope: (env: DiffEnvelope<TimelineRow>) => void) => {
      handler = onEnvelope;
      return Promise.resolve(() => {
        handler = null;
      });
    },
    emit: (envelope: DiffEnvelope<TimelineRow>) => handler?.(envelope),
  };
}

/** A promise the test resolves by hand, to hold a command "in flight". */
function makeDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Lets already-queued microtasks (a resolved resync's continuation) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("timelineStore: switching rooms while the previous room is still streaming", () => {
  it("ignores the previous room's envelopes and its resync snapshot", async () => {
    const channel = makeChannel();

    // `timeline_subscribe` for room B is slow — it has to build
    // `room.timeline()`. Held open so the test can drive the exact window
    // the bug lived in.
    const subscribeB = makeDeferred<void>();
    const timelineSubscribe = vi.fn(async (roomId: string) => {
      if (roomId === ROOM_B) await subscribeB.promise;
    });

    // `timeline_resync` is a mutex read, so it is fast — and while room B's
    // subscribe is still in flight, the core serves it out of room A's
    // still-installed handle, at room A's high seq. That is exactly what
    // this fake returns.
    const timelineResync = vi.fn(
      async () => [ROOM_A, 12, [item("a1"), item("a2")]] as [string, number, TimelineRow[]],
    );

    const store = createTimelineStore({
      timelineSubscribe,
      timelinePaginateBack: vi.fn(),
      timelineResync,
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
      onTimelineDiff: channel.onTimelineDiff,
    });

    // Room A is focused and has been streaming for a while.
    await store.subscribeTo(ROOM_A);
    channel.emit(env(ROOM_A, 1, [{ op: "reset", values: [item("a1")] }]));
    channel.emit(env(ROOM_A, 2, [{ op: "pushBack", value: item("a2") }]));
    expect(store.items.map((row) => row.item.id)).toEqual(["a1", "a2"]);

    // The user clicks room B. The subscribe command is now in flight.
    const switching = store.subscribeTo(ROOM_B);
    expect(store.items).toEqual([]);

    // Room A's subscription has not been replaced yet, and emits again.
    channel.emit(env(ROOM_A, 3, [{ op: "pushBack", value: item("a3") }]));
    await flush();

    // Dropped as not-ours. Critically it must not have been read as a gap:
    // a gap here resyncs off room A and installs A's items at A's seq.
    expect(timelineResync).not.toHaveBeenCalled();
    expect(store.items).toEqual([]);

    // Room B's subscription is finally installed and starts at seq 1.
    subscribeB.resolve();
    await switching;
    channel.emit(env(ROOM_B, 1, [{ op: "reset", values: [item("b1")] }]));
    channel.emit(env(ROOM_B, 2, [{ op: "pushBack", value: item("b2") }]));

    // The pane shows room B. Before the fix it showed room A's messages
    // here, permanently — B's seq 1 and 2 were below the expected sequence
    // the stale resync had left behind, so both were discarded as
    // duplicates.
    expect(store.items.map((row) => row.item.id)).toEqual(["b1", "b2"]);
  });

  it("discards a resync snapshot that resolves for the room we just left", async () => {
    const channel = makeChannel();

    const subscribeB = makeDeferred<void>();
    const timelineSubscribe = vi.fn(async (roomId: string) => {
      if (roomId === ROOM_B) await subscribeB.promise;
    });

    const resyncResult = makeDeferred<[string, number, TimelineRow[]]>();
    const timelineResync = vi.fn(() => resyncResult.promise);

    const store = createTimelineStore({
      timelineSubscribe,
      timelinePaginateBack: vi.fn(),
      timelineResync,
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
      onTimelineDiff: channel.onTimelineDiff,
    });

    await store.subscribeTo(ROOM_A);
    channel.emit(env(ROOM_A, 1, [{ op: "reset", values: [item("a1")] }]));

    // A genuine gap in room A's own stream starts a resync.
    channel.emit(env(ROOM_A, 9, []));
    expect(timelineResync).toHaveBeenCalledTimes(1);

    // The user switches to room B before it lands, and it then resolves
    // with room A's snapshot.
    const switching = store.subscribeTo(ROOM_B);
    resyncResult.resolve([ROOM_A, 9, [item("a1"), item("a9")]]);
    await flush();

    expect(store.items).toEqual([]);

    subscribeB.resolve();
    await switching;
    channel.emit(env(ROOM_B, 1, [{ op: "reset", values: [item("b1")] }]));
    expect(store.items.map((row) => row.item.id)).toEqual(["b1"]);
  });
});

describe("timelineStore: toggleReaction", () => {
  it("round-trips the IPC call's arguments and return value without touching items", async () => {
    const channel = makeChannel();
    const toggleReaction = vi.fn(async (roomId: string, eventId: string, key: string) => {
      expect(roomId).toBe(ROOM_A);
      expect(eventId).toBe("$e1:example.org");
      expect(key).toBe("👍");
      return true;
    });

    const store = createTimelineStore({
      timelineSubscribe: vi.fn(),
      timelinePaginateBack: vi.fn(),
      timelineResync: vi.fn(),
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction,
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
      onTimelineDiff: channel.onTimelineDiff,
    });

    await store.subscribeTo(ROOM_A);
    channel.emit(env(ROOM_A, 1, [{ op: "reset", values: [item("$e1:example.org")] }]));
    const before = store.items;

    const added = await store.toggleReaction(ROOM_A, "$e1:example.org", "👍");

    expect(added).toBe(true);
    expect(toggleReaction).toHaveBeenCalledTimes(1);
    expect(toggleReaction).toHaveBeenCalledWith(ROOM_A, "$e1:example.org", "👍");
    // No optimistic update: the store never appends/mutates on its own —
    // only a diff arriving over `onTimelineDiff` changes `items` (see
    // `Timeline.svelte`'s and this store's doc comments for why).
    expect(store.items).toBe(before);
  });

  it("resolves false when the reaction was removed, still without touching items", async () => {
    const channel = makeChannel();
    const toggleReaction = vi.fn(async () => false);

    const store = createTimelineStore({
      timelineSubscribe: vi.fn(),
      timelinePaginateBack: vi.fn(),
      timelineResync: vi.fn(),
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction,
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
      onTimelineDiff: channel.onTimelineDiff,
    });

    await store.subscribeTo(ROOM_A);
    const removed = await store.toggleReaction(ROOM_A, "$e1:example.org", "👍");
    expect(removed).toBe(false);
  });
});

describe("timelineStore: sendReply", () => {
  it("forwards the room id, body and the parent event id to the IPC call unchanged", async () => {
    const sendReply = vi.fn(async () => undefined);
    const store = createTimelineStore({
      timelineSubscribe: vi.fn(),
      timelinePaginateBack: vi.fn(),
      timelineResync: vi.fn(),
      sendMessage: vi.fn(),
      sendReply,
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
      onTimelineDiff: vi.fn(async () => () => {}),
    });

    await store.sendReply(ROOM_A, "hello", "$parent:example.org");

    expect(sendReply).toHaveBeenCalledTimes(1);
    expect(sendReply).toHaveBeenCalledWith(ROOM_A, "hello", "$parent:example.org");
  });
});

// `loaded` — whether this room has answered yet, as distinct from whether it
// has anything in it.
//
// The pane had no way to tell those apart and rendered "Nothing here yet."
// for the gap between the two, over rooms with a full history. Measured on
// 2026-08-17: the message appeared 10ms into a room switch and was gone by
// 66ms. See `timelinePane.ts`.
describe("timelineStore: knowing whether a room has answered", () => {
  it("has not heard from a room it has only just subscribed to", async () => {
    const channel = makeChannel();
    const store = createTimelineStore({
      onTimelineDiff: channel.onTimelineDiff,
      timelineResync: vi.fn(),
      timelineSubscribe: vi.fn().mockResolvedValue(undefined),
      timelinePaginateBack: vi.fn(),
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
    });

    expect(store.loaded).toBe(false);
    await store.subscribeTo(ROOM_A);
    expect(store.loaded).toBe(false);
  });

  it("has heard from a room the moment its first batch lands, even an empty one", async () => {
    const channel = makeChannel();
    const store = createTimelineStore({
      onTimelineDiff: channel.onTimelineDiff,
      timelineResync: vi.fn(),
      timelineSubscribe: vi.fn().mockResolvedValue(undefined),
      timelinePaginateBack: vi.fn(),
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
    });

    await store.subscribeTo(ROOM_A);
    // The core opens every subscription with a `Reset`, which for a genuinely
    // empty room carries nothing. That is still an answer, and the pane is
    // entitled to say the room is empty on the strength of it.
    channel.emit(env(ROOM_A, 1, [{ op: "reset", values: [] }]));

    expect(store.loaded).toBe(true);
    expect(store.items).toEqual([]);
  });

  it("forgets it has heard anything when the reader switches rooms", async () => {
    const channel = makeChannel();
    const store = createTimelineStore({
      onTimelineDiff: channel.onTimelineDiff,
      timelineResync: vi.fn(),
      timelineSubscribe: vi.fn().mockResolvedValue(undefined),
      timelinePaginateBack: vi.fn(),
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
    });

    await store.subscribeTo(ROOM_A);
    channel.emit(env(ROOM_A, 1, [{ op: "reset", values: [item("$a")] }]));
    expect(store.loaded).toBe(true);

    // The whole point: without this the next room inherits room A's answer and
    // the pane would render *its* emptiness as fact.
    await store.subscribeTo(ROOM_B);
    expect(store.loaded).toBe(false);
    expect(store.items).toEqual([]);
  });

  it("is not fooled by the outgoing room still streaming", async () => {
    const channel = makeChannel();
    const store = createTimelineStore({
      onTimelineDiff: channel.onTimelineDiff,
      timelineResync: vi.fn(),
      timelineSubscribe: vi.fn().mockResolvedValue(undefined),
      timelinePaginateBack: vi.fn(),
      sendMessage: vi.fn(),
      sendReply: vi.fn(),
      toggleReaction: vi.fn(),
      setTyping: vi.fn(),
      markRoomRead: vi.fn(),
    });

    await store.subscribeTo(ROOM_A);
    channel.emit(env(ROOM_A, 1, [{ op: "reset", values: [item("$a")] }]));
    await store.subscribeTo(ROOM_B);

    // Room A's subscription is still alive in the core for a moment. Its
    // envelopes are already rejected as somebody else's data (see the top of
    // this file); they must not count as room B answering either.
    channel.emit(env(ROOM_A, 2, [{ op: "pushBack", value: item("$a2") }]));

    expect(store.loaded).toBe(false);
  });
});
