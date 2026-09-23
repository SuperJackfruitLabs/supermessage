<script lang="ts">
  import { animateList } from "./animateList";
  // The sidebar: rooms sorted by recency, selecting one drives the timeline
  // subscription via `roomsStore.select`.
  //
  // Each row surfaces the structure spec §5.1/§6.1 calls for: the room name
  // is parsed into glyph/name/role via `roomIdentity.ts`'s
  // `core::room_identity::parse_room_identity`, the avatar fallback goes through `core::room_identity`'s `initial`
  // (never the raw name's first character — see that module's doc comment
  // for the astral-surrogate bug this replaces), and the second line (role
  // and/or relative last activity) is built from that parse plus
  // `relativeTime`.
  //
  // The third line is the message preview (spec §6.1.1), composed from
  // `RoomSummary`'s four `last*` fields by `core::room_preview` — including the
  // `You: ` prefix, which is the webview's to add and only for our own
  // non-emote messages. It arrived after the two lines above it and is a
  // further line beyond them, not a replacement for either.
  //
  // **All three lines are independently omitted.** Name is always there;
  // the role/time line appears when there is a role or a time; the preview
  // appears when there is a preview. Any of the eight combinations is a
  // real row — a brand-new room has a name and nothing else, a
  // never-active room with a role has no time, and a room whose latest
  // event is a membership change has a role and a time but no preview.
  // There is no placeholder string for any of them (spec §6.1, §6.1.1), so
  // each line asks its own question rather than sharing a "show the rest of
  // the row" flag.
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
  import ArrangementMenu from "./roster/ArrangementMenu.svelte";
  import RoomRow from "./roster/RoomRow.svelte";
  import RosterSectionHeading from "./roster/RosterSectionHeading.svelte";
  import {
    rosterSections,
    type AgentState,
    type RosterSection,
    type RosterView,
  } from "$lib/ipc";

  /**
   * `onSelect` fires after a row is chosen, whether or not that changed the
   * selection. It exists for the collapsed layout in `+page.svelte`, which
   * has to move to the room pane on *every* choice — including a re-choice
   * of the room that is already selected, which is exactly how an operator
   * returns to a room after using the back affordance.
   */
  let { onSelect }: { onSelect?: () => void } = $props();

  const avatarCache = createAvatarCache();

  /**
   * Handles a row click: select the room if it isn't already the selected
   * one, then notify.
   *
   * The guard is not a micro-optimization. `roomsStore.select` calls
   * `timelineStore.subscribeTo`, which re-arms the diff tracker to expect a
   * fresh sequence starting at 1 and re-issues `timeline_subscribe` — the
   * teardown-and-rebuild `rooms.svelte.ts`'s module doc comment spends most
   * of its length on. In the collapsed layout, returning to a room from the
   * roster is an ordinary navigation that happens constantly, so paying a
   * resubscribe for it (and the resync window it opens) would be a real
   * correctness and performance regression rather than a wasted call.
   * Choosing the already-focused room now does nothing but re-show it.
   */
  function chooseRoom(id: string): void {
    if (id !== roomsStore.selectedId) roomsStore.select(id);
    onSelect?.();
  }

  /**
   * The arrangement, remembered between sessions.
   *
   * Three of them, because a fleet is read for different reasons — see
   * `core::roster`. iOS shipped these first and this app did not have them,
   * so the two clients disagreed about what a roster was; the rules now live
   * in the core and both read the same answer.
   */
  const VIEW_KEY = "supermessage.roster.view";
  let view = $state<RosterView>(
    (localStorage.getItem(VIEW_KEY) as RosterView | null) ?? "recent",
  );
  $effect(() => {
    localStorage.setItem(VIEW_KEY, view);
  });

  /**
   * The arranged roster.
   *
   * Asked for once per change rather than per row: the section list carries
   * each row's state with it, so drawing a dot costs nothing beyond the one
   * call this effect already makes.
   */
  let sections = $state<RosterSection[]>([]);
  $effect(() => {
    const rows = roomsStore.rooms;
    const chosen = view;
    void rosterSections(rows, chosen, true, Date.now()).then((next) => {
      sections = next;
    });
  });

  const sortedRooms = $derived(sections.flatMap((section) => section.rows));


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

</script>

<!--
  The roster reorders itself constantly — it is sorted by last activity, so any
  message in any room moves a row, and a row that teleports past its neighbours
  reads as a different room having appeared. `animateList` moves it instead.
  Safe here, and specifically not in the timeline: see that action's doc comment
  for why a virtualized list must never have it.
-->
<nav aria-label="Rooms" class="flex h-full flex-col overflow-y-auto">
  <ArrangementMenu {view} onChange={(next) => (view = next)} />

  <!--
    The list reorders itself constantly — it is sorted by last activity, so
    any message in any room moves a row, and a row that teleports past its
    neighbours reads as a different room having appeared. `animateList` moves
    it instead. On this element rather than the `nav`, so the sticky control
    above is not something the action tries to animate.
  -->
  <div use:animateList class="flex flex-col">
  {#if sortedRooms.length === 0}
    <p class="px-4 py-6 text-center text-ui text-content-muted">No rooms yet.</p>
  {:else}
    {#each sections as section (section.id)}
      {#if section.title}
        <RosterSectionHeading
          title={section.title}
          detail={section.detail}
          attention={section.attention}
        />
      {/if}
      {#each section.rows as entry (entry.row.room.id)}
        <RoomRow
          row={entry.row}
          state={entry.state}
          describesAgent={entry.describesAgent}
          selected={entry.row.room.id === roomsStore.selectedId}
          avatarUrl={avatarCache.get(entry.row.room.id)}
          {now}
          onSelect={chooseRoom}
          onAvatarFailed={(roomId) => avatarCache.markFailed(roomId)}
        />
      {/each}
    {/each}
  {/if}
  </div>
</nav>
