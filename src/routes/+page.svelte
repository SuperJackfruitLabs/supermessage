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
  import { connectionStore } from "$lib/stores/connection.svelte";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import { parseRoomIdentity, roomInitial } from "$lib/components/roomIdentity";
  import type { ConnectionState } from "$lib/ipc";
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

  // Same avatar-cache pattern `RoomList` uses, instantiated separately and
  // keyed by room id — the header and the roster each fetch and cache their
  // own copy rather than sharing one, per spec: no cross-component avatar
  // cache exists in this codebase to share.
  const headerAvatarCache = createAvatarCache();

  /**
   * The parsed identity (glyph/name/role) of the selected room, per spec
   * §5.1/§6.2. Falls back to `roomsStore.selectedId` as the raw name, same
   * as the header did before this parse existed, for the edge case where
   * `selectedId` points at a room not (yet) present in `roomsStore.rooms`.
   */
  const selectedIdentity = $derived(
    parseRoomIdentity(
      roomsStore.rooms.find((room) => room.id === roomsStore.selectedId)?.name ??
        roomsStore.selectedId ??
        "",
    ),
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
          -->
          <div
            class="flex shrink-0 items-center justify-between gap-3 border-b border-border px-4 py-2"
          >
            <div class="flex min-w-0 items-center gap-2">
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
                -->
                <span
                  class="min-w-0 max-w-[14ch] truncate rounded-full border border-border px-2 py-0.5 font-mono text-label text-content-muted uppercase"
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
              <button
                type="button"
                onclick={() => (showRoomInfo = !showRoomInfo)}
                aria-pressed={showRoomInfo}
                class="shrink-0 rounded-md px-2 py-1 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content {showRoomInfo
                  ? 'bg-surface-sunken text-content'
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
      </section>
      {#if roomsStore.selectedId && showRoomInfo}
        {#key roomsStore.selectedId}
          <RoomInfoPanel roomId={roomsStore.selectedId} onClose={() => (showRoomInfo = false)} />
        {/key}
      {/if}
    </div>
  </div>
{/if}
