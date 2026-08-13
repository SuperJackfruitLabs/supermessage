<script lang="ts">
  // The room-info panel: name, topic, canonical alias, alt aliases, the raw
  // room id (copyable — people need it for debugging), and the joined
  // member list with display names and avatars.
  //
  // `docs/matrix-events.md` has suppressed `m.room.topic`/
  // `m.room.canonical_alias` (and friends) from the timeline on the grounds
  // that they belong "in room info" since M0's first pass against a real
  // account — this is that surface. Until now there was nowhere for a
  // reader to see a room's topic or alias at all.
  //
  // Fetched on demand, not streamed: `roomInfo` (`$lib/ipc.ts`) is a plain
  // request/response command, unlike `RoomSummary`/`TimelineItem`'s
  // `sm://*/diff` channels — a room's topic/alias/member list changes rarely
  // enough that a one-shot fetch each time the panel opens is the right
  // trade-off, not a third diff-backed store to maintain. `roomId` is
  // verified core-side against whichever room is actually focused
  // (`core::room_info::verify_same_room`, via `Session::room_info`) — the
  // same room-scoped guard the timeline commands already take — so a stale
  // fetch from a switched-away room fails loudly (`roomChanged`) rather than
  // silently showing the wrong room's identity.
  //
  // `+page.svelte` wraps this in `{#key roomsStore.selectedId}`, the same
  // pattern `Timeline.svelte` already uses, so a room switch remounts this
  // component fresh — `info`/`loading`/`loadError` all start clean per room,
  // and a fetch still resolving for a just-unmounted instance simply updates
  // state nobody renders anymore (the same accepted shape `Timeline.svelte`'s
  // own doc comments already describe for `requestOlderMessages`).
  //
  // Member avatars reuse the exact authenticated-media fetch a room's own
  // avatar already uses (`memberAvatarCache.svelte.ts`, wrapping the new
  // `member_avatar` command, which itself is a thin wrapper over
  // `core::media::avatar_thumbnail` — the same function `room_avatar` calls)
  // — not a second fetch path. The room's own avatar (shown in this panel's
  // header) reuses `avatarCache.svelte.ts` unchanged, exactly the way
  // `RoomList.svelte` already does.
  //
  // Topic and member display names are sender/server-controlled free text —
  // `break-words` throughout, the same overflow guard `Timeline.svelte`
  // applies to every other field of this kind (that file's doc comment notes
  // this exact class of bug has shipped twice already).

  import { onMount } from "svelte";
  import { roomInfo, type RoomInfo } from "$lib/ipc";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import { createMemberAvatarCache } from "$lib/stores/memberAvatarCache.svelte";
  import { initial, memberDisplayName, roomDisplayName } from "./roomInfoView";

  let { roomId, onClose }: { roomId: string; onClose: () => void } = $props();

  const avatarCache = createAvatarCache();
  const memberAvatarCache = createMemberAvatarCache();

  let info = $state<RoomInfo | null>(null);
  let loadError = $state<string | null>(null);
  let loading = $state(true);
  let copied = $state(false);

  onMount(() => {
    roomInfo(roomId)
      .then((result) => {
        info = result;
      })
      .catch((err: unknown) => {
        console.error("failed to load room info", roomId, err);
        loadError = "Couldn't load room info.";
      })
      .finally(() => {
        loading = false;
      });
  });

  const sortedMembers = $derived(
    info
      ? [...info.members].sort((a, b) =>
          memberDisplayName(a).localeCompare(memberDisplayName(b)),
        )
      : [],
  );

  /**
   * Copies the room id to the clipboard, via the standard `navigator.clipboard`
   * Web API — no Tauri plugin/capability needed, since this is an ordinary
   * write triggered by a user gesture (the button click), not an IPC call.
   * The room id text itself also carries `.selectable` (see the markup
   * below), so manual select-and-copy always works even if the async
   * clipboard write is unavailable for some reason — this button is a fast
   * path, not the only way to get the id out.
   */
  async function copyRoomId(): Promise<void> {
    if (!info) return;
    try {
      await navigator.clipboard.writeText(info.roomId);
      copied = true;
      setTimeout(() => {
        copied = false;
      }, 1500);
    } catch (err) {
      console.error("failed to copy room id to the clipboard", err);
    }
  }
</script>

<aside
  aria-label="Room info"
  class="flex h-full w-80 shrink-0 flex-col overflow-y-auto border-l border-border bg-surface-sunken"
>
  <div class="flex shrink-0 items-center justify-between border-b border-border px-4 py-3">
    <h2 class="text-sm font-semibold text-content">Room info</h2>
    <button
      type="button"
      onclick={onClose}
      aria-label="Close room info"
      class="rounded-md p-1 text-content-muted transition-colors hover:bg-surface hover:text-content"
    >
      ✕
    </button>
  </div>

  {#if loading}
    <p class="px-4 py-6 text-center text-sm text-content-muted">Loading…</p>
  {:else if loadError}
    <p class="px-4 py-6 text-center text-sm text-content-muted">{loadError}</p>
  {:else if info}
    {@const currentRoomId = info.roomId}
    {@const avatar = avatarCache.get(currentRoomId)}
    <div class="flex flex-col items-center gap-2 border-b border-border px-4 py-5">
      {#if avatar}
        <img
          src={avatar}
          alt=""
          aria-hidden="true"
          class="h-16 w-16 shrink-0 rounded-full object-cover"
          onerror={() => avatarCache.markFailed(currentRoomId)}
        />
      {:else}
        <span
          class="flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-surface-raised text-xl font-medium text-content"
          aria-hidden="true"
        >
          {initial(roomDisplayName(info))}
        </span>
      {/if}
      <p class="selectable max-w-full text-center text-base font-semibold break-words text-content">
        {roomDisplayName(info)}
      </p>
    </div>

    {#if info.topic}
      <div class="border-b border-border px-4 py-3">
        <h3 class="mb-1 text-xs font-medium text-content-muted">Topic</h3>
        <p class="selectable text-sm break-words text-content">{info.topic}</p>
      </div>
    {/if}

    {#if info.canonicalAlias || info.altAliases.length > 0}
      <div class="border-b border-border px-4 py-3">
        <h3 class="mb-1 text-xs font-medium text-content-muted">
          {info.canonicalAlias && info.altAliases.length > 0
            ? "Addresses"
            : info.altAliases.length > 0
              ? "Alternative addresses"
              : "Address"}
        </h3>
        {#if info.canonicalAlias}
          <p class="selectable text-sm break-words text-content">{info.canonicalAlias}</p>
        {/if}
        {#each info.altAliases as alias (alias)}
          <p class="selectable text-sm break-words text-content-muted">{alias}</p>
        {/each}
      </div>
    {/if}

    <div class="border-b border-border px-4 py-3">
      <h3 class="mb-1 text-xs font-medium text-content-muted">Room ID</h3>
      <div class="flex items-center gap-2">
        <p class="selectable min-w-0 flex-1 font-mono text-xs break-words text-content">
          {info.roomId}
        </p>
        <button
          type="button"
          onclick={copyRoomId}
          class="shrink-0 rounded-md border border-border px-2 py-1 text-xs font-medium text-content-muted transition-colors hover:bg-surface hover:text-content"
        >
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
    </div>

    <div class="min-h-0 flex-1 px-4 py-3">
      <h3 class="mb-2 text-xs font-medium text-content-muted">
        {info.activeMemberCount}
        {info.activeMemberCount === 1 ? "member" : "members"}
      </h3>
      <ul class="flex flex-col gap-2">
        {#each sortedMembers as member (member.userId)}
          {@const memberAvatar = member.avatarUrl ? memberAvatarCache.get(member.avatarUrl) : null}
          <li class="flex items-center gap-2">
            {#if memberAvatar}
              <img
                src={memberAvatar}
                alt=""
                aria-hidden="true"
                class="h-8 w-8 shrink-0 rounded-full object-cover"
                onerror={() => member.avatarUrl && memberAvatarCache.markFailed(member.avatarUrl)}
              />
            {:else}
              <span
                class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-raised text-xs font-medium text-content"
                aria-hidden="true"
              >
                {initial(memberDisplayName(member))}
              </span>
            {/if}
            <span class="min-w-0 flex-1">
              <!--
                `break-words`, not `truncate`: a member's display name is
                sender-controlled free text, and this codebase has already
                shipped the "long unbroken run widens its container" bug
                twice (see Timeline.svelte's top-of-script doc comment) —
                `truncate` (nowrap + ellipsis) would also just hide a long
                name outright rather than let the reader see it wrap.
              -->
              <span class="selectable block text-sm text-content break-words">
                {memberDisplayName(member)}
              </span>
              {#if member.displayName}
                <span class="selectable block text-xs text-content-muted break-words">
                  {member.userId}
                </span>
              {/if}
            </span>
          </li>
        {/each}
      </ul>
    </div>
  {/if}
</aside>
