// Coverage for `createMemberAvatarCache`'s contract: fetch lazily and
// exactly once per mxc URI, never block on the fetch, and always fall back
// to `null` (never throw, never leave a broken image) on any failure —
// mirrors `avatarCache.test.ts`'s coverage of the same contract, keyed on
// mxc URI instead of room id.

import { describe, expect, it, vi } from "vitest";
import { createMemberAvatarCache } from "./memberAvatarCache.svelte";

/** Lets an already-queued microtask (a resolved/rejected fetch) run. */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 0));
}

describe("createMemberAvatarCache", () => {
  it("returns null before the fetch resolves, then the resolved data URI", async () => {
    const memberAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMemberAvatarCache({ memberAvatar });

    expect(cache.get("mxc://x.org/a")).toBeNull();
    expect(memberAvatar).toHaveBeenCalledWith("mxc://x.org/a");

    await flush();

    expect(cache.get("mxc://x.org/a")).toBe("data:image/png;base64,abc");
  });

  it("fetches a given mxc URI at most once, no matter how many times get is called", async () => {
    const memberAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMemberAvatarCache({ memberAvatar });

    cache.get("mxc://x.org/a");
    cache.get("mxc://x.org/a");
    cache.get("mxc://x.org/a");
    await flush();
    cache.get("mxc://x.org/a");

    expect(memberAvatar).toHaveBeenCalledTimes(1);
  });

  it("caches independently per mxc URI, even when the fetch resolves to nothing", async () => {
    const memberAvatar = vi.fn().mockResolvedValue(null);
    const cache = createMemberAvatarCache({ memberAvatar });

    cache.get("mxc://x.org/a");
    cache.get("mxc://x.org/b");
    await flush();

    expect(memberAvatar).toHaveBeenCalledTimes(2);
    expect(memberAvatar).toHaveBeenCalledWith("mxc://x.org/a");
    expect(memberAvatar).toHaveBeenCalledWith("mxc://x.org/b");
    expect(cache.get("mxc://x.org/a")).toBeNull();
    expect(cache.get("mxc://x.org/b")).toBeNull();
  });

  it("falls back to null, without throwing, when the fetch rejects", async () => {
    const memberAvatar = vi.fn().mockRejectedValue(new Error("network down"));
    const cache = createMemberAvatarCache({ memberAvatar });
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    expect(cache.get("mxc://x.org/a")).toBeNull();
    await flush();
    expect(cache.get("mxc://x.org/a")).toBeNull();

    consoleError.mockRestore();
  });

  it("markFailed overrides a resolved avatar back to null", async () => {
    const memberAvatar = vi.fn().mockResolvedValue("data:image/png;base64,abc");
    const cache = createMemberAvatarCache({ memberAvatar });

    cache.get("mxc://x.org/a");
    await flush();
    expect(cache.get("mxc://x.org/a")).toBe("data:image/png;base64,abc");

    cache.markFailed("mxc://x.org/a");
    expect(cache.get("mxc://x.org/a")).toBeNull();
  });
});
