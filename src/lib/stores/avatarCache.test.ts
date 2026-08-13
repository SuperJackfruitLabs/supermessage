// Coverage for `createAvatarCache`'s contract: fetch lazily and exactly
// once per `avatarUrl`, never block on the fetch, refetch when `avatarUrl`
// changes, and always fall back to `null` (never throw, never leave a
// broken image) on any failure.

import { describe, expect, it, vi } from "vitest";
import { createAvatarCache } from "./avatarCache.svelte";

/** Lets an already-queued microtask (a resolved/rejected fetch) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("createAvatarCache", () => {
  it("returns null and fetches nothing for a room with no avatar", () => {
    const roomAvatar = vi.fn();
    const cache = createAvatarCache({ roomAvatar });

    expect(cache.get("!a:x.org", null)).toBeNull();
    expect(roomAvatar).not.toHaveBeenCalled();
  });

  it("returns null before the fetch resolves, then the resolved data URI", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ roomAvatar });

    expect(cache.get("!a:x.org", "mxc://x.org/1")).toBeNull();
    expect(roomAvatar).toHaveBeenCalledWith("!a:x.org");

    await flush();

    expect(cache.get("!a:x.org", "mxc://x.org/1")).toBe("data:image/png;base64,abc");
  });

  it("fetches a given avatarUrl at most once, no matter how many times get is called", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org", "mxc://x.org/1");
    cache.get("!a:x.org", "mxc://x.org/1");
    cache.get("!a:x.org", "mxc://x.org/1");
    await flush();
    cache.get("!a:x.org", "mxc://x.org/1");

    expect(roomAvatar).toHaveBeenCalledTimes(1);
  });

  it("caches by avatarUrl, not room id: two rooms sharing one mxc URI share one fetch", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,shared");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org", "mxc://x.org/shared");
    cache.get("!b:x.org", "mxc://x.org/shared");
    await flush();

    expect(roomAvatar).toHaveBeenCalledTimes(1);
    expect(cache.get("!a:x.org", "mxc://x.org/shared")).toBe("data:image/png;base64,shared");
    expect(cache.get("!b:x.org", "mxc://x.org/shared")).toBe("data:image/png;base64,shared");
  });

  it("refetches when a room's avatarUrl changes", async () => {
    const roomAvatar = vi
      .fn()
      .mockResolvedValueOnce("data:image/png;base64,old")
      .mockResolvedValueOnce("data:image/png;base64,new");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org", "mxc://x.org/old");
    await flush();
    expect(cache.get("!a:x.org", "mxc://x.org/old")).toBe("data:image/png;base64,old");

    // The room's avatar changed to a new mxc URI: a fresh key, so it must
    // fetch again rather than serving the stale cached bytes under the old
    // key (which would simply not be looked up any more) or refusing to
    // fetch because "an avatar was already fetched for this room".
    cache.get("!a:x.org", "mxc://x.org/new");
    expect(roomAvatar).toHaveBeenCalledTimes(2);
    await flush();
    expect(cache.get("!a:x.org", "mxc://x.org/new")).toBe("data:image/png;base64,new");
  });

  it("falls back to null, without throwing, when the fetch rejects", async () => {
    const roomAvatar = vi.fn().mockRejectedValue(new Error("network down"));
    const cache = createAvatarCache({ roomAvatar });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(cache.get("!a:x.org", "mxc://x.org/1")).toBeNull();
    await flush();
    expect(cache.get("!a:x.org", "mxc://x.org/1")).toBeNull();

    consoleError.mockRestore();
  });

  it("markFailed overrides a resolved avatar back to null", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org", "mxc://x.org/1");
    await flush();
    expect(cache.get("!a:x.org", "mxc://x.org/1")).toBe("data:image/png;base64,abc");

    // Simulates the <img> itself failing to decode a data: URI the core
    // handed back — the last line of "never show a broken image".
    cache.markFailed("mxc://x.org/1");
    expect(cache.get("!a:x.org", "mxc://x.org/1")).toBeNull();
  });
});
