// Regression coverage for the reload case.
//
// `sm://connection` is emitted on *transitions*. The core reaches `Running`
// seconds after login and then, if nothing goes wrong, never transitions
// again — so a webview that reloads (right-click → Reload, or vite's HMR
// throwing away the module graph) starts a brand-new store at its `offline`
// default and waits forever for an event that is not coming. The banner then
// reads "Offline" over a perfectly live connection, which is the one lie a
// connection indicator must never tell.
//
// The fix is to ask on startup rather than only listen. What these tests pin
// down is the ordering hazard that asking introduces: the answer is a
// snapshot taken before the query returns, so it must never be allowed to
// overwrite an event that arrived while it was in flight.
//
// Fakes only — no Tauri runtime.

import { describe, expect, it } from "vitest";
import { createConnectionStore } from "./connection.svelte";
import type { ConnectionPayload } from "$lib/ipc";

/** Fake `sm://connection` channel; captures the handler synchronously. */
function makeChannel() {
  let handler: ((payload: ConnectionPayload) => void) | null = null;
  return {
    onConnection: (onPayload: (payload: ConnectionPayload) => void) => {
      handler = onPayload;
      return Promise.resolve(() => {
        handler = null;
      });
    },
    emit: (payload: ConnectionPayload) => handler?.(payload),
  };
}

/** A `connection_state` query resolved by hand, so ordering can be tested. */
function deferredQuery() {
  let settle: (payload: ConnectionPayload) => void = () => {};
  const promise = new Promise<ConnectionPayload>((resolve) => {
    settle = resolve;
  });
  return { query: () => promise, settle };
}

describe("connectionStore", () => {
  it("is offline until it knows better", () => {
    const channel = makeChannel();
    const store = createConnectionStore({
      onConnection: channel.onConnection,
      connectionState: () => new Promise<ConnectionPayload>(() => {}),
    });

    expect(store.state).toBe("offline");
  });

  it("adopts the state the core reports when asked, with no event in sight", async () => {
    const channel = makeChannel();
    const asked = deferredQuery();
    const store = createConnectionStore({
      onConnection: channel.onConnection,
      connectionState: asked.query,
    });

    asked.settle({ state: "live", message: null });
    await asked.query();
    await Promise.resolve();

    expect(store.state).toBe("live");
  });

  it("keeps an event that arrived while the query was in flight", async () => {
    const channel = makeChannel();
    const asked = deferredQuery();
    const store = createConnectionStore({
      onConnection: channel.onConnection,
      connectionState: asked.query,
    });

    // The core went down between the question and the answer. The answer is
    // a snapshot of the older moment, and applying it would resurrect a
    // connection that is gone.
    channel.emit({ state: "error", message: "the server closed the stream" });
    asked.settle({ state: "live", message: null });
    await asked.query();
    await Promise.resolve();

    expect(store.state).toBe("error");
    expect(store.message).toBe("the server closed the stream");
  });

  it("still follows events after the query has answered", async () => {
    const channel = makeChannel();
    const asked = deferredQuery();
    const store = createConnectionStore({
      onConnection: channel.onConnection,
      connectionState: asked.query,
    });

    asked.settle({ state: "live", message: null });
    await asked.query();
    await Promise.resolve();

    channel.emit({ state: "offline", message: null });

    expect(store.state).toBe("offline");
  });
});
