// Tests for the gap -> resync-in-flight -> reset ordering hazard described
// in `gapSync.ts`'s doc comment. Exercised entirely with fakes for the
// command (`resync`) and event (`subscribe`) functions — no Tauri runtime
// required.

import { describe, expect, it, vi } from "vitest";
import { startGapSync, type Unlisten } from "./gapSync";
import type { DiffEnvelope } from "./diff";

function env<T>(seq: number, ops: DiffEnvelope<T>["ops"]): DiffEnvelope<T> {
  return { channel: "test", subject: "", seq, ops };
}

/** A fake event channel: `subscribe` captures the handler synchronously so
 * tests can drive it with `emit`, mirroring how `@tauri-apps/api`'s `listen`
 * callback fires. */
function makeChannel<T>() {
  let handler: ((env: DiffEnvelope<T>) => void) | null = null;
  let unlistenCalls = 0;
  return {
    subscribe: (onEnvelope: (env: DiffEnvelope<T>) => void): Unlisten => {
      handler = onEnvelope;
      return () => {
        unlistenCalls += 1;
        handler = null;
      };
    },
    emit: (envelope: DiffEnvelope<T>) => handler?.(envelope),
    get unlistenCalls() {
      return unlistenCalls;
    },
  };
}

/** A controllable resync promise, so tests can hold a resync "in flight". */
function makeDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe("startGapSync: steady-state application", () => {
  it("applies sequential envelopes and publishes the running list", () => {
    const channel = makeChannel<number>();
    const updates: number[][] = [];
    startGapSync<number>({
      subscribe: channel.subscribe,
      resync: vi.fn(),
      onUpdate: (items) => updates.push(items),
    });

    channel.emit(env(1, [{ op: "pushBack", value: 1 }]));
    channel.emit(env(2, [{ op: "pushBack", value: 2 }]));

    expect(updates).toEqual([[1], [1, 2]]);
  });
});

describe("startGapSync: gap -> resync-in-flight -> reset ordering", () => {
  it("suspends applying envelopes while a resync is in flight, then resumes from the reset snapshot", async () => {
    const channel = makeChannel<number>();
    const updates: number[][] = [];
    const deferred = makeDeferred<[number, number[]]>();
    const resync = vi.fn(() => deferred.promise);

    startGapSync<number>({
      subscribe: channel.subscribe,
      resync,
      onUpdate: (items) => updates.push(items),
    });

    channel.emit(env(1, [{ op: "pushBack", value: 1 }]));
    expect(updates.at(-1)).toEqual([1]);

    // seq jumps to 5 — a gap. Triggers exactly one resync.
    channel.emit(env(5, []));
    expect(resync).toHaveBeenCalledTimes(1);

    // While the resync is in flight, further envelopes on the same channel
    // — including ones that would themselves look like a gap or a normal
    // next-in-sequence envelope — must be ignored entirely: no state
    // change, and critically no second resync call.
    channel.emit(env(6, [{ op: "pushBack", value: 999 }]));
    channel.emit(env(2, [{ op: "pushBack", value: 999 }]));
    expect(resync).toHaveBeenCalledTimes(1);
    expect(updates.at(-1)).toEqual([1]);

    // The resync lands: hard reset to its snapshot.
    deferred.resolve([5, [7, 8]]);
    await vi.waitFor(() => expect(updates.at(-1)).toEqual([7, 8]));

    // The core guarantees the next live envelope after a resync is
    // exactly `seq + 1` — apply it normally, no further gap.
    channel.emit(env(6, [{ op: "pushBack", value: 9 }]));
    expect(updates.at(-1)).toEqual([7, 8, 9]);
    expect(resync).toHaveBeenCalledTimes(1);
  });

  it("never issues two overlapping resyncs for the same channel", () => {
    const channel = makeChannel<number>();
    const deferred = makeDeferred<[number, number[]]>();
    const resync = vi.fn(() => deferred.promise);

    startGapSync<number>({
      subscribe: channel.subscribe,
      resync,
      onUpdate: () => {},
    });

    // Multiple gap-triggering envelopes arrive before the first resync
    // has a chance to resolve.
    channel.emit(env(5, []));
    channel.emit(env(9, []));
    channel.emit(env(12, []));

    expect(resync).toHaveBeenCalledTimes(1);
  });
});

describe("startGapSync: resetForNewSubscription", () => {
  it("publishes an empty list immediately and discards a resync that was already in flight", async () => {
    const channel = makeChannel<string>();
    const updates: string[][] = [];
    const deferred = makeDeferred<[number, string[]]>();
    const resync = vi.fn(() => deferred.promise);

    const sync = startGapSync<string>({
      subscribe: channel.subscribe,
      resync,
      onUpdate: (items) => updates.push(items),
    });

    // A gap starts a resync for the "old" context (e.g. the previously
    // focused room).
    channel.emit(env(5, []));
    expect(resync).toHaveBeenCalledTimes(1);

    // The caller switches context (e.g. selects a different room) before
    // that resync has resolved.
    sync.resetForNewSubscription();
    expect(updates.at(-1)).toEqual([]);

    // The stale resync now resolves with data belonging to the old
    // context. It must not be allowed to clobber the freshly reset state.
    deferred.resolve([5, ["stale"]]);
    await vi.waitFor(() => expect(resync).toHaveBeenCalledTimes(1));
    // Give the (discarded) continuation a turn to run before asserting it
    // had no effect.
    await new Promise((r) => setTimeout(r, 0));
    expect(updates.at(-1)).toEqual([]);

    // The fresh subscription starts back at seq 1, and applies normally.
    channel.emit(env(1, [{ op: "pushBack", value: "a" }]));
    expect(updates.at(-1)).toEqual(["a"]);
  });

  it("allows a fresh gap in the new context to trigger its own resync once the stale one clears", async () => {
    const channel = makeChannel<string>();
    const updates: string[][] = [];
    let call = 0;
    const deferreds = [makeDeferred<[number, string[]]>(), makeDeferred<[number, string[]]>()];
    const resync = vi.fn(() => deferreds[call++].promise);

    const sync = startGapSync<string>({
      subscribe: channel.subscribe,
      resync,
      onUpdate: (items) => updates.push(items),
    });

    channel.emit(env(5, [])); // gap #1 -> resync #1 in flight
    sync.resetForNewSubscription(); // switch context while #1 is pending

    // A gap in the new context is suspended behind the still-pending
    // stale resync (it occupies the "resync in flight" guard), which is
    // the same suspend-until-it-lands rule applied uniformly rather than
    // special-cased for context switches.
    channel.emit(env(3, []));
    expect(resync).toHaveBeenCalledTimes(1);

    // Once the stale resync clears (and is discarded, per the previous
    // test), the new context's own gap can now be served.
    deferreds[0].resolve([5, ["stale"]]);
    await vi.waitFor(() => expect(updates.at(-1)).toEqual([]));

    channel.emit(env(4, [])); // a fresh gap in the new context
    expect(resync).toHaveBeenCalledTimes(2);
    deferreds[1].resolve([4, ["fresh"]]);
    await vi.waitFor(() => expect(updates.at(-1)).toEqual(["fresh"]));
  });
});

describe("startGapSync: unlisten", () => {
  it("stops applying envelopes and unsubscribes from the channel", async () => {
    const channel = makeChannel<number>();
    const updates: number[][] = [];
    const sync = startGapSync<number>({
      subscribe: channel.subscribe,
      resync: vi.fn(),
      onUpdate: (items) => updates.push(items),
    });

    channel.emit(env(1, [{ op: "pushBack", value: 1 }]));
    sync.unlisten();
    // The internal unlisten handoff goes through a microtask.
    await vi.waitFor(() => expect(channel.unlistenCalls).toBe(1));

    channel.emit(env(2, [{ op: "pushBack", value: 2 }]));
    expect(updates.at(-1)).toEqual([1]);
  });
});
