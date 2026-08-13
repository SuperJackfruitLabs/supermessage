<script lang="ts">
  // First screen a user sees. `m.login.password` only — the target
  // homeserver (id.agentpod.dev, Synapse) advertises no SSO/OIDC flow, so
  // this is a plain homeserver/username/password form.
  //
  // Goes through `roomsStore.login`, never `ipc.ts` directly: the core
  // restarts its room-list sequence counter on every login, and only
  // `roomsStore`'s wrapper re-arms the webview's tracker to match. See
  // `rooms.svelte.ts`'s module doc comment for the full hazard.

  import { goto } from "$app/navigation";
  import { roomsStore } from "$lib/stores/rooms.svelte";
  import type { CoreError } from "$lib/ipc";

  let homeserver = $state("https://id.agentpod.dev");
  let username = $state("");
  let password = $state("");
  let submitting = $state(false);
  let errorMessage = $state<string | null>(null);

  const canSubmit = $derived(
    !submitting && homeserver.trim() !== "" && username.trim() !== "" && password !== "",
  );

  /** Maps a `CoreError.kind` to copy a user can act on, never the raw message. */
  function messageFor(err: CoreError): string {
    switch (err.kind) {
      case "auth":
        return "Incorrect username or password.";
      case "network":
        return "Could not reach the homeserver. Check the address and your connection.";
      case "store":
        // A missing/locked OS keyring surfaces here — a local machine
        // problem, not a wrong password, so show the core's own message.
        return err.message;
      default:
        return err.message;
    }
  }

  async function handleSubmit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!canSubmit) return;

    submitting = true;
    errorMessage = null;
    try {
      await roomsStore.login(homeserver.trim(), username.trim(), password);
      await goto("/");
    } catch (err) {
      errorMessage = messageFor(err as CoreError);
    } finally {
      submitting = false;
    }
  }
</script>

<main
  class="flex min-h-dvh flex-col items-center justify-center bg-surface-sunken p-6"
  style="padding-top: calc(1.5rem + var(--inset-top)); padding-bottom: calc(1.5rem + var(--inset-bottom));"
>
  <div class="w-full max-w-sm rounded-xl border border-border bg-surface-raised p-8 shadow-sm">
    <div class="mb-6 text-center">
      <h1 class="text-xl font-semibold tracking-tight">Sign in</h1>
      <p class="mt-1 text-sm text-content-muted">Connect to your Matrix homeserver</p>
    </div>

    <form class="flex flex-col gap-4" onsubmit={handleSubmit}>
      <div class="flex flex-col gap-1.5">
        <label for="homeserver" class="text-sm font-medium text-content">Homeserver</label>
        <input
          id="homeserver"
          type="text"
          autocomplete="url"
          bind:value={homeserver}
          disabled={submitting}
          class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-content outline-none focus:border-accent disabled:opacity-60"
        />
      </div>

      <div class="flex flex-col gap-1.5">
        <label for="username" class="text-sm font-medium text-content">Username</label>
        <input
          id="username"
          type="text"
          autocomplete="username"
          bind:value={username}
          disabled={submitting}
          class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-content outline-none focus:border-accent disabled:opacity-60"
        />
      </div>

      <div class="flex flex-col gap-1.5">
        <label for="password" class="text-sm font-medium text-content">Password</label>
        <input
          id="password"
          type="password"
          autocomplete="current-password"
          bind:value={password}
          disabled={submitting}
          class="rounded-md border border-border bg-surface px-3 py-2 text-sm text-content outline-none focus:border-accent disabled:opacity-60"
        />
      </div>

      <!--
        Fixed-height reserved slot so the error appearing/disappearing never
        shifts the button below it.
      -->
      <div class="min-h-10">
        {#if errorMessage}
          <p class="selectable text-sm text-danger" role="alert">{errorMessage}</p>
        {/if}
      </div>

      <button
        type="submit"
        disabled={!canSubmit}
        class="rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-content transition-opacity disabled:opacity-60"
      >
        {submitting ? "Signing in…" : "Sign in"}
      </button>
    </form>
  </div>
</main>
