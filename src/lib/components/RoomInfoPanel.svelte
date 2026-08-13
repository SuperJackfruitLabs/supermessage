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
  import { parseRoomIdentity, roomInitial } from "./roomIdentity";
  import { initial, memberDisplayName, roomDisplayName, splitSigil } from "./roomInfoView";

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
    {@const identity = parseRoomIdentity(roomDisplayName(info))}
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
          {roomInitial(identity)}
        </span>
      {/if}
      <p class="selectable max-w-full text-center text-ui-lg break-words text-content">
        {identity.name}
      </p>
      {#if identity.role !== null}
        <span
          class="shrink-0 truncate rounded-full border border-border px-2 py-0.5 font-mono text-label text-content-muted uppercase"
        >
          {identity.role}
        </span>
      {/if}
    </div>

    {#if info.topic}
      <div class="border-b border-border px-4 py-3">
        <h3 class="mb-1 font-mono text-label text-content-muted uppercase">Topic</h3>
        <p class="selectable font-serif text-body break-words text-content">{info.topic}</p>
      </div>
    {/if}

    {#if info.canonicalAlias || info.altAliases.length > 0}
      <!--
        No "Address"/"Alternative addresses" heading (spec §5.2): the
        leading `#` sigil, rendered in --color-content-faint, is the label.
        Canonical-vs-alternative is a distinction sighted readers no longer
        get a heading for either — it now rides on two cues instead: the
        canonical alias always renders first, and its rest-of-id text is a
        touch heavier (font-medium vs the alt aliases' regular weight).
        Neither cue reaches a screen reader (DOM order is preserved, but
        weight isn't announced, and "first" isn't itself an announced
        relationship), so each line also carries an sr-only prefix
        ("Canonical address"/"Alternative address") — content a sighted
        reader doesn't see, restoring parity rather than leaving an AT user
        to infer canonical-ness from the mere absence of a marker on later
        rows.
      -->
      <div class="border-b border-border px-4 py-3">
        {#if info.canonicalAlias}
          {@const parsed = splitSigil(info.canonicalAlias)}
          <p class="selectable font-mono text-meta font-medium break-words">
            <span class="sr-only">Canonical address: </span><span class="text-content-faint"
              >{parsed.sigil}</span
            ><span class="text-content-muted">{parsed.rest}</span>
          </p>
        {/if}
        {#each info.altAliases as alias (alias)}
          {@const parsed = splitSigil(alias)}
          <p class="selectable font-mono text-meta break-words">
            <span class="sr-only">Alternative address: </span><span class="text-content-faint"
              >{parsed.sigil}</span
            ><span class="text-content-muted">{parsed.rest}</span>
          </p>
        {/each}
      </div>
    {/if}

    {@const roomIdParsed = splitSigil(info.roomId)}
    <div class="border-b border-border px-4 py-3">
      <div class="flex items-center gap-2">
        <p class="selectable min-w-0 flex-1 font-mono text-meta break-words">
          <span class="text-content-faint">{roomIdParsed.sigil}</span><span
            class="text-content-muted">{roomIdParsed.rest}</span
          >
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
      <h3 class="mb-2 font-mono text-label text-content-muted uppercase">
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
              <span class="selectable block font-sans text-ui text-content break-words">
                {memberDisplayName(member)}
              </span>
              {#if member.displayName}
                {@const parsedMember = splitSigil(member.userId)}
                <span class="selectable block font-mono text-meta break-words">
                  <span class="text-content-faint">{parsedMember.sigil}</span><span
                    class="text-content-muted">{parsedMember.rest}</span
                  >
                </span>
              {/if}
            </span>
          </li>
        {/each}
      </ul>
    </div>
  {/if}
</aside>
