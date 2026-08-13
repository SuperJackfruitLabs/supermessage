// Coverage for `createAvatarCache`'s contract: fetch lazily and exactly
// once per room id, unconditionally (never gated on the room having an
// `avatarUrl`), never block on the fetch, and always fall back to `null`
// (never throw, never leave a broken image) on any failure.

import { describe, expect, it, vi } from "vitest";
import { createAvatarCache } from "./avatarCache.svelte";

/** Lets an already-queued microtask (a resolved/rejected fetch) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("createAvatarCache", () => {
  it("returns null before the fetch resolves, then the resolved data URI", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ roomAvatar });

    expect(cache.get("!a:x.org")).toBeNull();
    expect(roomAvatar).toHaveBeenCalledWith("!a:x.org");

    await flush();

    expect(cache.get("!a:x.org")).toBe("data:image/png;base64,abc");
  });

  it("fetches unconditionally, not gated on the room having an avatarUrl", async () => {
    // The bug this cache exists to fix: a room whose avatar is really its
    // other member's profile picture reports `avatarUrl: null` on its
    // `RoomSummary`, yet `room_avatar` can still resolve one. The cache must
    // never use `avatarUrl` to decide whether to call it — it doesn't even
    // take one.
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,agent");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!dm:x.org");

    expect(roomAvatar).toHaveBeenCalledWith("!dm:x.org");
    await flush();
    expect(cache.get("!dm:x.org")).toBe("data:image/png;base64,agent");
  });

  it("fetches a given room at most once, no matter how many times get is called", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org");
    cache.get("!a:x.org");
    cache.get("!a:x.org");
    await flush();
    cache.get("!a:x.org");

    expect(roomAvatar).toHaveBeenCalledTimes(1);
  });

  it("caches independently per room id, even for rooms with no avatar", async () => {
    const roomAvatar = vi.fn().mockResolvedValue(null);
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org");
    cache.get("!b:x.org");
    await flush();

    expect(roomAvatar).toHaveBeenCalledTimes(2);
    expect(roomAvatar).toHaveBeenCalledWith("!a:x.org");
    expect(roomAvatar).toHaveBeenCalledWith("!b:x.org");
    expect(cache.get("!a:x.org")).toBeNull();
    expect(cache.get("!b:x.org")).toBeNull();
  });

  it("falls back to null, without throwing, when the fetch rejects", async () => {
    const roomAvatar = vi.fn().mockRejectedValue(new Error("network down"));
    const cache = createAvatarCache({ roomAvatar });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(cache.get("!a:x.org")).toBeNull();
    await flush();
    expect(cache.get("!a:x.org")).toBeNull();

    consoleError.mockRestore();
  });

  it("markFailed overrides a resolved avatar back to null", async () => {
    const roomAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createAvatarCache({ roomAvatar });

    cache.get("!a:x.org");
    await flush();
    expect(cache.get("!a:x.org")).toBe("data:image/png;base64,abc");

    // Simulates the <img> itself failing to decode a data: URI the core
    // handed back — the last line of "never show a broken image".
    cache.markFailed("!a:x.org");
    expect(cache.get("!a:x.org")).toBeNull();
  });
});
