<script lang="ts">
  import type { RosterRow } from "$lib/ipc";
  import ContentUnavailable from "./layout/ContentUnavailable.svelte";
  import { relativeTime } from "./roomIdentity";
  import { animateList } from "./animateList";
  import RoomAvatar from "./roster/RoomAvatar.svelte";
  import { drawnState } from "./roster/avatarView";
  import StatusPill from "./StatusPill.svelte";
  import type { AgentStatus } from "./agentsView";

  /**
   * The Agents directory (iOS `AgentsView`): every room with an agent in it, most recent first,
   * with what each is doing. The order, and which rooms count, are the core's
   * (`roster_agent_directory`); the status is `AgentStatus` per room, from `createAgentStatuses`.
   */
  export interface Props {
    agents: RosterRow[];
    nowMs: number;
    statuses: { statusOf(roomId: string): AgentStatus | null };
    avatars: { get(roomId: string): string | null; markFailed(roomId: string): void };
    selectedId?: string | null;
    onSelect: (roomId: string) => void;
  }

  let { agents, nowMs, statuses, avatars, selectedId = null, onSelect }: Props = $props();
</script>

<div class="flex h-full flex-col overflow-y-auto" data-testid="agents-list">
  {#if agents.length === 0}
    <div class="flex flex-1 items-center justify-center">
      <ContentUnavailable
        testid="no-agents"
        title="No agents yet"
        description="Rooms with an agent in them appear here."
      >
        {#snippet glyph()}
          <svg viewBox="0 0 24 24" width="40" height="40" fill="currentColor" class="text-content-faint">
            <path d="M12 1.5c.7 5.6 2.9 8.8 10.5 10.5-7.6 1.7-9.8 4.9-10.5 10.5C11.3 16.9 9.1 13.7 1.5 12 9.1 10.3 11.3 7.1 12 1.5Z" />
          </svg>
        {/snippet}
      </ContentUnavailable>
    </div>
  {:else}
    <div use:animateList class="flex flex-col">
      {#each agents as entry (entry.row.room.id)}
        {@const id = entry.row.room.id}
        {@const identity = entry.row.identity}
        {@const status = statuses.statusOf(id)}
        {@const time = relativeTime(entry.row.room.lastActivityMs, nowMs)}
        <button
          type="button"
          data-testid="agent-row"
          onclick={() => onSelect(id)}
          aria-current={id === selectedId ? "true" : undefined}
          aria-label={[identity.name, identity.role, status?.word].filter(Boolean).join(", ")}
          class="flex items-center gap-3 border-l-2 py-1 pr-4 pl-[10px] text-left transition-colors {id === selectedId
            ? 'border-l-accent bg-surface'
            : 'border-l-transparent hover:bg-surface/60'}"
        >
          <span class="py-2">
            <RoomAvatar
              initial={identity.initial}
              avatarUrl={avatars.get(id)}
              describesAgent={entry.describesAgent}
              style={entry.avatar ?? null}
              state={drawnState(entry.state, entry.describesAgent, true)}
              size={44}
              onAvatarFailed={() => avatars.markFailed(id)}
            />
          </span>
          <span class="flex min-w-0 flex-1 flex-col gap-0.5">
            <span class="flex items-baseline gap-1.5">
              <span class="min-w-0 flex-1 truncate text-ui font-medium text-content">{identity.name}</span>
              {#if time !== null}
                <span class="shrink-0 text-meta tabular-nums text-content-faint">{time}</span>
              {/if}
            </span>
            <span class="flex items-center gap-1.5">
              {#if identity.role}
                <span class="min-w-0 truncate text-ui text-content-muted">{identity.role}</span>
              {/if}
              {#if status}
                <StatusPill status={status.status} word={status.word} compact />
              {/if}
            </span>
          </span>
        </button>
      {/each}
    </div>
  {/if}
</div>
