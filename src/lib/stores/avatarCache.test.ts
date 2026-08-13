// Coverage for `createAvatarCache`'s contract: fetch lazily and exactly
// once per `mxcUri`, never block on the fetch, refetch when the URI
// changes, and always fall back to `null` (never throw, never leave a
// broken image) on any failure.

import { describe, expect, it, vi } from "vitest";
import { createAvatarCache } from "./avatarCache.svelte";

/** Lets an already-queued microtask (a resolved/rejected fetch) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("createAvatarCache", () => {
  it("returns null and fetches nothing for a null mxc URI", () => {
    const avatarThumbnail = vi.fn();
    const cache = createAvatarCache({ avatarThumbnail });

    expect(cache.get(null)).toBeNull();
    expect(avatarThumbnail).not.toHaveBeenCalled();
  });

  it("returns null before the fetch resolves, then the resolved data URI", async () => {
    const avatarThumbnail = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ avatarThumbnail });

    expect(cache.get("mxc://x.org/1")).toBeNull();
    expect(avatarThumbnail).toHaveBeenCalledWith("mxc://x.org/1");

    await flush();

    expect(cache.get("mxc://x.org/1")).toBe("data:image/png;base64,abc");
  });

  it("fetches a given mxc URI at most once, no matter how many times get is called", async () => {
    const avatarThumbnail = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ avatarThumbnail });

    cache.get("mxc://x.org/1");
    cache.get("mxc://x.org/1");
    cache.get("mxc://x.org/1");
    await flush();
    cache.get("mxc://x.org/1");

    expect(avatarThumbnail).toHaveBeenCalledTimes(1);
  });

  it("caches by mxc URI: two rooms sharing one avatar share one fetch", async () => {
    const avatarThumbnail = vi.fn().mockResolvedValue("data:image/png;base64,shared");
    const cache = createAvatarCache({ avatarThumbnail });

    // Two different rooms whose `avatarUrl` happens to be the same mxc URI
    // (e.g. two DMs with the same other member) must not double-fetch it.
    cache.get("mxc://x.org/shared");
    cache.get("mxc://x.org/shared");
    await flush();

    expect(avatarThumbnail).toHaveBeenCalledTimes(1);
    expect(cache.get("mxc://x.org/shared")).toBe("data:image/png;base64,shared");
  });

  it("refetches when the mxc URI changes", async () => {
    const avatarThumbnail = vi
      .fn()
      .mockResolvedValueOnce("data:image/png;base64,old")
      .mockResolvedValueOnce("data:image/png;base64,new");
    const cache = createAvatarCache({ avatarThumbnail });

    cache.get("mxc://x.org/old");
    await flush();
    expect(cache.get("mxc://x.org/old")).toBe("data:image/png;base64,old");

    // The room's avatar changed to a new mxc URI: a fresh key, so it must
    // fetch again rather than serving the stale cached bytes under the old
    // key (which would simply not be looked up any more) or refusing to
    // fetch because "an avatar was already fetched for this room".
    cache.get("mxc://x.org/new");
    expect(avatarThumbnail).toHaveBeenCalledTimes(2);
    await flush();
    expect(cache.get("mxc://x.org/new")).toBe("data:image/png;base64,new");
  });

  it("falls back to null, without throwing, when the fetch rejects", async () => {
    const avatarThumbnail = vi.fn().mockRejectedValue(new Error("network down"));
    const cache = createAvatarCache({ avatarThumbnail });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(cache.get("mxc://x.org/1")).toBeNull();
    await flush();
    expect(cache.get("mxc://x.org/1")).toBeNull();

    consoleError.mockRestore();
  });

  it("markFailed overrides a resolved avatar back to null", async () => {
    const avatarThumbnail = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ avatarThumbnail });

    cache.get("mxc://x.org/1");
    await flush();
    expect(cache.get("mxc://x.org/1")).toBe("data:image/png;base64,abc");

    // Simulates the <img> itself failing to decode a data: URI the core
    // handed back — the last line of "never show a broken image".
    cache.markFailed("mxc://x.org/1");
    expect(cache.get("mxc://x.org/1")).toBeNull();
  });
});
