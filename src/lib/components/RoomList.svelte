<script lang="ts">
  // The sidebar: rooms sorted by recency, selecting one drives the timeline
  // subscription via `roomsStore.select`.
  //
  // `lastMessage` is always `null` in M0 — the core defers preview decoding
  // to a later milestone (see `ipc.ts`'s `RoomSummary` doc comment). Rather
  // than print a placeholder like "No preview yet" on every single row —
  // which would just read as N copies of the same broken-looking string —
  // the preview line is omitted entirely when there's nothing to show. Every
  // row therefore looks intentionally uniform today; once previews land,
  // rooms that have one will simply grow a second line.
  //
  // Avatars: `avatarUrl` is an `mxc://` URI a browser can't load directly
  // (and this homeserver's media endpoints are authenticated, so there's no
  // bare `http(s)://` URL either — see `ipc.ts`'s `roomAvatar` doc comment).
  // `avatarCache` resolves it to a `data:` URI via the `room_avatar` command,
  // fetched lazily per room and cached by `avatarUrl` so the list never
  // blocks on avatars: every row renders immediately with its initials, and
  // swaps in the real image once (and if) the fetch resolves.

  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import type { RoomSummary } from "$lib/ipc";

  const avatarCache = createAvatarCache();

  const sortedRooms = $derived(
    [...roomsStore.rooms].sort((a, b) => (b.lastActivityMs ?? 0) - (a.lastActivityMs ?? 0)),
  );

  function initials(room: RoomSummary): string {
    const trimmed = room.name.trim();
    // Iterate code points, not code units: `trimmed[0]` takes half of an
    // astral-plane character, so an emoji-named room ("🧠 Buddhimaan")
    // renders a lone surrogate — a broken glyph. Every agent room here is
    // emoji-named, so this was visible on every row.
    const first = [...trimmed][0];
    return first === undefined ? "?" : first.toUpperCase();
  }
</script>

<nav aria-label="Rooms" class="flex h-full flex-col overflow-y-auto">
  {#if sortedRooms.length === 0}
    <p class="px-4 py-6 text-center text-sm text-content-muted">No rooms yet.</p>
  {:else}
    {#each sortedRooms as room (room.id)}
      {@const selected = room.id === roomsStore.selectedId}
      {@const avatar = avatarCache.get(room.id, room.avatarUrl)}
      <button
        type="button"
        onclick={() => roomsStore.select(room.id)}
        aria-current={selected ? "true" : undefined}
        class="flex items-center gap-3 border-b border-border px-4 py-3 text-left transition-colors {selected
          ? 'bg-surface'
          : 'hover:bg-surface/60'}"
      >
        {#if avatar}
          <img
            src={avatar}
            alt=""
            aria-hidden="true"
            class="h-10 w-10 shrink-0 rounded-full object-cover"
            onerror={() => room.avatarUrl && avatarCache.markFailed(room.avatarUrl)}
          />
        {:else}
          <span
            class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-surface-raised text-sm font-medium text-content"
            aria-hidden="true"
          >
            {initials(room)}
          </span>
        {/if}
        <span class="min-w-0 flex-1">
          <span class="flex items-center justify-between gap-2">
            <span class="truncate text-sm font-medium text-content">{room.name}</span>
            {#if room.unread > 0}
              <span
                class="shrink-0 rounded-full bg-accent px-1.5 py-0.5 text-xs font-semibold text-accent-content"
              >
                {room.unread}
              </span>
            {/if}
          </span>
        </span>
      </button>
    {/each}
  {/if}
</nav>
