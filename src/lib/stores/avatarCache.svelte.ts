// Lazily fetches avatars and caches the results in memory, keyed by the
// `mxc://` URI itself — a room's `avatarUrl` today, and (from M2) a user's
// avatar too, since `avatar_thumbnail` takes the mxc URI directly rather
// than anything room-specific (see `ipc.ts`'s doc comment). Keying on the
// URI is what makes an avatar that changes (a new `mxc://` URI on the room
// or user) refetch, while one that hasn't changed keeps serving the cached
// `data:` URI instead of re-invoking `avatar_thumbnail` every time a row
// re-renders.
//
// Callers must never block on avatars: `get` always returns synchronously
// (the cached value, or `null` before/absent a fetch) and kicks off the
// fetch in the background the first time a given `mxcUri` is seen. `null` is
// also the permanent answer for a failed fetch, or bytes the core couldn't
// identify as a renderable image — the caller's job is to fall back to
// initials in every one of those cases, not to distinguish them.

import { avatarThumbnail as defaultAvatarThumbnail } from "$lib/ipc";

export interface AvatarCacheDeps {
  avatarThumbnail: typeof defaultAvatarThumbnail;
}

const defaultDeps: AvatarCacheDeps = { avatarThumbnail: defaultAvatarThumbnail };

export function createAvatarCache(deps: AvatarCacheDeps = defaultDeps) {
  // mxc URI -> resolved `data:` URI, or `null` once resolution has finished
  // with nothing renderable. `$state` so every component reading through
  // `get` re-renders once an in-flight fetch lands.
  const resolved = $state<Record<string, string | null>>({});
  // Every `mxcUri` this cache has ever kicked off a fetch for, whether or
  // not it has resolved yet — what stops a key from being requested twice
  // just because multiple rows (or repeated re-renders of the same row)
  // called `get` for it before the first fetch finished.
  const requested = new Set<string>();

  /**
   * The cached avatar for `mxcUri`, fetching in the background the first
   * time this exact URI is seen. `null` covers "no avatar" (pass `null`
   * through, e.g. a room with no `avatarUrl`), "still fetching", and
   * "resolved to nothing renderable" alike — callers render their fallback
   * for all three.
   */
  function get(mxcUri: string | null): string | null {
    if (mxcUri === null) return null;
    if (!requested.has(mxcUri)) {
      requested.add(mxcUri);
      void fetchInto(mxcUri);
    }
    return resolved[mxcUri] ?? null;
  }

  /**
   * Marks `mxcUri` as having no usable image, so `get` falls back to
   * initials from now on. For the one failure mode fetching alone can't
   * catch: a `data:` URI the core produced but the `<img>` itself fails to
   * decode — the last line of the "never show a broken image" guarantee.
   */
  function markFailed(mxcUri: string): void {
    resolved[mxcUri] = null;
  }

  async function fetchInto(mxcUri: string): Promise<void> {
    try {
      resolved[mxcUri] = await deps.avatarThumbnail(mxcUri);
    } catch (err) {
      console.error("failed to fetch avatar thumbnail", mxcUri, err);
      resolved[mxcUri] = null;
    }
  }

  return { get, markFailed };
}

export type AvatarCache = ReturnType<typeof createAvatarCache>;
