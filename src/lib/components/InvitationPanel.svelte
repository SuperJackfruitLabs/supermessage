<script lang="ts">
  // Accept or decline, in the place the composer would be.
  //
  // Issue #1: AgentPod provisions one Matrix room per station and invites the
  // operator to each, so every agent room arrives as an invitation. The roster
  // listed them (the core filters with `new_filter_non_left`) and nothing could
  // act on them — the client built for rooms whose other occupants are agents
  // could not enter one.
  //
  // It sits where the composer sits, and replaces it, because the two are the
  // same question — "what can I do in this room?" — with different answers.
  // Showing a composer for a room this account has not joined would take a
  // message and lose it at the homeserver.
  //
  // Nothing here is optimistic. Accepting does not mark the room joined; the
  // homeserver does, the room-list stream reports it, and this panel gives way
  // to the composer on the next diff (see `rooms.svelte.ts::acceptInvitation`).
  // Until then the buttons stay disabled rather than the panel disappearing —
  // an invitation that vanished before the join landed, and came back if it
  // failed, would be worse than one that waits.

  import { onMount } from "svelte";
  import { errorMessage } from "$lib/errorMessage";
  import { report, type ReportResult } from "$lib/safety";
  import { safetyStore } from "$lib/stores/safety.svelte";
  import { invitationPrompt, inviterLine } from "./invitationView";
  import ReportSheet from "./safety/ReportSheet.svelte";

  /**
   * The invitation, and what to do about it.
   *
   * `roomsStore.acceptInvitation` / `declineInvitation` become callbacks.
   * Everything this component decides stays here: the in-flight guard so
   * neither button can be pressed twice, and the failure message — a
   * refused join is exactly the case where silence is worst, because the
   * invitation stays on screen either way.
   */
  export interface Props {
    roomId: string;
    roomName: string;
    onAccept: () => Promise<void>;
    onDecline: () => Promise<void>;
    /** Who invited, when the core knows. A failure shows no line. */
    loadInviter: () => Promise<string | null>;
    /** Sends the report of this invitation's room; defaults to the core's. */
    onReport?: (reason: string, alsoBlock: boolean) => Promise<ReportResult>;
  }

  let { roomId, roomName, onAccept, onDecline, loadInviter, onReport }: Props = $props();

  let inviter = $state<string | null>(null);
  let reporting = $state(false);

  onMount(() => {
    let live = true;
    loadInviter()
      .then((who) => {
        if (live) inviter = who;
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  });

  /** Set while a join/leave is in flight, so neither button can be pressed twice. */
  let busy = $state(false);
  /**
   * What went wrong, shown in place of nothing.
   *
   * A refused join is exactly the case where silence is worst: the invitation
   * stays on screen either way, so without this the operator sees a button
   * that does nothing.
   */
  let failure = $state<string | null>(null);

  async function respond(action: "accept" | "decline"): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      if (action === "accept") await onAccept();
      else await onDecline();
    } catch (err) {
      failure =
        action === "accept"
          ? `Could not accept: ${errorMessage(err)}`
          : `Could not decline: ${errorMessage(err)}`;
    } finally {
      busy = false;
    }
  }
</script>

<div class="border-t border-border px-4 py-3">
  <p class="text-ui text-content">{invitationPrompt(roomName)}</p>
  {#if inviterLine(inviter) !== null}
    <p class="text-meta text-content-muted">{inviterLine(inviter)}</p>
  {/if}
  <div class="mt-3 flex gap-2">
    <button
      type="button"
      disabled={busy}
      onclick={() => void respond("accept")}
      class="rounded-control bg-accent px-3 py-1.5 text-ui font-medium text-accent-content transition-colors hover:opacity-90 disabled:opacity-50"
    >
      Accept
    </button>
    <button
      type="button"
      disabled={busy}
      onclick={() => void respond("decline")}
      class="rounded-control border border-border px-3 py-1.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface/60 hover:text-content disabled:opacity-50"
    >
      Decline
    </button>
  </div>
  {#if failure !== null}
    <p role="alert" class="mt-2 text-meta text-danger">{failure}</p>
  {/if}
  <button
    type="button"
    disabled={busy}
    onclick={() => (reporting = true)}
    title="Reports this invitation to your homeserver's administrators"
    class="mt-3 text-meta text-danger transition-colors hover:underline disabled:opacity-50"
  >
    Report room…
  </button>
  {#if reporting}
    <ReportSheet
      subject={{ kind: "room", roomId, name: roomName }}
      isBlocked={(userId) => safetyStore.isBlocked(userId)}
      onSend={onReport ?? ((reason) => report({ kind: "room", roomId, name: roomName }, reason, false))}
      onClose={() => (reporting = false)}
    />
  {/if}
</div>
