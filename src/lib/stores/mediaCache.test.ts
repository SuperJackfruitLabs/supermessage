// Coverage for `createMediaCache`'s contract: fetch lazily and exactly once
// per event id, never block on the fetch, and converge every failure mode
// (core returns null, fetch rejects, `<img>` itself fails to decode) on the
// same `hasFailed` signal rather than leaving the caller to guess. Mirrors
// `avatarCache.test.ts`'s shape, plus the `hasFailed`-vs-`get` distinction
// that cache doesn't need — see `mediaCache.svelte.ts`'s doc comment for why.

import { describe, expect, it, vi } from "vitest";
import { createMediaCache } from "./mediaCache.svelte";

/** Lets an already-queued microtask (a resolved/rejected fetch) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("createMediaCache", () => {
  it("returns null before the fetch resolves, then the resolved data URI", async () => {
    const mediaFetch = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMediaCache({ mediaFetch });

    expect(cache.get("$event1:x.org")).toBeNull();
    expect(cache.hasFailed("$event1:x.org")).toBe(false);
    expect(mediaFetch).toHaveBeenCalledWith("$event1:x.org");

    await flush();

    expect(cache.get("$event1:x.org")).toBe("data:image/png;base64,abc");
    expect(cache.hasFailed("$event1:x.org")).toBe(false);
  });

  it("fetches a given event at most once, no matter how many times get is called", async () => {
    const mediaFetch = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMediaCache({ mediaFetch });

    cache.get("$event1:x.org");
    cache.get("$event1:x.org");
    cache.get("$event1:x.org");
    await flush();
    cache.get("$event1:x.org");

    expect(mediaFetch).toHaveBeenCalledTimes(1);
  });

  it("caches independently per event id", async () => {
    const mediaFetch = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMediaCache({ mediaFetch });

    cache.get("$event1:x.org");
    cache.get("$event2:x.org");
    await flush();

    expect(mediaFetch).toHaveBeenCalledTimes(2);
    expect(mediaFetch).toHaveBeenCalledWith("$event1:x.org");
    expect(mediaFetch).toHaveBeenCalledWith("$event2:x.org");
  });

  it("marks the event failed when the core resolves with null (nothing renderable)", async () => {
    const mediaFetch = vi.fn().mockResolvedValue(null);
    const cache = createMediaCache({ mediaFetch });

    expect(cache.get("$event1:x.org")).toBeNull();
    expect(cache.hasFailed("$event1:x.org")).toBe(false); // still in flight

    await flush();

    expect(cache.get("$event1:x.org")).toBeNull();
    expect(cache.hasFailed("$event1:x.org")).toBe(true);
  });

  it("marks the event failed, without throwing, when the fetch rejects", async () => {
    const mediaFetch = vi.fn().mockRejectedValue(new Error("network down"));
    const cache = createMediaCache({ mediaFetch });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(cache.get("$event1:x.org")).toBeNull();
    await flush();

    expect(cache.get("$event1:x.org")).toBeNull();
    expect(cache.hasFailed("$event1:x.org")).toBe(true);

    consoleError.mockRestore();
  });

  it("markFailed overrides a resolved image back to failed", async () => {
    const mediaFetch = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMediaCache({ mediaFetch });

    cache.get("$event1:x.org");
    await flush();
    expect(cache.get("$event1:x.org")).toBe("data:image/png;base64,abc");
    expect(cache.hasFailed("$event1:x.org")).toBe(false);

    // Simulates the <img> itself failing to decode a data: URI the core
    // handed back — the last line of "never show a broken image".
    cache.markFailed("$event1:x.org");
    expect(cache.get("$event1:x.org")).toBeNull();
    expect(cache.hasFailed("$event1:x.org")).toBe(true);
  });

  it("never marks an event failed while its fetch is still in flight", () => {
    const mediaFetch = vi.fn().mockReturnValue(new Promise(() => {})); // never resolves
    const cache = createMediaCache({ mediaFetch });

    cache.get("$event1:x.org");
    expect(cache.hasFailed("$event1:x.org")).toBe(false);
  });
});
