// Lazily fetches avatars and caches the results in memory, keyed by **room
// id**, not by the mxc URI on `RoomSummary.avatarUrl`.
//
// That's a deliberate change from an earlier version of this cache: a room
// whose "avatar" is really its other member's profile picture (a DM with no
// `m.room.avatar` and no heroes — see `core::rooms::resolve_room_avatar_mxc`'s
// doc comment for why Synapse omits heroes here) has `avatarUrl: null`, so
// keying on that URI meant those rooms' avatars were never even attempted.
// `room_avatar(roomId)` resolves the room's member list itself when needed,
// so this cache calls it unconditionally, for every room, keyed on the one
// identifier every room always has.
//
// Trade-off, noted rather than hidden: keying on room id instead of the mxc
// URI loses "an avatar that changes mid-session refetches automatically" —
// a room's id is stable even when its avatar isn't. Avatars change rarely,
// and a restart re-resolves from scratch, so this was judged an acceptable
// cost for actually getting these rooms' avatars to render at all.
//
// Callers must never block on avatars: `get` always returns synchronously
// (the cached value, or `null` before/absent a fetch) and kicks off the
// fetch in the background the first time a given room id is seen. `null` is
// also the permanent answer for "no avatar to show," a failed fetch, or
// bytes the core couldn't identify as a renderable image — the caller's job
// is to fall back to initials in every one of those cases, not to
// distinguish them.

import { roomAvatar as defaultRoomAvatar } from "$lib/ipc";

export interface AvatarCacheDeps {
  roomAvatar: typeof defaultRoomAvatar;
}

const defaultDeps: AvatarCacheDeps = { roomAvatar: defaultRoomAvatar };

export function createAvatarCache(deps: AvatarCacheDeps = defaultDeps) {
  // room id -> resolved `data:` URI, or `null` once resolution has finished
  // with nothing renderable. `$state` so every component reading through
  // `get` re-renders once an in-flight fetch lands.
  const resolved = $state<Record<string, string | null>>({});
  // Every room id this cache has ever kicked off a fetch for, whether or
  // not it has resolved yet — what stops a room from being requested twice
  // just because multiple re-renders of the same row called `get` for it
  // before the first fetch finished.
  const requested = new Set<string>();

  /**
   * The cached avatar for `roomId`, fetching in the background the first
   * time this room is seen. Call unconditionally — a room reporting no
   * `avatarUrl` on its `RoomSummary` can still resolve an avatar here (the
   * two-person DM fallback), which is the entire reason this takes a room
   * id and not the mxc URI.
   */
  function get(roomId: string): string | null {
    if (!requested.has(roomId)) {
      requested.add(roomId);
      void fetchInto(roomId);
    }
    return resolved[roomId] ?? null;
  }

  /**
   * Marks `roomId` as having no usable image, so `get` falls back to
   * initials from now on. For the one failure mode fetching alone can't
   * catch: a `data:` URI the core produced but the `<img>` itself fails to
   * decode — the last line of the "never show a broken image" guarantee.
   */
  function markFailed(roomId: string): void {
    resolved[roomId] = null;
  }

  async function fetchInto(roomId: string): Promise<void> {
    try {
      resolved[roomId] = await deps.roomAvatar(roomId);
    } catch (err) {
      console.error("failed to fetch avatar for room", roomId, err);
      resolved[roomId] = null;
    }
  }

  return { get, markFailed };
}

export type AvatarCache = ReturnType<typeof createAvatarCache>;
