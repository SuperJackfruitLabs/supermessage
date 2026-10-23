<script lang="ts">
  import type { RoomStatus } from "$lib/ipc";
  import StatusPill from "./StatusPill.svelte";
  import { memberCountLabel } from "./roomHeaderView";

  /**
   * The header's second line under the room's name (iOS `RoomScreen.swift:194-220`):
   * the status pill for an agent's room, else "N members" for a room of people.
   * Nothing at all when it has neither — the line does not hold a blank row open.
   */
  export interface Props {
    status: RoomStatus | null;
    word: string | null;
    members: number | null;
  }

  let { status, word, members }: Props = $props();
  const count = $derived(memberCountLabel(members));
</script>

{#if status !== null && word !== null}
  <div class="flex min-w-0"><StatusPill {status} {word} /></div>
{:else if count !== null}
  <p class="truncate text-meta text-content-muted" data-testid="member-count">{count}</p>
{/if}
