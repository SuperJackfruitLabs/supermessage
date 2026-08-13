<script lang="ts">
  // The sidebar: rooms sorted by recency, selecting one drives the timeline
  // subscription via `roomsStore.select`.
  //
  // Each row surfaces the structure spec §5.1/§6.1 calls for: the room name
  // is parsed into glyph/name/role via `roomIdentity.ts`'s
  // `parseRoomIdentity`, the avatar fallback goes through `roomInitial`
  // (never the raw name's first character — see that module's doc comment
  // for the astral-surrogate bug this replaces), and the second line (role
  // and/or relative last activity) is built from that parse plus
  // `relativeTime`.
  //
  // `lastMessage` is always `null` in M0 — the core defers preview decoding
  // to a later milestone (see `ipc.ts`'s `RoomSummary` doc comment) — so no
  // row here ever renders a message preview. That's a separate concern from
  // the role/activity line this component builds: once previews land,
  // they'd be a further line beyond the two here, not a replacement for
  // either, and they'd need the same "omit rather than print a placeholder"
  // treatment this component already gives a room with no role and no
  // activity (spec §6.1).
  //
  // Avatars: fetched via `avatarCache`, keyed by room id, for **every**
  // room — not gated on `room.avatarUrl` being set. That field only ever
  // reflects the room's own `m.room.avatar`, and is `null` for most of
  // these rooms: their "avatar" (per Element) is really the other member's
  // profile picture, which the core can only resolve by reading the room's
  // member list — async, so it happens inside the `room_avatar` command
  // rather than the synchronous room-list projection (see
  // `core::rooms::resolve_room_avatar_mxc`'s doc comment and `ipc.ts`'s
  // `RoomSummary`/`roomAvatar` doc comments). Gating the fetch on
  // `avatarUrl` here would silently skip exactly those rooms. The cache
  // still keeps the list from blocking on avatars: every row renders
  // immediately with its initials, and swaps in the real image once (and
  // if) the fetch resolves.

  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import { parseRoomIdentity, relativeTime, roomInitial } from "./roomIdentity";

  const avatarCache = createAvatarCache();

  const sortedRooms = $derived(
    [...roomsStore.rooms].sort((a, b) => (b.lastActivityMs ?? 0) - (a.lastActivityMs ?? 0)),
  );

  // Recency threshold for spec §6.1's muted-vs-faint split on the "· 4m"
  // time: rooms active within the last 5 minutes render their time in
  // `--color-content-muted`, older ones in `--color-content-faint`. Recency
  // is the only honest per-row liveness signal available — per-room typing
  // isn't streamed, `typingStore` scopes to the focused room only.
  const RECENT_MS = 5 * 60_000;

  // `relativeTime` needs an instant to measure against. This is
  // deliberately *not* a ticking clock: `now` is derived from
  // `sortedRooms`, so it's recomputed exactly when the roster itself
  // re-renders — new activity, a room added or removed, an unread count
  // changing — which is the only time a row's age label could actually be
  // stale. A `setInterval` re-rendering the whole roster every
  // second/minute purely to age a label would be a battery cost for zero
  // new information. Do not add one; if a row's time looks stale, the fix
  // is confirming the roster re-renders on new activity, not a timer.
  const now = $derived.by(() => {
    void sortedRooms;
    return Date.now();
  });

  /**
   * The row's accessible name: parsed name, role, unread count, in that
   * order — set explicitly as the button's own `aria-label` rather than
   * left to the default descendant-concatenation algorithm. Two reasons:
   * the visual "·" between role and time would otherwise be read literally
   * (e.g. "middle dot") by some screen readers, and the unread badge sits
   * to the right of the name in the DOM/markup for layout reasons, which
   * would otherwise interleave it ahead of the role. The relative-time
   * label ("4m") is left out on purpose: it's supplementary and changes on
   * every re-render, not identifying information worth repeating on every
   * row for a screen reader user.
   */
  function rowAriaLabel(name: string, role: string | null, unread: number): string {
    const parts = [name];
    if (role !== null) parts.push(role);
    if (unread > 0) parts.push(`${unread} unread`);
    return parts.join(", ");
  }
</script>

<nav aria-label="Rooms" class="flex h-full flex-col overflow-y-auto">
  {#if sortedRooms.length === 0}
    <p class="px-4 py-6 text-center text-sm text-content-muted">No rooms yet.</p>
  {:else}
    {#each sortedRooms as room (room.id)}
      {@const selected = room.id === roomsStore.selectedId}
      {@const avatar = avatarCache.get(room.id)}
      {@const identity = parseRoomIdentity(room.name)}
      {@const time = relativeTime(room.lastActivityMs, now)}
      {@const recent = room.lastActivityMs !== null && now - room.lastActivityMs < RECENT_MS}
      {@const showRow2 = identity.role !== null || time !== null}
      <button
        type="button"
        onclick={() => roomsStore.select(room.id)}
        aria-current={selected ? "true" : undefined}
        aria-label={rowAriaLabel(identity.name, identity.role, room.unread)}
        class="flex gap-3 border-l-2 pr-4 pl-[10px] text-left transition-colors {selected
          ? 'border-l-accent bg-surface'
          : 'border-l-transparent hover:bg-surface/60'}"
      >
        {#if avatar}
          <img
            src={avatar}
            alt=""
            aria-hidden="true"
            class="h-8 w-8 shrink-0 self-center rounded-full object-cover"
            onerror={() => avatarCache.markFailed(room.id)}
          />
        {:else}
          <span
            class="flex h-8 w-8 shrink-0 self-center items-center justify-center rounded-full bg-surface-raised text-ui font-medium text-content"
            aria-hidden="true"
          >
            {roomInitial(identity)}
          </span>
        {/if}
        <!--
          The row separator lives on this column, not the button: the
          button's flex row defaults to `align-items: stretch`, and this
          column (name + role/time, with its own `py-3`) is always the
          tallest sibling, so its own bottom edge already coincides with the
          row's. Anchoring the hairline here — rather than on the button —
          is what keeps it inset to clear the avatar column per spec §6.1
          ("Row separator: hairline, inset to clear the avatar column")
          instead of running edge-to-edge under the avatar too.
        -->
        <span class="min-w-0 flex-1 border-b border-border py-3">
          <span class="flex items-center justify-between gap-2">
            <span class="truncate text-ui font-medium text-content">{identity.name}</span>
            {#if room.unread > 0}
              <!--
                No `aria-label` of its own. The button above sets an explicit
                one covering name, role and unread count, and an explicit
                `aria-label` replaces its whole subtree for name computation
                — so a label here would be dead for the row's accessible
                name while still being reachable by an assistive
                technology's virtual cursor, which is the worst of both:
                inert where it looks useful, and a second, differently
                worded reading of the same number where it isn't.
              -->
              <span
                class="shrink-0 rounded-full bg-accent px-1.5 py-0.5 font-mono text-meta text-accent-content"
              >
                {room.unread}
              </span>
            {/if}
          </span>
          {#if showRow2}
            <span class="mt-0.5 flex min-w-0 items-baseline gap-1 font-mono text-meta text-content-muted">
              {#if identity.role !== null}
                <span class="truncate text-label uppercase">{identity.role}</span>
              {/if}
              {#if identity.role !== null && time !== null}
                <span aria-hidden="true">·</span>
              {/if}
              {#if time !== null}
                <span class="shrink-0 {recent ? '' : 'text-content-faint'}">{time}</span>
              {/if}
            </span>
          {/if}
        </span>
      </button>
    {/each}
  {/if}
</nav>
