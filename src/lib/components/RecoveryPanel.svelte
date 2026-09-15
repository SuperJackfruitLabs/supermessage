<script lang="ts">
  // The key that gets a conversation back.
  //
  // Room keys live in this device's encrypted store. Without recovery, a lost
  // or reset device takes every encrypted conversation on it — there is no
  // server-side copy to fall back on, because that is what end-to-end
  // encryption means. Backup uploads those keys encrypted under a key the
  // server never sees; this screen is where the user gets that key, and where
  // a new device uses it.
  //
  // Three states, three different screens, because they are three different
  // situations and a single "Recovery" page that made the reader work out
  // which one they were in would be the worst version of this.

  /**
   * The state, and the two things that can be done about it.
   *
   * Callbacks rather than IPC calls so a story can drive the whole screen with
   * no Tauri host — the same reason `NewRoomPanel` takes `onCreate`.
   */
  export interface Props {
    state: "enabled" | "disabled" | "incomplete" | "unknown";
    onEnable: () => Promise<string>;
    onRecover: (key: string) => Promise<void>;
    onReset: (password: string) => Promise<string>;
    onClose: () => void;
  }

  let { state: recovery, onEnable, onRecover, onReset, onClose }: Props = $props();

  let busy = $state(false);
  let resetting = $state(false);
  let password = $state("");
  let failure = $state<string | null>(null);
  /**
   * The key, held only until this panel closes.
   *
   * Never persisted and never sent anywhere. The user copies it now or asks
   * for a new one later — there is deliberately no way to re-display it,
   * because an app that can show you your recovery key again is an app that
   * stored it.
   */
  let freshKey = $state<string | null>(null);
  let copied = $state(false);
  let entered = $state("");

  async function enable(): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      freshKey = await onEnable();
    } catch (err) {
      failure = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }

  /**
   * Throw the old identity away and start again.
   *
   * The only way out for somebody with no recovery key and no device holding
   * the secrets. Destructive, and the copy above says so before the password
   * field appears.
   */
  async function reset(): Promise<void> {
    if (busy || password === "") return;
    busy = true;
    failure = null;
    try {
      freshKey = await onReset(password);
      resetting = false;
      password = "";
    } catch (err) {
      failure = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }

  async function recover(): Promise<void> {
    if (busy || entered.trim() === "") return;
    busy = true;
    failure = null;
    try {
      await onRecover(entered.trim());
      onClose();
    } catch (err) {
      // Led with what the reader can act on. By far the likeliest cause is a
      // mistyped key, and `M_FORBIDDEN` on its own tells them nothing about
      // that — but the detail stays, because the second likeliest cause is
      // something this sentence would be wrong about.
      const detail = err instanceof Error ? err.message : String(err);
      failure = `That key was not accepted — check it for typos. (${detail})`;
    } finally {
      busy = false;
    }
  }

  async function copy(): Promise<void> {
    if (!freshKey) return;
    try {
      await navigator.clipboard.writeText(freshKey);
      copied = true;
    } catch {
      // A clipboard the browser refuses is not a failure worth a red box —
      // the key is on screen and selectable either way.
      copied = false;
    }
  }
</script>

<div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button type="button" aria-label="Close" class="absolute inset-0 bg-scrim" onclick={onClose}
  ></button>

  <div
    role="dialog"
    tabindex="-1"
    aria-label="Encryption recovery"
    class="relative z-10 flex w-full max-w-md flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    }}
  >
    <h2 class="text-ui font-medium text-content">Encryption recovery</h2>

    {#if freshKey}
      <!--
        The one moment this key exists outside the SDK. Said plainly, because a
        user who closes this without saving it has lost nothing *today* and
        everything on the day their phone goes in a river.
      -->
      <p class="text-ui text-content-muted">
        Save this somewhere safe. It is shown once, and it is the only way to read your
        encrypted messages on a new device.
      </p>
      <code
        class="select-all break-all rounded-control border border-border bg-surface-sunken px-3 py-2 font-mono text-ui text-content"
        data-testid="recovery-key">{freshKey}</code
      >
      <div class="flex gap-2">
        <button
          type="button"
          onclick={copy}
          class="rounded-control bg-surface-sunken px-3 py-2 text-ui text-content transition-colors hover:bg-surface-sunken/70"
        >
          {copied ? "Copied" : "Copy"}
        </button>
        <button
          type="button"
          onclick={onClose}
          class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken/60"
        >
          I have saved it
        </button>
      </div>
    {:else if recovery === "unknown"}
      <!--
        Not "no recovery set up". Offering to generate a key to somebody who
        already has one is how the first one gets orphaned, and this state is
        simply "we have not finished asking".
      -->
      <p class="text-ui text-content-muted">Checking this account…</p>
    {:else if recovery === "incomplete" || recovery === "disabled"}
      <!--
        Stranded: this device cannot read the history. `incomplete` and
        `disabled` read the same to a person and differ only in which action
        fixes it, which the two buttons already say — so they share a screen
        rather than being two of four states nobody can tell apart.
      -->
      <p class="text-ui text-content-muted">
        This device is missing your encryption keys. Enter your recovery key to read your
        earlier messages here.
      </p>
      <form
        class="flex flex-col gap-2"
        onsubmit={(e: SubmitEvent) => {
          e.preventDefault();
          void recover();
        }}
      >
        <input
          bind:value={entered}
          type="text"
          autocomplete="off"
          spellcheck="false"
          placeholder="EsT? ???? ???? ???? ???? ???? ???? ????"
          aria-label="Recovery key"
          class="rounded-control border border-border bg-surface-sunken px-3 py-2 font-mono text-ui text-content placeholder:text-content-faint"
        />
        <button
          type="submit"
          disabled={busy || entered.trim() === ""}
          class="self-start rounded-control bg-accent px-3 py-2 text-ui text-on-accent transition-colors disabled:opacity-50"
        >
          {busy ? "Restoring…" : "Restore"}
        </button>
      </form>

      <!--
        The escape hatch, and the reason this panel stopped being a dead end:
        somebody who never had a key used to be shown a field they could not
        fill and nothing else. Element offers exactly one way out of the same
        corner, and this is the same one.
      -->
      {#if resetting}
        <form
          class="flex flex-col gap-2 border-t border-border pt-4"
          onsubmit={(e: SubmitEvent) => {
            e.preventDefault();
            void reset();
          }}
        >
          <!--
            The consequence stays on screen next to the button that causes it.
            It used to be replaced by the sentence about the password, so the
            one moment a reader was deciding to destroy a backup was the one
            moment nothing on screen said a backup would be destroyed.
          -->
          <p class="text-ui text-content-muted">
            Anything backed up under the old key is lost, and your other devices will need
            verifying again. Messages already on this device stay readable.
          </p>
          <p class="text-ui text-content-muted">
            Your password confirms this with your homeserver. It is used once and not
            stored.
          </p>
          <input
            bind:value={password}
            type="password"
            autocomplete="current-password"
            aria-label="Your password"
            class="rounded-control border border-border bg-surface-sunken px-3 py-2 text-ui text-content"
          />
          <div class="flex gap-2">
            <button
              type="submit"
              disabled={busy || password === ""}
              class="self-start rounded-control bg-danger px-3 py-2 text-ui text-on-accent transition-colors disabled:opacity-50"
            >
              {busy ? "Starting over…" : "Start over"}
            </button>
            <button
              type="button"
              onclick={() => {
                resetting = false;
                password = "";
                failure = null;
              }}
              class="self-start rounded-control bg-surface-sunken px-3 py-2 text-ui text-content transition-colors hover:bg-surface-sunken/70"
            >
              Cancel
            </button>
          </div>
        </form>
      {:else}
        <p class="border-t border-border pt-4 text-ui text-content-muted">
          No recovery key? Start again with a new one. Messages already on this device stay
          readable, but anything backed up under the old key is lost, and your other
          devices will need verifying again.
        </p>
        <button
          type="button"
          onclick={() => (resetting = true)}
          class="self-start rounded-control bg-surface-sunken px-3 py-2 text-ui text-content transition-colors hover:bg-surface-sunken/70"
        >
          Start over with a new key
        </button>
      {/if}
    {:else}
      <p class="text-ui text-content-muted">
        Your messages can be recovered. They can be restored on a new device with your
        recovery key. There is no way to show it again.
      </p>
      <button
        type="button"
        onclick={onClose}
        class="self-start rounded-control bg-surface-sunken px-3 py-2 text-ui text-content transition-colors hover:bg-surface-sunken/70"
      >
        Done
      </button>
    {/if}

    {#if failure}
      <p role="alert" class="text-meta text-danger">{failure}</p>
    {/if}
  </div>
</div>
