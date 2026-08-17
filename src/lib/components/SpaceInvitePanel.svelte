<script lang="ts">
  // Accepting an invitation to a space.
  //
  // A room's invitation is answered in the room pane, where the composer
  // would be, because the question "what can I do here?" is about the room in
  // front of you. A space has no room pane and never will — it is a
  // navigation surface, not a conversation — so its invitation is answered
  // here, in a dialog raised from the rail entry that carries it.
  //
  // It names the space, and that is most of why it exists rather than two
  // buttons wedged into a 56px strip: AgentPod invites the operator to one
  // space per node, so they arrive in pairs and "Accept" alone would not say
  // which one is being accepted.
  //
  // Nothing here is optimistic. Accepting joins on the homeserver, the rail
  // is re-read from the core (`rooms.svelte.ts::acceptInvitation`), and the
  // entry becomes an ordinary space because the core now reports it as
  // joined. Declining goes the same way through leave. The dialog closes only
  // once the call has come back, so a failure has somewhere to be shown.

  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { invitationPrompt } from "./invitationView";
  import { parseRoomIdentity } from "./roomIdentity";

  let {
    spaceId,
    spaceName,
    onClose,
  }: { spaceId: string; spaceName: string; onClose: () => void } = $props();

  /** Set while a join/leave is in flight, so neither button can be pressed twice. */
  let busy = $state(false);
  let failure = $state<string | null>(null);

  // The parsed name, like everywhere else a room name is shown: a space can
  // carry the same `glyph Name — Role` structure a room can, and the raw
  // string would put the glyph in the middle of a sentence.
  const label = $derived(parseRoomIdentity(spaceName).name);

  function focusOnMount(node: HTMLButtonElement) {
    node.focus();
  }

  async function respond(action: "accept" | "decline"): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      if (action === "accept") await roomsStore.acceptInvitation(spaceId);
      else await roomsStore.declineInvitation(spaceId);
      onClose();
    } catch (err) {
      failure =
        action === "accept"
          ? `Could not accept: ${err instanceof Error ? err.message : String(err)}`
          : `Could not decline: ${err instanceof Error ? err.message : String(err)}`;
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
    aria-label="Invitation to a space"
    class="relative z-10 flex w-full max-w-md flex-col gap-3 rounded-lg border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    }}
  >
    <p class="text-ui text-content">{invitationPrompt(label)}</p>
    <!--
      What a space *is*, said plainly. The rail is new enough that "space" is
      not yet a word this app has taught anyone, and an operator deciding
      whether to accept deserves to know it groups rooms rather than being
      another room.
    -->
    <p class="text-meta text-content-muted">
      A space groups rooms. Accepting adds it to the rail, where it filters the
      roster to the rooms inside it.
    </p>
    <div class="mt-1 flex gap-2">
      <button
        use:focusOnMount
        type="button"
        disabled={busy}
        onclick={() => void respond("accept")}
        class="rounded-md bg-accent px-3 py-1.5 text-ui font-medium text-accent-content transition-colors hover:opacity-90 disabled:opacity-50"
      >
        Accept
      </button>
      <button
        type="button"
        disabled={busy}
        onclick={() => void respond("decline")}
        class="rounded-md border border-border px-3 py-1.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface/60 hover:text-content disabled:opacity-50"
      >
        Decline
      </button>
    </div>
    {#if failure !== null}
      <p role="alert" class="text-meta text-content-muted">{failure}</p>
    {/if}
  </div>
</div>
