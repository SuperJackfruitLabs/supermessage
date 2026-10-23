<script lang="ts">
  import type { AgentAvatarStyle, AgentState } from "$lib/ipc";
  import { avatarLabel, avatarRoleClass, dotClass } from "./avatarView";

  /**
   * A room's face (iOS `Rooms/RoomAvatar.swift`): its picture, or a generated tile, with the
   * agent's state as a dot on its corner.
   *
   * An agent is a rounded square in the core's tile colours (`style`, from `agentAvatarStyle`
   * or the roster row) and wears a small badge, so the difference from a person does not rest
   * on shape alone; a person or a room of people is a circle. A picture fills either shape.
   * Quiet draws no dot. The accessible text is "Agent" and the state word, joined by ", ".
   */
  export interface Props {
    initial: string;
    avatarUrl: string | null;
    describesAgent: boolean;
    /** The core's tile for an agent's room; `null` falls back to the people's look. */
    style: AgentAvatarStyle | null;
    /** The state dot, or `null` for none. */
    state: AgentState | null;
    size?: number;
    onAvatarFailed?: () => void;
  }

  let { initial, avatarUrl, describesAgent, style, state, size = 40, onAvatarFailed }: Props = $props();

  const agentTile = $derived(describesAgent && style !== null ? style : null);
  const dot = $derived(dotClass(state));
  const label = $derived(avatarLabel(describesAgent, state));
  const raisedGround = $derived(agentTile === null || agentTile.ground === "surfaceRaised");
</script>

<span
  class="relative inline-block shrink-0"
  style:width="{size}px"
  style:height="{size}px"
  role={label === "" ? undefined : "img"}
  aria-label={label === "" ? undefined : label}
  aria-hidden={label === "" ? "true" : undefined}
  data-testid="room-avatar"
>
  {#if avatarUrl}
    <img
      src={avatarUrl}
      alt=""
      aria-hidden="true"
      class="h-full w-full object-cover {describesAgent ? '' : 'rounded-pill'}"
      style:border-radius={describesAgent ? `${size * 0.28}px` : undefined}
      onerror={() => onAvatarFailed?.()}
    />
  {:else if agentTile}
    <span
      class="flex h-full w-full items-center justify-center font-semibold {avatarRoleClass(agentTile.ground, 'bg')} {avatarRoleClass(agentTile.ink, 'text')} {raisedGround ? 'border border-border' : ''}"
      style:border-radius="{size * 0.28}px"
      style:font-size="{size * 0.44}px"
      aria-hidden="true"
      data-variant={agentTile.variant}
    >
      {initial}
    </span>
  {:else}
    <span
      class="flex h-full w-full items-center justify-center rounded-pill border border-border bg-surface-raised font-medium text-content-muted"
      style:font-size="{size * 0.42}px"
      aria-hidden="true"
    >
      {initial}
    </span>
  {/if}
  {#if describesAgent}
    <span
      class="absolute flex items-center justify-center rounded-pill bg-accent text-accent-content ring-[1.5px] ring-surface"
      style:top="-3px"
      style:right="-3px"
      style:width="{size * 0.36}px"
      style:height="{size * 0.36}px"
      aria-hidden="true"
      data-testid="agent-badge"
    >
      <svg viewBox="0 0 24 24" width="60%" height="60%" fill="currentColor" aria-hidden="true">
        <path d="M12 1.5c.7 5.6 2.9 8.8 10.5 10.5-7.6 1.7-9.8 4.9-10.5 10.5C11.3 16.9 9.1 13.7 1.5 12 9.1 10.3 11.3 7.1 12 1.5Z" />
      </svg>
    </span>
  {/if}
  {#if dot !== null}
    <span
      class="absolute rounded-pill {dot} ring-2 ring-surface"
      style:bottom="-2px"
      style:right="-2px"
      style:width="{size * 0.3}px"
      style:height="{size * 0.3}px"
      aria-hidden="true"
      data-testid="state-dot"
    ></span>
  {/if}
</span>
