<script lang="ts">
  import type { AgentAvatarStyle, AgentState, RoomRow } from "$lib/ipc";
  import { rowTime } from "../roomIdentity";
  import RoomAvatar from "./RoomAvatar.svelte";
  import { avatarLabel, drawnState, unreadLabel } from "./avatarView";

  /**
   * One roster row.
   *
   * The row arrives with its name already split, its preview already
   * composed and its affordance already chosen — `core::roster` decided all
   * three, so iOS and this app cannot disagree about any of them. Only the
   * age label is derived here, because it reads a clock.
   *
   * `avatarUrl` is a resolved value rather than a cache. The container holds
   * the `createAvatarCache()` and calls it for **every** row, not only those
   * whose `avatarUrl` is set, because that field is only populated in some
   * cases and gating on it would silently skip exactly the rooms that need
   * resolving.
   */
  export interface Props {
    row: RoomRow;
    /** The agent state `core::roster` put on this row. */
    state: AgentState;
    /**
     * Whether `state`'s activity word describes anything — carried on the
     * row by `core::roster`. False for a room of people, whose dot and
     * activity word are then left out; `needsYou` shows regardless.
     */
    describesAgent: boolean;
    /** The core's generated tile for an agent's room (`RosterRow.avatar`). */
    avatar?: AgentAvatarStyle | null;
    /** Whether the state dot is wanted at all; a pending decision is only drawn when it is. */
    showsState?: boolean;
    selected: boolean;
    avatarUrl: string | null;
    /**
     * The instant the age label measures against.
     *
     * Passed in, and deliberately not a ticking clock: the container derives
     * it from the roster itself, so it is recomputed exactly when a row's
     * age could actually be stale. A timer re-rendering the whole roster to
     * age a label would be a battery cost for no new information.
     */
    now: number;
    onSelect: (roomId: string) => void;
    /** An avatar that failed to load, so the container can stop retrying. */
    onAvatarFailed: (roomId: string) => void;
    /**
     * Asks for the row's menu, at a point: the pointer for a right-click, the row's bottom-left
     * for Shift+F10 or the ContextMenu key. Rows are drawn the same without it.
     */
    onMenu?: (roomId: string, at: { x: number; y: number }) => void;
  }

  let {
    row,
    state,
    describesAgent,
    avatar = null,
    showsState = true,
    selected,
    avatarUrl,
    now,
    onSelect,
    onAvatarFailed,
    onMenu,
  }: Props = $props();

  function onContextMenu(e: MouseEvent): void {
    if (!onMenu) return;
    e.preventDefault();
    onMenu(row.room.id, { x: e.clientX, y: e.clientY });
  }

  function onKeydown(e: KeyboardEvent): void {
    if (!onMenu) return;
    if (e.key !== "ContextMenu" && !(e.key === "F10" && e.shiftKey)) return;
    e.preventDefault();
    const box = (e.currentTarget as HTMLElement).getBoundingClientRect();
    onMenu(row.room.id, { x: box.left, y: box.bottom });
  }

  const room = $derived(row.room);
  const identity = $derived(row.identity);
  const preview = $derived(row.preview);
  const time = $derived(rowTime(room.lastActivityMs, now, row.affordance === "respondToInvitation"));
  /**
   * Each of the two lines below the name asks its own question — there is
   * deliberately no shared "show the rest of the row" flag.
   */
  const invited = $derived(row.affordance === "respondToInvitation");
  /** The state the avatar's dot draws, or `null` for none (iOS `drawsState`). */
  const shown = $derived(drawnState(state, describesAgent, showsState));
  /** Line 2: the preview, else the role it has said nothing under, else a blank line. */
  const secondLine = $derived(preview !== null ? preview.text : (identity.role ?? ""));

  /**
   * The row's accessible name: name, role, unread count, and — only when a decision is pending —
   * the fixed string `Approval needed`, then what the avatar says ("Agent", its state word).
   *
   * The avatar is a part of the button, whose explicit `aria-label` replaces the subtree for name
   * computation, so the avatar's text has to be said here or the state exists in colour only.
   * `needsYou` is already carried by "Approval needed" when a decision is pending.
   */
  function rowAriaLabel(
    name: string,
    role: string | null,
    unread: number,
    pendingDecision: boolean,
    isInvited: boolean,
    isAgent: boolean,
    value: AgentState | null,
  ): string {
    const parts = [name];
    // Right after the name, because it changes what the row *is*: an invitation is not a
    // conversation yet, and a reader who learns that last has already formed the wrong idea.
    if (isInvited) parts.push("Invitation");
    if (role !== null) parts.push(role);
    if (unread > 0) parts.push(`${unread} unread`);
    if (pendingDecision) parts.push("Approval needed");
    const face = avatarLabel(isAgent, pendingDecision && value === "needsYou" ? null : value);
    if (face !== "") parts.push(face);
    return parts.join(", ");
  }
</script>

<button
  type="button"
  onclick={() => onSelect(room.id)}
  oncontextmenu={onContextMenu}
  onkeydown={onKeydown}
  aria-current={selected ? "true" : undefined}
  aria-label={rowAriaLabel(
    identity.name,
    identity.role,
    room.unread,
    preview?.pending ?? false,
    invited,
    describesAgent,
    shown,
  )}
  class="flex gap-3 border-l-2 pr-4 pl-[10px] text-left transition-colors {selected
    ? 'border-l-accent bg-surface'
    : preview?.pending
      ? 'border-l-signal hover:bg-surface/60'
      : 'border-l-transparent hover:bg-surface/60'}"
>
  <span class="self-center py-2">
    <RoomAvatar
      initial={identity.initial}
      {avatarUrl}
      {describesAgent}
      style={avatar}
      state={shown}
      onAvatarFailed={() => onAvatarFailed(room.id)}
    />
  </span>
  <!--
    The row separator lives on this column, not the button: the button's flex row stretches
    its children, and this column is the tallest sibling, so its bottom edge already coincides
    with the row's. Anchoring the hairline here keeps it inset to clear the avatar column
    (spec §6.1) instead of running edge-to-edge under the avatar too.

    Two lines and only two (iOS `RoomRowView`): who, and what they last said. The state is the
    dot on the avatar and the runtime lives in room info (R1) — the role-and-time line that sat
    between them was metadata competing with the conversation.
  -->
  <span class="flex min-w-0 flex-1 flex-col justify-center gap-0.5 border-b border-border py-3">
    <span class="flex items-baseline gap-1.5">
      <span class="min-w-0 flex-1 truncate text-ui font-medium text-content">{identity.name}</span>
      {#if invited}
        <!--
          Outlined rather than filled: an invitation is a state, not a count, and the filled
          accent pill is the unread number's.
        -->
        <span
          class="shrink-0 rounded-pill border border-accent px-[7px] py-px text-meta text-accent"
        >
          Invitation
        </span>
      {/if}
      {#if time !== null}
        <span class="shrink-0 text-meta tabular-nums {room.unread > 0 ? 'text-accent' : 'text-content-faint'}"
          >{time}</span
        >
      {/if}
    </span>
    <span class="flex items-center gap-1.5">
      <!--
        The preview, `signal` when it is a decision awaiting an answer (the only place amber
        appears in the roster), else `content-muted`; the role in `content-faint` when the room
        has said nothing; a blank line keeps every row the same height. Sans: a preview is a
        fragment of something a person or an agent wrote.
      -->
      <span
        class="min-w-0 flex-1 truncate font-sans text-meta {preview !== null
          ? preview.pending
            ? 'text-signal'
            : 'text-content-muted'
          : 'text-content-faint'}"
        data-testid="row-second-line">{secondLine === "" ? "\u00A0" : secondLine}</span
      >
      {#if room.unread > 0}
        <!--
          No `aria-label` of its own: the button's explicit label already carries the count, and
          a second reading of the same number would be inert where it looks useful.
        -->
        <span
          class="shrink-0 rounded-pill bg-accent px-1.5 py-0.5 text-meta tabular-nums text-accent-content"
        >
          {unreadLabel(room.unread)}
        </span>
      {/if}
    </span>
  </span>
</button>
