<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";

  type CoreStatus = {
    platform: string;
    cryptoProvider: string;
    sdkReady: boolean;
  };

  // M0 smoke test: proves the Svelte <-> Rust core bridge is wired.
  // This screen is scaffolding — the room list replaces it.
  const status = invoke<CoreStatus>("core_status");
</script>

<main
  class="flex min-h-dvh flex-col items-center justify-center gap-6 bg-surface p-8"
  style="padding-top: calc(2rem + var(--inset-top)); padding-bottom: calc(2rem + var(--inset-bottom));"
>
  <div class="text-center">
    <h1 class="text-2xl font-semibold tracking-tight">supermessage</h1>
    <p class="mt-1 text-sm text-content-muted">
      M0 spine — scaffold up, Matrix core not yet connected.
    </p>
  </div>

  {#await status}
    <p class="text-sm text-content-muted">Contacting core…</p>
  {:then s}
    <dl
      class="grid grid-cols-[auto_auto] gap-x-6 gap-y-2 rounded-lg border border-border bg-surface-raised px-6 py-4 text-sm"
    >
      <dt class="text-content-muted">Platform</dt>
      <dd class="selectable font-mono">{s.platform}</dd>
      <dt class="text-content-muted">TLS provider</dt>
      <dd class="selectable font-mono">{s.cryptoProvider}</dd>
      <dt class="text-content-muted">Matrix SDK</dt>
      <dd class="font-mono">{s.sdkReady ? "linked" : "missing"}</dd>
    </dl>
  {:catch error}
    <p class="selectable text-sm text-red-500">Core unreachable: {error}</p>
  {/await}
</main>
