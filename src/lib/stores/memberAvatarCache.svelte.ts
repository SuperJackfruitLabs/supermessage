// Lazily fetches room-member avatars and caches the results in memory,
// keyed by **mxc URI** — unlike `avatarCache.svelte.ts` (keyed by room id,
// because a room's avatar can be implicit and needs the core to resolve it
// from the room's member list first), a member's own avatar mxc URI is
// already the right answer: `RoomInfo.members[].avatarUrl` from `roomInfo`
// carries it directly, with no resolution step of its own. So this cache
// takes the mxc URI straight from the caller and fetches it unconditionally
// through the same authenticated-media path `avatarCache` uses
// (`memberAvatar`, which wraps `core::media::avatar_thumbnail` — the same
// function `room_avatar` calls) — never a second fetch path.
//
// Shape otherwise mirrors `avatarCache.svelte.ts` exactly — see that file's
// doc comment for the same lazy/lazy-per-key/never-blocks/never-throws
// contract, which applies here unchanged.

import { memberAvatar as defaultMemberAvatar } from "$lib/ipc";

export interface MemberAvatarCacheDeps {
  memberAvatar: typeof defaultMemberAvatar;
}

const defaultDeps: MemberAvatarCacheDeps = { memberAvatar: defaultMemberAvatar };

export function createMemberAvatarCache(deps: MemberAvatarCacheDeps = defaultDeps) {
  // mxc URI -> resolved `data:` URI, or `null` once resolution has finished
  // with nothing renderable.
  const resolved = $state<Record<string, string | null>>({});
  // Every mxc URI this cache has ever kicked off a fetch for, whether or not
  // it has resolved yet — stops a member row from being requested twice just
  // because multiple re-renders called `get` for it before the first fetch
  // finished.
  const requested = new Set<string>();

  /**
   * The cached avatar for `mxcUri`, fetching in the background the first
   * time this mxc URI is seen. Call only with a non-null
   * `RoomMember.avatarUrl` — a member with no avatar set has nothing for
   * this cache to fetch, and the caller should fall back to initials before
   * ever calling this, the same discipline `RoomList.svelte` already
   * follows for `avatarCache` (there, gating is wrong because of the
   * two-person fallback; here there is no such fallback, so gating on a
   * present `avatarUrl` is simply correct).
   */
  function get(mxcUri: string): string | null {
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
   * decode.
   */
  function markFailed(mxcUri: string): void {
    resolved[mxcUri] = null;
  }

  async function fetchInto(mxcUri: string): Promise<void> {
    try {
      resolved[mxcUri] = await deps.memberAvatar(mxcUri);
    } catch (err) {
      console.error("failed to fetch avatar for member mxc uri", mxcUri, err);
      resolved[mxcUri] = null;
    }
  }

  return { get, markFailed };
}

export type MemberAvatarCache = ReturnType<typeof createMemberAvatarCache>;
