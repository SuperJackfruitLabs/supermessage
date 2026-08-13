// Lazily fetches room avatars and caches the results in memory, keyed by the
// room's `avatarUrl` — the `mxc://` URI, not the room id — so an avatar that
// changes (a new `avatarUrl` on the room) refetches, while one that hasn't
// changed keeps serving the cached `data:` URI instead of re-invoking
// `room_avatar` every time a row re-renders.
//
// `RoomList.svelte` must never block on avatars: `get` always returns
// synchronously (the cached value, or `null` before/absent a fetch) and
// kicks off the fetch in the background the first time a given `avatarUrl`
// is seen. `null` is also the permanent answer for "no avatar", a failed
// fetch, or bytes the core couldn't identify as a renderable image — the
// caller's job is to fall back to initials in every one of those cases, not
// to distinguish them.

import { roomAvatar as defaultRoomAvatar } from "$lib/ipc";

export interface AvatarCacheDeps {
  roomAvatar: typeof defaultRoomAvatar;
}

const defaultDeps: AvatarCacheDeps = { roomAvatar: defaultRoomAvatar };

export function createAvatarCache(deps: AvatarCacheDeps = defaultDeps) {
  // avatarUrl -> resolved `data:` URI, or `null` once resolution has
  // finished with nothing renderable. `$state` so every component reading
  // through `get` re-renders once an in-flight fetch lands.
  const resolved = $state<Record<string, string | null>>({});
  // Every `avatarUrl` this cache has ever kicked off a fetch for, whether or
  // not it has resolved yet — what stops a key from being requested twice
  // just because multiple rows (or repeated re-renders of the same row)
  // called `get` for it before the first fetch finished.
  const requested = new Set<string>();

  /**
   * The cached avatar for `roomId`/`avatarUrl`, fetching in the background
   * the first time `avatarUrl` is seen. `avatarUrl` is what's cached on —
   * `roomId` only ever gets used as the argument to the fetch itself, since
   * that's what the `room_avatar` command takes.
   */
  function get(roomId: string, avatarUrl: string | null): string | null {
    if (avatarUrl === null) return null;
    if (!requested.has(avatarUrl)) {
      requested.add(avatarUrl);
      void fetchInto(roomId, avatarUrl);
    }
    return resolved[avatarUrl] ?? null;
  }

  /**
   * Marks `avatarUrl` as having no usable image, so `get` falls back to
   * initials from now on. For the one failure mode fetching alone can't
   * catch: a `data:` URI the core produced but the `<img>` itself fails to
   * decode — the last line of the "never show a broken image" guarantee.
   */
  function markFailed(avatarUrl: string): void {
    resolved[avatarUrl] = null;
  }

  async function fetchInto(roomId: string, avatarUrl: string): Promise<void> {
    try {
      resolved[avatarUrl] = await deps.roomAvatar(roomId);
    } catch (err) {
      console.error("failed to fetch avatar for room", roomId, err);
      resolved[avatarUrl] = null;
    }
  }

  return { get, markFailed };
}

export type AvatarCache = ReturnType<typeof createAvatarCache>;
