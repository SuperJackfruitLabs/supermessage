// Lazily fetches inline message media (today: `m.image` thumbnails) and
// caches the results in memory, keyed by **event id** — mirrors
// `avatarCache.svelte.ts`'s shape almost exactly (see that file's doc
// comment for the pattern this follows), with one addition `avatarCache`
// doesn't need: a `hasFailed` query distinct from `get`.
//
// Avatars can conflate "no avatar to show" and "the fetch failed" into a
// single `null`, because both cases render the same fallback (initials)
// immediately — there is no "avatar is still loading" visual state to get
// wrong. An inline image is different: `Timeline.svelte` must show a
// *sized placeholder box* (reserved to the image's known aspect ratio, so
// the row doesn't reflow when the real bytes land) while the fetch is still
// in flight, but fall back to the plain informative-row text once it's
// clear nothing is ever going to render — three distinct states, not two.
// `get` returning `null` alone cannot distinguish "still loading" from
// "permanently nothing"; `hasFailed` is what resolves that ambiguity.
//
// Callers must never block on media: `get` always returns synchronously
// (the cached `data:` URI, or `null` before/absent a fetch) and kicks off
// the fetch in the background the first time a given event id is seen.
// Every failure path — the core reporting no renderable media, a rejected
// fetch, or the `<img>` itself failing to decode a `data:` URI that did
// arrive — converges on the same `hasFailed(id) === true` state, so the
// caller's fallback logic never has to distinguish which one occurred (the
// same discipline `avatarCache`'s doc comment describes for its own callers).

import { mediaFetch as defaultMediaFetch } from "$lib/ipc";

export interface MediaCacheDeps {
  mediaFetch: typeof defaultMediaFetch;
}

const defaultDeps: MediaCacheDeps = { mediaFetch: defaultMediaFetch };

export function createMediaCache(deps: MediaCacheDeps = defaultDeps) {
  // event id -> resolved `data:` URI, or `null` while loading/failed.
  // `$state` so every component reading through `get`/`hasFailed`
  // re-renders once an in-flight fetch lands.
  const resolved = $state<Record<string, string | null>>({});
  // event id -> whether resolution has finished with nothing renderable —
  // separate from `resolved` being `null`, which is also the in-flight
  // state (see this file's top-of-module doc comment for why the two must
  // stay distinguishable).
  const failed = $state<Record<string, boolean>>({});
  // Every event id this cache has ever kicked off a fetch for, whether or
  // not it has resolved yet — what stops the same item from being
  // requested twice just because multiple re-renders (or multiple `VList`
  // remounts of the same row) called `get` before the first fetch finished.
  const requested = new Set<string>();

  /**
   * The cached media `data:` URI for `eventId`, fetching in the background
   * the first time this event is seen. `null` both before the fetch
   * resolves and once it has resolved with nothing to show — check
   * {@link hasFailed} to tell those two apart.
   */
  function get(eventId: string): string | null {
    if (!requested.has(eventId)) {
      requested.add(eventId);
      void fetchInto(eventId);
    }
    return resolved[eventId] ?? null;
  }

  /**
   * Whether `eventId` has definitively resolved to "nothing renderable" —
   * the core found no fetchable/sniffable media, the fetch itself rejected,
   * or {@link markFailed} was called because the `<img>` failed to decode a
   * `data:` URI that did arrive. `false` both before any fetch has been
   * kicked off and while one is still in flight; callers that need "is this
   * still loading" must derive it themselves as `!get(id) &&
   * !hasFailed(id)`, since this cache has no separate boolean for it (a
   * fetch is always kicked off the moment `get` is first called, so the
   * loading window is bounded and doesn't need its own tracked state).
   */
  function hasFailed(eventId: string): boolean {
    return failed[eventId] === true;
  }

  /**
   * Marks `eventId` as having no usable image, so `get` keeps returning
   * `null` and `hasFailed` starts returning `true`. For the one failure
   * mode fetching alone can't catch: a `data:` URI the core produced but
   * the `<img>` itself fails to decode — the last line of the "never show a
   * broken image" guarantee, same role as `avatarCache.markFailed`.
   */
  function markFailed(eventId: string): void {
    resolved[eventId] = null;
    failed[eventId] = true;
  }

  async function fetchInto(eventId: string): Promise<void> {
    try {
      const src = await deps.mediaFetch(eventId);
      resolved[eventId] = src;
      // The core itself found nothing renderable — as final an answer as a
      // thrown error, so it's recorded the same way.
      if (src == null) failed[eventId] = true;
    } catch (err) {
      console.error("failed to fetch media for event", eventId, err);
      resolved[eventId] = null;
      failed[eventId] = true;
    }
  }

  return { get, hasFailed, markFailed };
}

export type MediaCache = ReturnType<typeof createMediaCache>;
