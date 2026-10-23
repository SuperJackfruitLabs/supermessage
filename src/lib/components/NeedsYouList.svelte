<script lang="ts">
  import type { NeedsYouInbox } from "$lib/ipc";
  import CaughtUp from "./CaughtUp.svelte";
  import { animateList } from "./animateList";
  import { inboxSections, isCaughtUp } from "./inboxView";
  import RoomRow from "./roster/RoomRow.svelte";

  /**
   * The Needs you inbox (iOS `NeedsYouView`): decisions, then invitations, each section only when
   * it has rows, in the roster's own two-line row. Nothing waiting is the caught-up state. `null`
   * is "not read yet", which draws nothing rather than a false "all caught up".
   */
  export interface Props {
    inbox: NeedsYouInbox | null;
    nowMs: number;
    showsState: boolean;
    avatars: { get(roomId: string): string | null; markFailed(roomId: string): void };
    selectedId?: string | null;
    onSelect: (roomId: string) => void;
    onOpenInfo?: (roomId: string) => void;
  }

  let { inbox, nowMs, showsState, avatars, selectedId = null, onSelect, onOpenInfo }: Props = $props();

  const sections = $derived(inbox === null ? [] : inboxSections(inbox));
</script>

<div class="flex h-full flex-col overflow-y-auto" data-testid="needs-you-list">
  {#if inbox !== null && isCaughtUp(inbox)}
    <div class="flex flex-1 items-center justify-center"><CaughtUp /></div>
  {:else}
    <div use:animateList class="flex flex-col">
      {#each sections as section (section.id)}
        <h3 class="flex items-baseline gap-1.5 px-4 pt-3 pb-1 text-meta">
          <span class="min-w-0 truncate text-content-muted">{section.title}</span>
          <span class="shrink-0 tabular-nums text-content-faint" data-testid="needs-you-count-{section.id}"
            >{section.count}</span
          >
        </h3>
        {#each section.rows as entry (entry.row.room.id)}
          <RoomRow
            row={entry.row}
            state={entry.state}
            describesAgent={entry.describesAgent}
            avatar={entry.avatar ?? null}
            {showsState}
            selected={entry.row.room.id === selectedId}
            avatarUrl={avatars.get(entry.row.room.id)}
            now={nowMs}
            {onSelect}
            onAvatarFailed={(roomId) => avatars.markFailed(roomId)}
            onMenu={onOpenInfo ? (roomId) => onOpenInfo(roomId) : undefined}
          />
        {/each}
      {/each}
    </div>
  {/if}
</div>
