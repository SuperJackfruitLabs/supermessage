<script lang="ts">
  // Session gate: restores a prior session if one exists, otherwise sends
  // the user to /login. This is intentionally a placeholder — the real
  // two-pane chat UI lands in a later task; don't build it here.
  //
  // Goes through `roomsStore.restoreSession`, never `ipc.ts` directly, so
  // the room-list tracker gets re-armed alongside the core's sequence
  // counter restart. See `rooms.svelte.ts`'s module doc comment.

  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { roomsStore } from "$lib/stores/rooms.svelte";

  let checking = $state(true);
  let restored = $state(false);

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
</script>

<main
  class="flex min-h-dvh flex-col items-center justify-center bg-surface p-8"
  style="padding-top: calc(2rem + var(--inset-top)); padding-bottom: calc(2rem + var(--inset-bottom));"
>
  {#if checking}
    <p class="text-sm text-content-muted">Restoring session…</p>
  {:else if restored}
    <!-- Provisional placeholder — replaced by the two-pane chat UI. -->
    <div class="text-center">
      <h1 class="text-xl font-semibold tracking-tight">supermessage</h1>
      <p class="mt-1 text-sm text-content-muted">Signed in. Chat UI not yet built.</p>
    </div>
  {/if}
</main>
