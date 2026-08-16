<script lang="ts">
  // Starting a conversation, rather than waiting to be invited to one.
  //
  // Two things behind one control, because they are the same intent from the
  // operator's side — "I want to be in a room I am not in yet" — and splitting
  // them into two entry points would make the reader classify their own
  // situation before they can act.
  //
  // The checking lives in `roomCreation.ts`: a Matrix id and a room alias have
  // exact shapes, and a request built from a typo comes back as an opaque
  // homeserver error rather than "that is not a user id".

  import { createRoom, joinRoomByAlias } from "$lib/ipc";
  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { spacesStore } from "$lib/stores/spaces.svelte";
  import {
    creationProblem,
    isRoomTarget,
    parseInvitees,
    shouldBeDirect,
  } from "./roomCreation";

  let { onClose }: { onClose: () => void } = $props();

  let mode = $state<"create" | "join">("create");
  let name = $state("");
  let inviteText = $state("");
  let target = $state("");
  let busy = $state(false);
  let failure = $state<string | null>(null);

  const invitees = $derived(parseInvitees(inviteText));
  const problem = $derived(creationProblem(name, invitees));

  function focusOnMount(node: HTMLInputElement) {
    node.focus();
  }

  /**
   * Opens the room that was just made or joined, and closes.
   *
   * The rail is re-read too: joining by alias is one of the ways a space
   * arrives, and a space that appeared while this panel was open would
   * otherwise be invisible until the next launch.
   */
  async function finish(roomId: string): Promise<void> {
    await spacesStore.load().catch(() => {});
    roomsStore.select(roomId);
    onClose();
  }

  async function submit(): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      if (mode === "create") {
        if (problem !== null) {
          failure = problem.message;
          return;
        }
        await finish(await createRoom(name, invitees, shouldBeDirect(invitees)));
      } else {
        if (!isRoomTarget(target)) {
          failure = "That is not a room. They look like #missions:id.agentpod.dev.";
          return;
        }
        await finish(await joinRoomByAlias(target));
      }
    } catch (err) {
      failure = err instanceof Error ? err.message : String(err);
    } finally {
      busy = false;
    }
  }
</script>

<div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button
    type="button"
    aria-label="Close"
    class="absolute inset-0 bg-black/40"
    onclick={onClose}
  ></button>

  <div
    role="dialog"
    tabindex="-1"
    aria-label="Start a conversation"
    class="relative z-10 flex w-full max-w-md flex-col gap-3 rounded-lg border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    }}
  >
    <div class="flex gap-1" role="tablist" aria-label="Create or join">
      {#each [["create", "New room"], ["join", "Join by address"]] as [value, label] (value)}
        <button
          type="button"
          role="tab"
          aria-selected={mode === value}
          onclick={() => {
            mode = value as "create" | "join";
            failure = null;
          }}
          class="rounded-md px-3 py-1.5 text-ui font-medium transition-colors {mode === value
            ? 'bg-surface-sunken text-content'
            : 'text-content-muted hover:bg-surface-sunken/60'}"
        >
          {label}
        </button>
      {/each}
    </div>

    <form
      class="flex flex-col gap-3"
      onsubmit={(e: SubmitEvent) => {
        e.preventDefault();
        void submit();
      }}
    >
      {#if mode === "create"}
        <label class="flex flex-col gap-1">
          <span class="text-ui text-content-muted">Name</span>
          <input
            use:focusOnMount
            bind:value={name}
            type="text"
            placeholder="Q4 rollout"
            class="rounded-md border border-border bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none"
          />
        </label>
        <label class="flex flex-col gap-1">
          <span class="text-ui text-content-muted">Invite</span>
          <input
            bind:value={inviteText}
            type="text"
            placeholder="@agent_echo:id.agentpod.dev, @ana:id.agentpod.dev"
            class="rounded-md border border-border bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none"
          />
          <!--
            Said before it happens, not discovered afterwards: which half of a
            client's list this lands in is decided at creation and cannot be
            changed later.
          -->
          <span class="font-mono text-meta text-content-faint">
            {invitees.length === 1
              ? "One person — this will be a direct message."
              : "Two or more — this will be a room."}
          </span>
        </label>
      {:else}
        <label class="flex flex-col gap-1">
          <span class="text-ui text-content-muted">Room address</span>
          <input
            use:focusOnMount
            bind:value={target}
            type="text"
            placeholder="#agentpod_missions:id.agentpod.dev"
            class="rounded-md border border-border bg-surface-sunken px-3 py-2 font-mono text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none"
          />
        </label>
      {/if}

      {#if failure !== null}
        <p role="alert" class="text-ui text-destructive">{failure}</p>
      {/if}

      <div class="flex justify-end gap-2">
        <button
          type="button"
          onclick={onClose}
          class="rounded-md border border-border px-3 py-1.5 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={busy}
          class="rounded-md bg-accent px-3 py-1.5 text-ui font-medium text-accent-content transition-opacity hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Working…" : mode === "create" ? "Create" : "Join"}
        </button>
      </div>
    </form>
  </div>
</div>
