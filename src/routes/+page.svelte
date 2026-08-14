<script lang="ts">
  // The two-pane chat UI: room list on the left, timeline + composer on the
  // right, a connection banner across the top when the core isn't "live".
  //
  // Still session-gated the same way the provisional placeholder was: try
  // to restore a prior session, and if there isn't one, fall through to
  // /login. Goes through `roomsStore.restoreSession`, never `ipc.ts`
  // directly, so the room-list tracker gets re-armed alongside the core's
  // sequence counter restart — see `rooms.svelte.ts`'s module doc comment.
  //
  // This mount also happens right after a successful login, because /login
  // navigates here. That restore is a no-op by design, guarded in two
  // places: `roomsStore.restoreSession` skips it while a session is
  // established, and `Session::restore_and_start` short-circuits core-side
  // if the webview ever asks anyway. Calling it unconditionally from here
  // used to build a second `Client` and a second set of streams, which
  // froze the room list for the whole session.

  import { onDestroy, onMount, tick } from "svelte";
  import { goto } from "$app/navigation";
  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { spacesStore } from "$lib/stores/spaces.svelte";
  import { connectionStore } from "$lib/stores/connection.svelte";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import { parseRoomIdentity, roomInitial } from "$lib/components/roomIdentity";
  import { railEntries } from "$lib/components/spacesRailView";
  import type { ConnectionState } from "$lib/ipc";
  import RoomList from "$lib/components/RoomList.svelte";
  import SpacesRail from "$lib/components/SpacesRail.svelte";
  import Timeline from "$lib/components/Timeline.svelte";
  import TypingIndicator from "$lib/components/TypingIndicator.svelte";
  import Composer from "$lib/components/Composer.svelte";
  import ConnectionBanner from "$lib/components/ConnectionBanner.svelte";
  import RoomInfoPanel from "$lib/components/RoomInfoPanel.svelte";

  let checking = $state(true);
  let restored = $state(false);
  let signingOut = $state(false);

  /**
   * The two responsive breakpoints (spec §9), as media queries rather than
   * CSS classes.
   *
   * `1237.98`/`639.98` rather than `1237`/`639`: "below 1238px" has to mean
   * *below*, and a viewport is not necessarily an integer number of CSS
   * pixels (a fractional device-pixel-ratio, a zoom level, or a
   * desktop-webview window dragged to 1237.5px all produce one).
   * `max-width: 1237px` would leave 1237.5px matching neither query;
   * `1237.98px` closes that gap at the same place `min-width: 1238px`
   * opens.
   *
   * **Why 1238 and not the 840 this shipped with.** The question the panel
   * breakpoint has to answer is not "how wide is the window" but "how much
   * is left for the room pane once the roster and the panel have taken
   * theirs" — and the old query asked the first. The roster is `w-72`
   * (288px, border included) and the panel is `w-80` (320px), both fixed,
   * so the pane gets `viewport − 608` whenever the panel is a column. At
   * 839px the panel overlaid and the pane was 551px; at 840px the panel
   * took a column and the pane was **232px** — one pixel of extra window
   * bought a 58% narrower reading surface, the room name collapsed to
   * `B…`, and dispatch-card values broke mid-word into one-word-per-line
   * ladders. The whole 840–1160px band rendered that way.
   *
   * 1238 = 288 (roster) + 320 (panel) + 630 (the sheet). 630px is not a
   * round number picked to feel safe: it is the reading column's own full
   * designed width at these viewports — `max-w-[calc(72ch+4rem)]` in
   * `Timeline.svelte`, i.e. the 72ch measure (566px, measured) plus the
   * `lg:px-8` gutter it owns. So the rule this encodes is: **the panel may
   * take a column only when doing so costs the reading surface nothing.**
   * Below that the panel overlays, which narrows nothing — it is painted
   * over the pane rather than subtracting from it.
   *
   * A viewport query is the right instrument for a remaining-width test
   * here precisely *because* both other columns are fixed: `viewport ≥
   * 1238` and `viewport − 288 − 320 ≥ 630` are the same predicate, and
   * `matchMedia` can only see the first.
   *
   * **And 1294 when the spaces rail is up.** The rail is a fourth fixed
   * column, 56px wide, so on an account with spaces the same subtraction
   * reads `viewport − 344 − 320`, and the threshold that keeps the sheet at
   * its full 630 moves with it: 1294 = 56 + 288 + 320 + 630. Keeping the
   * single 1238 would have quietly re-broken the rule this constant exists
   * to encode — across 1238–1293 the panel would take a column and leave the
   * pane 574px, short of the sheet's own designed width. Not the 232px
   * catastrophe that motivated the revision, which is exactly why it would
   * have gone unnoticed.
   *
   * Two queries rather than one computed string because `matchMedia` is
   * cheap to hold open and a rail can appear (or not) after mount, when
   * `spaces_list` lands — see `panelOverlay` below, which picks between
   * them.
   */
  const PANEL_OVERLAY_QUERY = "(max-width: 1237.98px)";
  const PANEL_OVERLAY_WITH_RAIL_QUERY = "(max-width: 1293.98px)";
  const ROSTER_COLLAPSE_QUERY = "(max-width: 639.98px)";

  /** Whether the spaces rail is rendering — the same question
   * `SpacesRail.svelte` asks, through the same function, so the two can
   * never disagree about whether there is a fourth column. */
  const railVisible = $derived(railEntries(spacesStore.spaces).length > 0);

  /** The raw state of the two panel-breakpoint queries. Read through
   * `panelOverlay`, never directly. */
  let panelOverlayWithoutRail = $state(false);
  let panelOverlayWithRail = $state(false);

  /**
   * Below 1238px — or below 1294px with the spaces rail up — the room-info
   * panel stops taking a third column and overlays the room pane instead
   * (spec §9).
   */
  const panelOverlay = $derived(railVisible ? panelOverlayWithRail : panelOverlayWithoutRail);
  /**
   * Below 640px: the roster and the room pane no longer fit side by side,
   * so exactly one of them is on screen at a time and `roomPaneOpen`
   * decides which.
   *
   * This is a `$state` flag driven by `matchMedia`, not a CSS breakpoint,
   * for one specific reason: the back affordance in the room header must
   * not *exist* in the wide layout. A CSS-only collapse would leave a
   * focusable control in the tab order at every width, doing nothing at
   * most of them.
   */
  let narrow = $state(false);
  /**
   * In the collapsed layout, whether the room pane (rather than the roster)
   * is the pane on screen. Meaningless while `narrow` is false — both panes
   * are visible then.
   *
   * Deliberately separate from `roomsStore.selectedId`: the back affordance
   * clears *this*, never the selection. Keeping the room selected is what
   * makes returning to it instant and leaves the timeline subscription
   * alone — re-selecting a room re-arms it from seq 1, which is the hazard
   * `rooms.svelte.ts`'s module comment describes at length, and doing that
   * on every back-and-forth would be a real regression rather than a
   * cosmetic one. `RoomList` skips the re-select for the already-selected
   * room for the same reason.
   */
  let roomPaneOpen = $state(false);
  /**
   * Whether the room-info panel is open. Deliberately **not** reset on a
   * room switch: `RoomInfoPanel` below is itself remounted per room (`{#key
   * roomsStore.selectedId}`, same as `Timeline`), so leaving this `true`
   * across a switch means the panel stays open and simply shows the newly
   * selected room's info next — the more useful behavior, and one less
   * thing for a room-switch effect to have to remember to do.
   */
  let showRoomInfo = $state(false);

  // Same avatar-cache pattern `RoomList` uses, instantiated separately and
  // keyed by room id — the header and the roster each fetch and cache their
  // own copy rather than sharing one, per spec: no cross-component avatar
  // cache exists in this codebase to share.
  const headerAvatarCache = createAvatarCache();

  /**
   * The parsed identity (glyph/name/role) of the selected room, per spec
   * §5.1/§6.2. Falls back to `roomsStore.selectedId` as the raw name, same
   * as the header did before this parse existed, for the edge case where
   * `selectedId` names a room the store has never had a summary for.
   *
   * Reads `selectedRoomName` rather than searching `roomsStore.rooms`
   * itself, and the difference is the spaces rail: a space filter can
   * legitimately remove the *open* room from the roster (design §7 requires
   * the pane to keep showing it), and searching the roster made the header
   * fall back to `!id:server` the instant it did.
   */
  const selectedIdentity = $derived(
    parseRoomIdentity(roomsStore.selectedRoomName ?? roomsStore.selectedId ?? ""),
  );

  /**
   * The header connection dot's text alternative (spec §6.2, §9): colour is
   * never the only channel, so this word always renders beside the dot
   * inside a `role="status"` wrapper. Lowercase, unlike the banner's
   * capitalized labels, because the two surfaces have different jobs — the
   * dot is a compact at-a-glance state, the banner carries a sentence.
   */
  function connectionWord(state: ConnectionState): string {
    switch (state) {
      case "offline":
        return "offline";
      case "syncing":
        return "syncing";
      case "live":
        return "live";
      case "error":
        return "error";
    }
  }

  /**
   * Which pane is on screen. Both, unless the layout has collapsed — and
   * `roomVisible` additionally insists on a selected room, so a collapsed
   * layout can never show the room pane's "Choose a room from the roster."
   * empty state, which is the one state whose header (and with it the back
   * affordance) doesn't render.
   *
   * The two panes stay *mounted* either way and the hidden one is
   * `display: none` — not `{#if}`-ed out. Unmounting the room pane would
   * discard `Composer`'s per-room drafts and `Timeline`'s scroll position
   * on every trip back to the roster, and unmounting the roster would drop
   * `RoomList`'s avatar cache and re-fetch every avatar over IPC. Neither
   * is a cost the back button should carry. `hidden` also takes the pane
   * out of the tab order and the accessibility tree, which is what makes
   * "one pane at a time" true for a keyboard or screen-reader user too.
   */
  const rosterVisible = $derived(!narrow || !roomPaneOpen);
  const roomVisible = $derived(
    !narrow || (roomPaneOpen && roomsStore.selectedId !== null),
  );

  /**
   * Whether `RoomInfoPanel` is actually going to render. It belongs to the
   * room pane, so it renders only when that pane is on screen — otherwise
   * the overlay would float over the roster in the collapsed layout.
   */
  const panelOpen = $derived(Boolean(roomsStore.selectedId && showRoomInfo && roomVisible));

  /**
   * Whether the panel is occupying a column of its own rather than
   * overlaying the room pane — the re-derived form of the old `panelOpen`
   * test, which decided which element carries the `--inset-right`
   * safe-area padding on the assumption that an open panel always took the
   * third column.
   *
   * The rule it encodes is unchanged: the rightmost element in the layout
   * carries `--inset-right`. What changed is that an *overlaying* panel
   * doesn't relieve `<section>` of the job — the section is still the
   * rightmost column, the panel is just painted on top of part of it, and
   * the section's own composer and timeline still have to clear the safe
   * area for the whole time the panel is shut. So the section keeps the
   * padding whenever the panel is not a column, and `RoomInfoPanel` — which
   * is the rightmost content in *both* layouts whenever it renders at all —
   * carries its own.
   */
  const panelTakesColumn = $derived(panelOpen && !panelOverlay);

  /**
   * Whether the panel is a modal overlay rather than a third column — the
   * only geometry in which it gets dialog semantics. In the wide layout it
   * is a column beside the room pane, covers nothing, and must keep
   * behaving like an ordinary region: no `role="dialog"`, no `aria-modal`,
   * no scrim, no `inert` on its neighbours, and Escape left to whatever
   * else might want it.
   */
  const panelIsModal = $derived(panelOpen && panelOverlay);

  /**
   * The safe-area padding each pane carries.
   *
   * The rule is "whichever element is on an outer edge of the layout pays
   * that edge's inset", and the collapsed layout broke it by assuming the
   * two panes are always both on screen. Below 640px exactly one of them
   * is (`rosterVisible`/`roomVisible`, `display: none` for the other), so
   * the *visible* pane is on both outer edges at once and has to pay both:
   * with `--inset-left` living only on `<aside>`, the room pane's back
   * button sat at x=12 under a simulated 44px cutout, and with
   * `--inset-right` living only on `<section>`, the roster ran to x=479 of
   * a 480px viewport. Landscape on a notched phone is exactly the case the
   * collapse exists for.
   *
   * Above 640px nothing changes: the roster keeps the left inset, and the
   * right one stays with whichever element is genuinely rightmost — the
   * section, or `RoomInfoPanel` when it takes a column (see
   * `panelTakesColumn`). `narrow` implies `panelOverlay` (640 < 1238), so
   * the section never has to weigh both conditions at once.
   *
   * This now sits on the **roster group** — the wrapper holding the spaces
   * rail and the roster — rather than on `<aside>` itself, which is why the
   * rail's arrival did not need a second inset rule. Whichever of the two is
   * actually leftmost, the group is, and the padding lands outside both. The
   * wrapper carries `bg-surface-sunken` so the padded strip is still the
   * roster's own ground under a cutout rather than a bar of the shell's
   * `--color-surface` showing through.
   */
  const rosterGroupInsets = $derived(
    narrow
      ? "padding-left: var(--inset-left); padding-right: var(--inset-right);"
      : "padding-left: var(--inset-left);",
  );
  const sectionInsets = $derived(
    (narrow ? "padding-left: var(--inset-left); " : "") +
      (panelTakesColumn ? "" : "padding-right: var(--inset-right);"),
  );

  /**
   * The `Info` button, so focus can be put back on it when the modal
   * overlay closes. A modal that returns focus to `document.body` strands
   * a keyboard user at the top of the page with no memory of where they
   * were; returning it to the control that opened the panel is the whole
   * contract.
   */
  let infoButton = $state<HTMLButtonElement | null>(null);

  /**
   * Closes the panel and restores focus to `Info`.
   *
   * `await tick()` is load-bearing, not politeness: while the overlay is
   * open the room pane is `inert`, and `Info` lives inside it. Focusing an
   * inert element is a no-op, so the focus call has to happen after Svelte
   * has flushed the DOM update that takes `inert` back off.
   */
  async function closeRoomInfo(): Promise<void> {
    const restoreFocus = panelIsModal;
    showRoomInfo = false;
    if (!restoreFocus) return;
    await tick();
    infoButton?.focus();
  }

  /**
   * Escape dismisses the modal overlay. Bound on the window rather than on
   * the panel because the panel is not guaranteed to hold focus — the
   * operator may have clicked the scrim, or focus may have been reset by
   * something outside this component — and a modal that only closes while
   * it happens to be focused is not really dismissible.
   *
   * Guarded on `panelIsModal`: in the column layout Escape must do
   * nothing, because there the panel is not a dialog and nothing is
   * covered.
   */
  function onWindowKeydown(event: KeyboardEvent): void {
    if (!panelIsModal || event.key !== "Escape") return;
    event.preventDefault();
    void closeRoomInfo();
  }

  /**
   * Returns the collapsed layout to the roster. Clears the pane, never the
   * selection — see `roomPaneOpen`.
   */
  function backToRoster(): void {
    roomPaneOpen = false;
  }

  /**
   * Whether a file is currently being dragged over the window.
   *
   * The attachments design (§8) asks for a visible drop state, "or the
   * reader cannot tell the app accepts drops at all", in `--color-accent` —
   * this is not a decision, so it does not get the signal colour.
   *
   * **What actually drives it, stated honestly, because the answer is
   * platform-dependent.** The drop itself is handled entirely in Rust
   * (`core::attachments::on_files_dropped`, which stages the file and emits
   * `sm://attachment/staged`); nothing here touches the dropped file, reads
   * `dataTransfer`, or listens for Tauri's `tauri://drag-drop` — see
   * `Composer.svelte`'s listener comment for why that last one is a rule
   * rather than a preference. What this needs is only the *hover* fact, and
   * the DOM's own drag events are the one source of it that carries no
   * paths at all. They are best-effort: Tauri's OS-level drag-drop handler
   * intercepts drags before the webview sees them on Windows (which is why
   * `dragDropEnabled: false` exists), so on that platform this state may
   * never light up and the standing hint on the composer's attach control
   * ("or drop one on the window") is what tells the reader drops work. The
   * alternative — `tauri://drag-enter`/`drag-over` — reports the hover
   * reliably on every platform *and* hands the webview the raw paths of the
   * files being dragged, which is precisely the thing §3's discipline
   * exists to refuse.
   *
   * `preventDefault` on both events is defensive, not participatory: if a
   * drag *does* reach the webview, the default action for dropping a file
   * on a page is to navigate to it, which would replace the running app
   * with a `file://` view of whatever was dropped. Cancelling the default
   * suppresses that; the Rust handler is upstream and unaffected.
   */
  let dropActive = $state(false);
  /**
   * `dragleave` fires on every element boundary crossed, including ones
   * inside the window, so it cannot be trusted on its own to mean "the drag
   * has left". A short grace period that any subsequent `dragover` cancels
   * is the standard fix. The longer idle timer below covers the case that
   * has no DOM event at all: a drop consumed by Tauri's own handler, after
   * which no further `dragover` ever arrives.
   */
  const DROP_LEAVE_GRACE_MS = 120;
  const DROP_IDLE_MS = 1200;
  let dropClearTimer: ReturnType<typeof setTimeout> | undefined;

  function scheduleDropClear(delayMs: number): void {
    clearTimeout(dropClearTimer);
    dropClearTimer = setTimeout(() => {
      dropActive = false;
    }, delayMs);
  }

  function onWindowDragOver(event: DragEvent): void {
    event.preventDefault();
    dropActive = true;
    scheduleDropClear(DROP_IDLE_MS);
  }

  function onWindowDragLeave(): void {
    scheduleDropClear(DROP_LEAVE_GRACE_MS);
  }

  function onWindowDrop(event: DragEvent): void {
    // Deliberately reads nothing off `event.dataTransfer`: the file is the
    // Rust handler's business, and this handler's only job is to stop the
    // webview navigating to it.
    event.preventDefault();
    clearTimeout(dropClearTimer);
    dropActive = false;
  }

  onDestroy(() => clearTimeout(dropClearTimer));

  /**
   * Whether to *show* the drop state. A drop with no room focused is
   * ignored by the core (`on_files_dropped` logs "no room is focused" and
   * returns), so promising otherwise would be a lie — and the strip it
   * would produce has nowhere to appear, since the composer only exists
   * inside a selected room.
   */
  const showDropState = $derived(dropActive && roomVisible && roomsStore.selectedId !== null);

  // The `matchMedia` listeners behind `panelOverlay`/`narrow`. A separate,
  // *synchronous* `onMount` from the session-restore one below, because
  // only a synchronous callback's return value is used as the unmount
  // teardown — an `async` one returns a promise Svelte will not call.
  onMount(() => {
    const overlayQuery = window.matchMedia(PANEL_OVERLAY_QUERY);
    const railOverlayQuery = window.matchMedia(PANEL_OVERLAY_WITH_RAIL_QUERY);
    const collapseQuery = window.matchMedia(ROSTER_COLLAPSE_QUERY);

    function applyCollapse(matches: boolean): void {
      // On the transition *into* the collapsed layout, land on whatever the
      // operator was already reading: the room pane if a room is selected,
      // the roster if not. Only on the edge — recomputing this on every
      // resize event would yank a reader who has deliberately gone back to
      // the roster into the room again.
      if (matches && !narrow) roomPaneOpen = roomsStore.selectedId !== null;
      narrow = matches;
    }
    function onCollapseChange(event: MediaQueryListEvent): void {
      applyCollapse(event.matches);
    }
    function onOverlayChange(event: MediaQueryListEvent): void {
      panelOverlayWithoutRail = event.matches;
    }
    function onRailOverlayChange(event: MediaQueryListEvent): void {
      panelOverlayWithRail = event.matches;
    }

    panelOverlayWithoutRail = overlayQuery.matches;
    panelOverlayWithRail = railOverlayQuery.matches;
    applyCollapse(collapseQuery.matches);
    overlayQuery.addEventListener("change", onOverlayChange);
    railOverlayQuery.addEventListener("change", onRailOverlayChange);
    collapseQuery.addEventListener("change", onCollapseChange);
    return () => {
      overlayQuery.removeEventListener("change", onOverlayChange);
      railOverlayQuery.removeEventListener("change", onRailOverlayChange);
      collapseQuery.removeEventListener("change", onCollapseChange);
    };
  });

  onMount(async () => {
    try {
      restored = await roomsStore.restoreSession();
    } catch (err) {
      // No session to restore, or a store/network hiccup while checking —
      // either way there's nothing to show here. Login screen handles
      // reporting typed errors to the user; this gate just falls through.
      console.error("restoreSession failed", err);
      restored = false;
    } finally {
      checking = false;
    }
    if (!restored) {
      await goto("/login");
      return;
    }
    // Session start is the spaces rail's one-shot fetch (spaces-rail design
    // §5). Spaces change far less than the room list, so they are not a
    // third streamed channel — and this is the moment there is a session to
    // ask. Not awaited: the rail appearing (or not) must not hold up the
    // roster and timeline, which are already streaming by now, and `load`
    // reports its own failures rather than throwing.
    void spacesStore.load();
  });

  /**
   * Signs out and returns to the login screen.
   *
   * Navigates to /login whichever way the command goes. The core clears the
   * session, secrets and stores before it can fail (the only failure left
   * after that point is deleting the store directory), and
   * `roomsStore.logout` clears local state in a `finally` — so on an error
   * the user is logged out regardless, and leaving them staring at a room
   * list for an account that no longer exists would be the worse outcome.
   */
  async function signOut(): Promise<void> {
    if (signingOut) return;
    signingOut = true;
    try {
      await roomsStore.logout();
    } catch (err) {
      console.error("logout failed", err);
    } finally {
      // Whichever way logout went, the account's spaces are no longer this
      // app's to show — `roomsStore.logout` clears the room list in its own
      // `finally` for exactly the same reason. Without this the next
      // account's mount would render the previous one's rail for the frames
      // between restoring and its own `spaces_list` landing.
      spacesStore.clear();
      signingOut = false;
    }
    await goto("/login");
  }
</script>

<!--
  Top level, not inside the `restored` branch: `<svelte:window>` may only
  appear at the top level of a component. The handler is a no-op unless
  `panelIsModal`, which cannot be true before the app has rendered, so an
  always-mounted listener costs one comparison per keystroke and nothing
  else.
-->
<svelte:window
  onkeydown={onWindowKeydown}
  ondragover={onWindowDragOver}
  ondragleave={onWindowDragLeave}
  ondrop={onWindowDrop}
/>

{#if checking}
  <main
    class="flex min-h-dvh flex-col items-center justify-center bg-surface p-8"
    style="padding-top: calc(2rem + var(--inset-top)); padding-bottom: calc(2rem + var(--inset-bottom));"
  >
    <p class="text-ui text-content-muted">Restoring session…</p>
  </main>
{:else if restored}
  <div class="flex h-dvh flex-col bg-surface" style="padding-top: var(--inset-top); padding-bottom: var(--inset-bottom);">
    <ConnectionBanner />
    <!--
      `relative`: the containing block an overlaying `RoomInfoPanel`
      positions against below 840px. It is the pane row, not the whole app
      shell, so the overlay spans the height between the connection banner
      and the bottom safe area rather than covering the banner too.
    -->
    <div class="relative flex min-h-0 flex-1">
      <!--
        The roster group: the spaces rail and the roster, which travel
        together.

        **The rail collapses with the roster, and that is the whole narrow
        -layout decision** (spaces-rail design §7). Below 640px exactly one
        pane is on screen, and the rail is part of the navigation pane, not
        of the room pane — so when the roster is showing, the rail is beside
        it, and when the room pane is showing, both are gone. The reader
        reaches the rail exactly the way they already reach the roster: the
        header's "‹ Rooms" button.

        The alternative — hiding the rail below 640px while keeping the
        filter — is the one thing §7 rules out by name: a space selected at
        1905px would survive a resize to 700px with no affordance left to
        clear it, and the roster would look like an account that had lost
        most of its rooms. Keeping the rail costs 56px of a 640px-or-narrower
        viewport, which the roster (already `flex-1` there) absorbs; losing
        the way back costs the reader their rooms.

        Everything that used to live on `<aside>` and describes *the
        navigation column as a whole* moved here: which pane is visible, the
        safe-area insets (see `rosterGroupInsets`), and `inert` while the
        info panel is a modal overlay — the rail is behind that overlay at
        700px exactly as the roster is, and `aria-modal`'s claim has to be
        true of both. `<aside>`'s own classes are otherwise unchanged.
      -->
      <div
        class="{rosterVisible ? 'flex' : 'hidden'} {narrow
          ? 'min-w-0 flex-1'
          : 'shrink-0'} bg-surface-sunken"
        style={rosterGroupInsets}
        inert={panelIsModal}
      >
        <SpacesRail />
        <aside
          class="flex {narrow
            ? 'min-w-0 flex-1'
            : 'w-72 shrink-0'} flex-col border-r border-border bg-surface-sunken"
        >
          <div class="min-h-0 flex-1">
            <!--
              `onSelect` is how the collapsed layout learns a room was
              chosen. It is a notification, not the selection itself:
              `RoomList` still owns the `roomsStore.select` call (see its doc
              comment), and this page only decides which pane to show as a
              result. A `$effect` watching `selectedId` could not do this job
              — choosing the room that is *already* selected doesn't change
              that value, so returning to it from the roster would silently
              do nothing.
            -->
            <RoomList onSelect={() => (roomPaneOpen = true)} />
          </div>
          <!--
            The only user-reachable way out of a session: switch accounts,
            clear a corrupted local store, or wipe local history and crypto
            keys off this device. Parked at the foot of the sidebar rather
            than given chrome of its own — it's a rarely-used escape hatch,
            not a primary action, and M0 has no account menu to hang it off.
          -->
          <div class="shrink-0 border-t border-border p-2">
            <button
              type="button"
              onclick={signOut}
              disabled={signingOut}
              class="w-full rounded-md px-3 py-2 text-left text-ui text-content-muted transition-colors hover:bg-surface hover:text-content disabled:opacity-60"
            >
              {signingOut ? "Signing out…" : "Sign out"}
            </button>
          </div>
        </aside>
      </div>
      <!--
        `inert` while the panel is a modal overlay, and this is the finding
        it closes: the overlay is opaque and pinned over this pane's right
        320px, but every control underneath it stayed in the tab order —
        57 of them at 480px in the harness, including a pending decision's
        Approve and Decline buttons, which a keyboard user could reach and
        activate without ever seeing them. `inert` removes the whole
        subtree from the tab order, from hit-testing and from the
        accessibility tree in one attribute, which is exactly the scope
        wanted; a hand-rolled focus trap would have covered the Tab case
        and left `Decline` half-visible and still clickable with a mouse.

        The roster carries it too (see its own attribute): `aria-modal`
        claims everything outside the dialog is unavailable, and it has to
        be true at 700px where the roster is beside the overlay rather
        than under it. The scrim below is what makes that visible instead
        of merely factual.
      -->
      <!--
        `relative` unconditionally, so the drop notice below can be pinned
        inside this pane without the class list changing between states —
        the same reasoning as Send's `border-transparent`: a box that
        measures the same in both states cannot shift by a pixel when it
        changes.

        The drop state itself is an inset 2px `--color-accent` outline on
        this pane, matching the focus ring's own weight and offset (spec §9)
        because it means the same kind of thing: *this* is what your input
        is about to land in. `outline` rather than a border, so nothing
        reflows while a file is in the air.
      -->
      <section
        class="{roomVisible ? 'flex' : 'hidden'} relative min-w-0 flex-1 flex-col {showDropState
          ? 'outline outline-2 -outline-offset-2 outline-accent'
          : ''}"
        style={sectionInsets}
        inert={panelIsModal}
      >
        {#if roomsStore.selectedId}
          {@const headerAvatar = headerAvatarCache.get(roomsStore.selectedId)}
          <!--
            The only header this room pane has: the selected room's parsed
            identity (avatar, name, role chip — spec §5.1/§6.2, already
            known from `roomsStore.rooms`, no extra fetch) plus the
            connection dot and the one way to reach the room-info panel —
            there was previously no surface at all for a room's
            topic/alias/member list; see `RoomInfoPanel.svelte`'s doc
            comment.

            No member count here by design (spec §6.2): it only comes from
            `roomInfo`, fetched when the panel opens, and a stale or absent
            number would be worse than none.

            `bg-surface-sunken`, so the header joins the field rather than
            capping the sheet. It was the last element outside the depth
            stack: the roster, the timeline field and the composer are all
            sunken, and the header — inheriting the shell's
            `--color-surface` — was a full-width lit bar sitting over them.
            Rendered side by side at 1905px and 700px in both themes, that
            reads as two lit regions in an L rather than one column, and
            the sheet stops being *the* lit surface. Sunken makes the
            header and the composer a matching pair of trays bracketing the
            reading column, which is what the 700px case makes obvious:
            there the sheet fills the pane, so the trays are the only thing
            giving it edges.

            It does not vanish into the roster it now shares a tone with —
            the roster's own `border-r` runs the full height of the pane
            row, including past this header, and the `border-b` below
            separates it from the field. The hairline matters *more* than
            it did (the tone step it used to have is gone) and happens to
            get stronger in dark at the same time: `--color-border` reads
            1.30:1 on `surface` and 1.41:1 on the darkened `sunken`.
          -->
          <div
            class="flex shrink-0 items-center justify-between gap-3 border-b border-border bg-surface-sunken px-4 py-2"
          >
            <div class="flex min-w-0 items-center gap-2">
              {#if narrow}
                <!--
                  The way back to the roster, and the reason `narrow` is a
                  `$state` flag rather than a CSS breakpoint: above 640px
                  the roster is already on screen, so this button would be
                  a control that does nothing — reachable by Tab, named to
                  a screen reader, and inert. It must not exist there, so
                  it isn't rendered there.

                  A word, not just the `‹`: the glyph is `aria-hidden`
                  decoration in the same mono register as the composer's
                  `›` prompt, and "Rooms" is what actually names the
                  destination. `-ml-1` pulls its padding back so the label
                  optically aligns with the header's own left edge.

                  `hover:bg-surface`, not `hover:bg-surface-sunken`: the
                  header is sunken now, so a sunken hover would be no
                  hover at all. A control on the sunken ground lifts to
                  `surface` — the convention the roster on the same ground
                  already uses for `Sign out`.
                -->
                <button
                  type="button"
                  onclick={backToRoster}
                  class="-ml-1 flex shrink-0 items-center gap-1 rounded-md px-2 py-1 text-ui font-medium text-content-muted transition-colors hover:bg-surface hover:text-content"
                >
                  <span aria-hidden="true" class="font-mono">‹</span>
                  Rooms
                </button>
              {/if}
              {#if headerAvatar}
                <img
                  src={headerAvatar}
                  alt=""
                  aria-hidden="true"
                  class="h-6 w-6 shrink-0 rounded-full object-cover"
                  onerror={() => headerAvatarCache.markFailed(roomsStore.selectedId ?? "")}
                />
              {:else}
                <span
                  class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-surface-raised text-ui font-medium text-content"
                  aria-hidden="true"
                >
                  {roomInitial(selectedIdentity)}
                </span>
              {/if}
              <span class="min-w-0 truncate text-ui-lg text-content">{selectedIdentity.name}</span>
              {#if selectedIdentity.role !== null}
                <!--
                  `min-w-0` and a `max-w`, not `shrink-0`: a `shrink-0` flex
                  item never shrinks below its own nowrap content width, so
                  pairing it with `truncate` makes the truncation dead code
                  and lets a long role push the connection dot and `Info`
                  button out of the header. The role is bounded to 40
                  characters by `parseRoomIdentity`, which caps the damage
                  but does not prevent it. The name is the more important
                  half of the identity, so the chip is the one given a hard
                  ceiling and told to give way first.

                  The ceiling is written out in full because the two
                  obvious short forms are both wrong, and the first one
                  shipped. `max-w-[14ch]` was a *border-box* cap — Tailwind
                  sets `box-sizing: border-box` globally — so `px-2` and
                  the 1px border ate into it: 84px of box, 66px of room,
                  and `SQUAD LEAD` needs 68. The spec's own canonical role
                  (§1, §5.1, §6.2) rendered `SQUAD LE…` at 1905px while the
                  roster row two inches away showed it whole; `CODE &
                  BUILD` (98px) lost four characters. Widening to
                  `max-w-[16ch]` would not have fixed it either: `ch` is
                  the *advance* of `0`, and `--text-label` adds `0.08em` of
                  tracking to every character, so 16ch of box is only
                  ~11 characters of chip.

                  So the cap names what it actually means — sixteen
                  characters of this element's own text (`16ch` of advance
                  plus `16 × 0.08em` of tracking), plus the box it sits in
                  (`px-2` = 1rem, and 2px of border). 127px total, 109px of
                  text. `CODE & BUILD` fits with 29px to spare, and the
                  40-character worst case still truncates rather than
                  reaching the connection dot.
                -->
                <span
                  class="min-w-0 max-w-[calc(16ch+1.28em+1rem+2px)] truncate rounded-full border border-border px-2 py-0.5 font-mono text-label text-content-muted uppercase"
                >
                  {selectedIdentity.role}
                </span>
              {/if}
            </div>
            <div class="flex shrink-0 items-center gap-3">
              <!--
                Colour is never the sole channel here (spec §9): the dot's
                fill state (filled only for "live") and the word beside it
                both carry the state, and `role="status"` names the pair to
                assistive tech. Never amber — `--color-signal` is reserved
                exclusively for the pending-decision card (spec §3, §6.2).
              -->
              <span class="flex items-center gap-1.5" role="status">
                <span
                  aria-hidden="true"
                  class="h-2 w-2 rounded-full {connectionStore.state === 'live'
                    ? 'bg-content-muted'
                    : connectionStore.state === 'error'
                      ? 'border border-danger'
                      : 'border border-content-muted'}"
                ></span>
                <span
                  class="font-mono text-meta {connectionStore.state === 'error'
                    ? 'text-danger'
                    : 'text-content-muted'}"
                >
                  {connectionWord(connectionStore.state)}
                </span>
              </span>
              <!--
                Both grounds inverted with the header (see its comment):
                on a sunken bar, `bg-surface-sunken` for hover and pressed
                is 1.0:1 against the ground — the states would exist in the
                markup and nowhere on screen. This mirrors the roster row,
                which is the app's other control on a sunken ground and
                already solves the same problem the same way: `bg-surface`
                for the sustained state, `bg-surface/60` for hover, so the
                two are told apart rather than collapsing into each other.
                Measured after the swap: hover 1.054:1 light / 1.042:1
                dark, pressed 1.090:1 / 1.088:1 against the header. The
                pressed state is now *stronger* in dark than it was
                (1.035:1). `aria-pressed` carries it regardless — the
                ground was never the only channel — but a state a sighted
                operator cannot see is still a state that is not working.
              -->
              <button
                type="button"
                bind:this={infoButton}
                onclick={() => (showRoomInfo ? void closeRoomInfo() : (showRoomInfo = true))}
                aria-pressed={showRoomInfo}
                class="shrink-0 rounded-md px-2 py-1 text-ui font-medium text-content-muted transition-colors hover:bg-surface/60 hover:text-content {showRoomInfo
                  ? 'bg-surface text-content'
                  : ''}"
              >
                Info
              </button>
            </div>
          </div>
          {#key roomsStore.selectedId}
            <Timeline roomId={roomsStore.selectedId} />
          {/key}
          <TypingIndicator />
          <Composer roomId={roomsStore.selectedId} />
        {:else}
          <div class="flex flex-1 items-center justify-center">
            <p class="text-ui text-content-muted">Choose a room from the roster.</p>
          </div>
        {/if}
        {#if showDropState}
          <!--
            The word half of the drop state — an outline alone says
            "something is happening here", not "let go and this becomes an
            attachment". Sits just above the composer, where the staged
            strip is about to appear, so the eye is already in the right
            place when it does.

            `aria-hidden` and `pointer-events-none`: this is a pointer-only
            affordance with no keyboard or screen-reader equivalent (there
            is no way to drag a file without a pointer), and announcing a
            transient hover state would be noise. The attach button's own
            accessible name is the reachable path to the same outcome.
          -->
          <div
            class="pointer-events-none absolute inset-x-0 bottom-0 z-10 flex justify-center pb-24"
            aria-hidden="true"
          >
            <p
              class="rounded-full border border-accent bg-surface-raised px-3 py-1 font-mono text-label text-accent uppercase"
            >
              Drop to attach
            </p>
          </div>
        {/if}
      </section>
      {#if panelIsModal}
        <!--
          The scrim, and the honest half of `aria-modal`. Everything behind
          the overlay is `inert` while it is open, which at 700px includes
          a roster the overlay does not cover: without a veil that roster
          would look ordinary and silently refuse clicks, which is a worse
          bug than the one `inert` fixes. The scrim says "this is not
          available right now" in the one channel a mouse user reads.

          `bg-scrim`, not a token at an opacity modifier: a wash of
          `--color-surface-sunken` over the roster — which is already
          `--color-surface-sunken` — is a 1.0:1 no-op in light, the same
          way the header's own hover state was before the branch's last
          commit. `--color-scrim` is defined once in `app.css` and darkens
          in both themes.

          Not a `<button>`: it would be a second control named the same
          thing as `Close room info` and a tab stop *outside* the dialog it
          is scrimming. Escape and the panel's own ✕ are the labelled ways
          out; this is the unlabelled convenience, so it is `aria-hidden`
          and reachable only by pointer.
        -->
        <!-- svelte-ignore a11y_click_events_have_key_events -->
        <!-- svelte-ignore a11y_no_static_element_interactions -->
        <div
          class="absolute inset-0 z-10 bg-scrim"
          aria-hidden="true"
          onclick={() => void closeRoomInfo()}
        ></div>
      {/if}
      {#if panelOpen && roomsStore.selectedId}
        <!--
          One call site, two geometries (spec §9). Below 1238px the wrapper
          is an absolutely positioned overlay pinned to the right of the
          pane row and running its full height; at or above it, the wrapper
          is `display: contents` — it disappears from layout entirely and
          `RoomInfoPanel`'s own `<aside>` stays a direct flex item of the
          row, exactly the third column it was before this task. That is
          why this is one wrapper and not two branches rendering the same
          component: nothing about the panel itself changes between the two
          layouts, only whether it is in flow.

          `modal` is the one thing that does change, and it is passed down
          rather than applied here: the dialog role belongs on the panel's
          own `<aside>`, where it replaces that element's implicit
          `complementary` role and reuses its existing `aria-label` as the
          dialog's name. Putting it on this wrapper instead would nest a
          "Room info" dialog around a "Room info" region.
        -->
        <div
          class={panelOverlay
            ? "absolute inset-y-0 right-0 z-20 flex max-w-full"
            : "contents"}
        >
          {#key roomsStore.selectedId}
            <RoomInfoPanel
              roomId={roomsStore.selectedId}
              modal={panelIsModal}
              onClose={() => void closeRoomInfo()}
            />
          {/key}
        </div>
      {/if}
    </div>
  </div>
{/if}
