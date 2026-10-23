<script lang="ts">
  import { onMount } from "svelte";
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
  import RowMenu from "./roster/RowMenu.svelte";
  import { rowMenuItems, type RowMenuItem } from "./roster/rowMenu";
  import { SETTING_FAILURE, toggleMute, togglePin } from "./roomSettings";
  import { roomSettingsWrites } from "$lib/stores/roomSettingsWrites.svelte";
  import InlineFailure from "./timeline/InlineFailure.svelte";
  import RosterSectionHeading from "./roster/RosterSectionHeading.svelte";
  import FilterChips from "./roster/FilterChips.svelte";
  import ContentUnavailable from "./layout/ContentUnavailable.svelte";
  import { createRosterFilter, type FilteredRoster } from "./roster/rosterFilter";
  import {
    roomAvatar,
    roomInfo,
    markRoomReadFromList,
    rosterHiddenInvitations,
    rosterSections,
    spaceDisplayName,
    type RoomRow as RoomRowDto,
    type RosterFilter,
    type RosterSection,
    type RosterView,
  } from "$lib/ipc";

  /**
   * Where the roster comes from, so a story can hand in rooms and a core without a session. The
   * app passes nothing and gets the rooms store and the real commands.
   */
  export interface Source {
    rooms: RoomRowDto[];
    selectedId: string | null;
    rosterSections: typeof rosterSections;
    rosterHiddenInvitations: typeof rosterHiddenInvitations;
    spaceDisplayName: typeof spaceDisplayName;
    filter: ReturnType<typeof createRosterFilter>;
    roomAvatar: typeof roomAvatar;
  }

  /**
   * `onSelect` fires after a row is chosen, whether or not that changed the
   * selection. It exists for the collapsed layout in `+page.svelte`, which
   * has to move to the room pane on *every* choice — including a re-choice
   * of the room that is already selected, which is exactly how an operator
   * returns to a room after using the back affordance.
   */
  let { onSelect, source }: { onSelect?: () => void; source?: Partial<Source> } = $props();

  const rooms = $derived(source?.rooms ?? roomsStore.rooms);
  const selectedId = $derived(source?.selectedId !== undefined ? source.selectedId : roomsStore.selectedId);
  // Read when called, never captured: a story may hand in a different source later.
  const core = {
    rosterSections: (...a: Parameters<typeof rosterSections>) => (source?.rosterSections ?? rosterSections)(...a),
    rosterHiddenInvitations: (...a: Parameters<typeof rosterHiddenInvitations>) =>
      (source?.rosterHiddenInvitations ?? rosterHiddenInvitations)(...a),
    spaceDisplayName: (name: string) => (source?.spaceDisplayName ?? spaceDisplayName)(name),
  };
  // One controller for the component's life.
  // svelte-ignore state_referenced_locally
  const filter = source?.filter ?? createRosterFilter();

  const avatarCache = createAvatarCache({ roomAvatar: (id) => (source?.roomAvatar ?? roomAvatar)(id) });

  /** The open row menu, if any: which room, where, and what it offers. */
  let menu = $state<{ roomId: string; at: { x: number; y: number }; items: RowMenuItem[] } | null>(null);
  /** Said above the list when a mute or pin was refused; cleared by the next menu. */
  let settingFailure = $state<string | null>(null);
  /** Only the newest request may open the menu. */
  let menuRequest = 0;

  /**
   * Opens a row's menu. A joined room is asked what it is set to first (`room_info`), so the
   * menu says "Unmute" or "Unpin" truthfully; when that fails it says "Mute" and "Pin", and the
   * toggle then does nothing, as on iOS. A room with nothing to offer opens no menu.
   */
  async function openMenu(roomId: string, at: { x: number; y: number }): Promise<void> {
    const entry = arranged.flatMap((s) => s.rows).find((e) => e.row.room.id === roomId);
    if (!entry) return;
    const mine = ++menuRequest;
    settingFailure = null;
    const info =
      entry.row.affordance === "compose"
        ? await roomInfo(roomId)
            .then((i) => ({ notifications: i.notifications, pinned: i.pinned }))
            .catch(() => null)
        : null;
    if (mine !== menuRequest) return;
    const items = rowMenuItems(entry.row.affordance, info);
    menu = items.length === 0 ? null : { roomId, at, items };
  }

  function closeMenu(): void {
    menuRequest += 1;
    menu = null;
  }

  async function chooseMenuItem(id: RowMenuItem["id"]): Promise<void> {
    const roomId = menu?.roomId;
    closeMenu();
    if (!roomId) return;
    if (id === "markRead") {
      // A public read receipt on the room's latest event, for a row that may not be open.
      await markRoomReadFromList(roomId).catch((err: unknown) => {
        console.error("mark read failed", err);
        settingFailure = "Couldn't mark that room read. Try again.";
      });
    } else if (id === "mute" || id === "pin") {
      const outcome = await roomSettingsWrites.run<unknown>(roomId, () =>
        id === "mute" ? toggleMute(roomId) : togglePin(roomId),
      );
      if (outcome.ran && outcome.value === null) settingFailure = SETTING_FAILURE;
    } else {
      await roomsStore.declineInvitation(roomId).catch((err: unknown) => {
        console.error("decline failed", err);
        settingFailure = "Couldn't decline that invitation. Try again.";
      });
    }
  }

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
    if (id !== selectedId) roomsStore.select(id);
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

  /** The desktop always lists invitations in the roster, so none are withheld by the arrangement. */
  const SHOWS_INVITATIONS = true;

  /** The chip in force; the stored one is read through the core on mount. */
  let chosen = $state<RosterFilter>("all");
  let chosenLoaded = $state(false);
  onMount(() => {
    void filter
      .load()
      .catch((err: unknown) => console.error("roster filter load failed", err))
      .finally(() => {
        chosen = filter.selected;
        chosenLoaded = true;
      });
  });

  function chooseChip(next: RosterFilter): void {
    chosen = next;
    void filter.select(next).catch((err: unknown) => console.error("roster filter store failed", err));
  }

  /**
   * The arranged roster, and what the chip in force leaves of it.
   *
   * Asked for once per change rather than per row: the section list carries
   * each row's state with it, so drawing a dot costs nothing beyond the one
   * call this effect already makes. The arrangement is the core's, the narrowing
   * is the core's, and so are the chip counts (over the whole arrangement) and
   * the readable section titles.
   */
  let arranged = $state<RosterSection[]>([]);
  let shown = $state<FilteredRoster>({ sections: [], chips: [], empty: null });
  let titles = $state<Map<string, string>>(new Map());
  /** The first roster read has landed. Until then "no rooms" means "not read yet", so nothing says it. */
  let rosterLoaded = $state(false);
  let roster = 0;
  $effect(() => {
    const rows = rooms;
    const chosenView = view;
    const inForce = chosen;
    if (!chosenLoaded) return;
    void inForce;
    const mine = ++roster;
    void (async () => {
      const next = await core.rosterSections(rows, chosenView, SHOWS_INVITATIONS, Date.now());
      // Nothing is withheld when invitations are always listed, so there is nothing to ask the core.
      const hidden = SHOWS_INVITATIONS ? 0 : await core.rosterHiddenInvitations(rows, SHOWS_INVITATIONS);
      const filtered = await filter.apply(next, hidden);
      if (filtered === null || mine !== roster) return;
      const readable = await filter.sectionTitles(filtered.sections, core.spaceDisplayName);
      if (mine !== roster) return;
      arranged = next;
      shown = filtered;
      titles = readable;
      rosterLoaded = true;
    })().catch((err: unknown) => console.error("roster failed", err));
  });

  const sortedRooms = $derived(shown.sections.flatMap((section) => section.rows));
  const hasRooms = $derived(arranged.some((section) => section.rows.length > 0));


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
  {#if hasRooms}
    <FilterChips chips={shown.chips} selected={chosen} onSelect={chooseChip} />
  {/if}
  {#if settingFailure !== null}
    <div class="px-4"><InlineFailure message={settingFailure} /></div>
  {/if}

  <!--
    The list reorders itself constantly — it is sorted by last activity, so
    any message in any room moves a row, and a row that teleports past its
    neighbours reads as a different room having appeared. `animateList` moves
    it instead. On this element rather than the `nav`, so the sticky control
    above is not something the action tries to animate.
  -->
  <div use:animateList class="flex flex-col">
  {#if !rosterLoaded}
    <!-- Not read yet: draw nothing rather than an empty state that is announced and then replaced. -->
  {:else if !hasRooms}
    <ContentUnavailable
      role="status"
      testid="no-rooms"
      title="No rooms yet"
      description="Rooms appear here as they sync."
    >
      {#snippet glyph()}
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3.5 13.5 6 5.5h12l2.5 8" />
          <path d="M3.5 13.5V18a1.5 1.5 0 0 0 1.5 1.5h14a1.5 1.5 0 0 0 1.5-1.5v-4.5h-5a3.5 3.5 0 0 1-7 0Z" />
        </svg>
      {/snippet}
    </ContentUnavailable>
  {:else if shown.empty !== null}
    <ContentUnavailable
      role="status"
      testid="roster-empty"
      title={shown.empty.title}
      description={shown.empty.description}
    >
      {#snippet glyph()}
        {#if chosen === "all"}
          <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3.5" y="5.5" width="17" height="13" rx="2" />
            <path d="m4 7 8 6 8-6" />
          </svg>
        {:else}
          <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M7.5 9.5h9M9.5 12.5h5M11 15.5h2" />
          </svg>
        {/if}
      {/snippet}
      {#snippet action()}
        {#if chosen !== "all"}
          <button
            type="button"
            data-testid="show-all"
            onclick={() => chooseChip("all")}
            class="rounded-control bg-accent px-3 py-1.5 text-ui font-medium text-accent-content transition-colors hover:opacity-90"
            >Show all</button
          >
        {/if}
      {/snippet}
    </ContentUnavailable>
  {:else}
    {#each shown.sections as section (section.id)}
      {#if section.title}
        <RosterSectionHeading
          title={titles.get(section.title) ?? section.title}
          detail={section.detail}
          attention={section.attention}
        />
      {/if}
      {#each section.rows as entry (entry.row.room.id)}
        <RoomRow
          row={entry.row}
          state={entry.state}
          describesAgent={entry.describesAgent}
          avatar={entry.avatar ?? null}
          selected={entry.row.room.id === selectedId}
          avatarUrl={avatarCache.get(entry.row.room.id)}
          {now}
          onSelect={chooseRoom}
          onAvatarFailed={(roomId) => avatarCache.markFailed(roomId)}
          onMenu={(id, at) => void openMenu(id, at)}
        />
      {/each}
    {/each}
  {/if}
  </div>
</nav>

{#if menu !== null}
  <RowMenu items={menu.items} at={menu.at} busy={roomSettingsWrites.busy(menu.roomId)} onChoose={(id) => void chooseMenuItem(id)} onClose={closeMenu} />
{/if}
