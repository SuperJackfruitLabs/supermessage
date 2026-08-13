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

  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { roomsStore } from "$lib/stores/rooms.svelte";
  import RoomList from "$lib/components/RoomList.svelte";
  import Timeline from "$lib/components/Timeline.svelte";
  import TypingIndicator from "$lib/components/TypingIndicator.svelte";
  import Composer from "$lib/components/Composer.svelte";
  import ConnectionBanner from "$lib/components/ConnectionBanner.svelte";
  import RoomInfoPanel from "$lib/components/RoomInfoPanel.svelte";

  let checking = $state(true);
  let restored = $state(false);
  let signingOut = $state(false);
  /**
   * Whether the room-info panel is open. Deliberately **not** reset on a
   * room switch: `RoomInfoPanel` below is itself remounted per room (`{#key
   * roomsStore.selectedId}`, same as `Timeline`), so leaving this `true`
   * across a switch means the panel stays open and simply shows the newly
   * selected room's info next — the more useful behavior, and one less
   * thing for a room-switch effect to have to remember to do.
   */
  let showRoomInfo = $state(false);

  const selectedRoomName = $derived(
    roomsStore.rooms.find((room) => room.id === roomsStore.selectedId)?.name ??
      roomsStore.selectedId,
  );

  /**
   * Whether `RoomInfoPanel` is actually going to render. Drives which of
   * `<section>`/`RoomInfoPanel` gets the `--inset-right` safe-area padding
   * below: whichever one is currently the rightmost visible content, since
   * only one of them ever is.
   */
  const panelOpen = $derived(Boolean(roomsStore.selectedId && showRoomInfo));

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
    }
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
      signingOut = false;
    }
    await goto("/login");
  }
</script>

{#if checking}
  <main
    class="flex min-h-dvh flex-col items-center justify-center bg-surface p-8"
    style="padding-top: calc(2rem + var(--inset-top)); padding-bottom: calc(2rem + var(--inset-bottom));"
  >
    <p class="text-sm text-content-muted">Restoring session…</p>
  </main>
{:else if restored}
  <div class="flex h-dvh flex-col bg-surface" style="padding-top: var(--inset-top); padding-bottom: var(--inset-bottom);">
    <ConnectionBanner />
    <div class="flex min-h-0 flex-1">
      <aside
        class="flex w-72 shrink-0 flex-col border-r border-border bg-surface-sunken"
        style="padding-left: var(--inset-left);"
      >
        <div class="min-h-0 flex-1">
          <RoomList />
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
            class="w-full rounded-md px-3 py-2 text-left text-sm text-content-muted transition-colors hover:bg-surface hover:text-content disabled:opacity-60"
          >
            {signingOut ? "Signing out…" : "Sign out"}
          </button>
        </div>
      </aside>
      <section
        class="flex min-w-0 flex-1 flex-col"
        style={panelOpen ? "" : "padding-right: var(--inset-right);"}
      >
        {#if roomsStore.selectedId}
          <!--
            The only header this room pane has: the selected room's name
            (already known from `roomsStore.rooms`, no extra fetch) plus the
            one way to reach the room-info panel — there was previously no
            surface at all for a room's topic/alias/member list; see
            `RoomInfoPanel.svelte`'s doc comment.
          -->
          <div
            class="flex shrink-0 items-center justify-between gap-2 border-b border-border px-4 py-2"
          >
            <span class="min-w-0 truncate text-sm font-medium text-content">
              {selectedRoomName}
            </span>
            <button
              type="button"
              onclick={() => (showRoomInfo = !showRoomInfo)}
              aria-pressed={showRoomInfo}
              class="shrink-0 rounded-md px-2 py-1 text-xs font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content {showRoomInfo
                ? 'bg-surface-sunken text-content'
                : ''}"
            >
              Room info
            </button>
          </div>
          {#key roomsStore.selectedId}
            <Timeline roomId={roomsStore.selectedId} />
          {/key}
          <TypingIndicator />
          <Composer roomId={roomsStore.selectedId} />
        {:else}
          <div class="flex flex-1 items-center justify-center">
            <p class="text-sm text-content-muted">Select a room to start chatting</p>
          </div>
        {/if}
      </section>
      {#if roomsStore.selectedId && showRoomInfo}
        {#key roomsStore.selectedId}
          <RoomInfoPanel roomId={roomsStore.selectedId} onClose={() => (showRoomInfo = false)} />
        {/key}
      {/if}
    </div>
  </div>
{/if}
