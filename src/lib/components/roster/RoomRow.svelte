<script lang="ts">
  import type { AgentState, RoomRow } from "$lib/ipc";
  import { relativeTime } from "../roomIdentity";
  import { shownState } from "./rosterState";

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
  }

  let { row, state, describesAgent, selected, avatarUrl, now, onSelect, onAvatarFailed }: Props =
    $props();

  // Rooms active within the last 5 minutes render their time in
  // `--color-content-muted`, older ones in `--color-content-faint`. Recency
  // is the only honest per-row liveness signal available — per-room typing
  // is not streamed, and `typingStore` scopes to the focused room only.
  const RECENT_MS = 5 * 60_000;

  const room = $derived(row.room);
  const identity = $derived(row.identity);
  const preview = $derived(row.preview);
  const time = $derived(relativeTime(room.lastActivityMs, now));
  const recent = $derived(room.lastActivityMs !== null && now - room.lastActivityMs < RECENT_MS);
  /**
   * Each of the two lines below the name asks its own question — there is
   * deliberately no shared "show the rest of the row" flag.
   */
  const showRoleTime = $derived(identity.role !== null || time !== null);
  const invited = $derived(row.affordance === "respondToInvitation");
  /** The state this row may say, or `null` when it says none. */
  const shown = $derived(shownState(state, describesAgent));

  /**
   * The dot's colour, in the vocabulary the console reserves: amber for what
   * is owed, accent for what is alive, a faint mark for what is merely quiet,
   * and nothing at all for silence — absence is not a state worth a mark.
   */
  function stateClass(value: AgentState | null): string {
    switch (value) {
      case null:
        return "bg-transparent";
      case "needsYou":
        return "bg-signal";
      case "active":
        return "bg-accent";
      case "idle":
        return "bg-content-faint";
      case "quiet":
        return "bg-transparent";
    }
  }

  /** The word the accessible name uses for a state the dot shows in colour. */
  function stateWord(value: AgentState): string {
    switch (value) {
      case "needsYou":
        return "needs you";
      case "active":
        return "active";
      case "idle":
        return "idle";
      case "quiet":
        return "quiet";
    }
  }

  /**
   * The row's accessible name: parsed name, role, unread count, and — only
   * when a decision is pending — the fixed string `Approval needed`.
   *
   * The dot is `aria-hidden` and an explicit `aria-label` replaces the
   * button's whole subtree for name computation, so without `stateWord` the
   * state would exist in colour only. That is the one thing an accessible
   * name must not do. `needsYou` is already carried by "Approval needed",
   * and saying it twice is noise.
   */
  function rowAriaLabel(
    name: string,
    role: string | null,
    unread: number,
    pendingDecision: boolean,
    isInvited: boolean,
    value: AgentState | null,
  ): string {
    const parts = [name];
    // Right after the name, because it changes what the row *is*: an
    // invitation is not a conversation yet, and a reader who learns that
    // last has already formed the wrong idea of the row.
    if (isInvited) parts.push("Invitation");
    if (role !== null) parts.push(role);
    if (unread > 0) parts.push(`${unread} unread`);
    if (pendingDecision) parts.push("Approval needed");
    // Nothing for a room the activity word does not describe (`null`).
    if (value !== null && value !== "needsYou") parts.push(stateWord(value));
    return parts.join(", ");
  }
</script>

<button
  type="button"
  onclick={() => onSelect(room.id)}
  aria-current={selected ? "true" : undefined}
  aria-label={rowAriaLabel(
    identity.name,
    identity.role,
    room.unread,
    preview?.pending ?? false,
    invited,
    shown,
  )}
  class="flex gap-3 border-l-2 pr-4 pl-[10px] text-left transition-colors {selected
    ? 'border-l-accent bg-surface'
    : preview?.pending
      ? 'border-l-signal hover:bg-surface/60'
      : 'border-l-transparent hover:bg-surface/60'}"
>
  {#if avatarUrl}
    <img
      src={avatarUrl}
      alt=""
      aria-hidden="true"
      class="h-8 w-8 shrink-0 self-center rounded-pill object-cover"
      onerror={() => onAvatarFailed(room.id)}
    />
  {:else}
    <span
      class="flex h-8 w-8 shrink-0 self-center items-center justify-center rounded-pill bg-surface-raised text-ui font-medium text-content"
      aria-hidden="true"
    >
      {identity.initial}
    </span>
  {/if}
  <!--
    The row separator lives on this column, not the button: the
    button's flex row defaults to `align-items: stretch`, and this
    column (name + role/time, with its own `py-3`) is always the
    tallest sibling, so its own bottom edge already coincides with the
    row's. Anchoring the hairline here — rather than on the button —
    is what keeps it inset to clear the avatar column per spec §6.1
    ("Row separator: hairline, inset to clear the avatar column")
    instead of running edge-to-edge under the avatar too.
  -->
  <span class="min-w-0 flex-1 border-b border-border py-3">
    <span class="flex items-center justify-between gap-2">
      <!--
        What the roster may say this agent is doing, decided by
        `core::roster` and carried on the row. Quiet draws a
        transparent dot rather than nothing, so names stay aligned
        down the column — absence is not a state worth a mark, but it
        is not a reason to move everything either. A room of people
        (`describesAgent` false) draws the same transparent dot, for
        the same reason, unless it needs you.
      -->
      <span
        class="h-1.5 w-1.5 shrink-0 rounded-pill {stateClass(shown)}"
        aria-hidden="true"
      ></span>
      <span class="min-w-0 flex-1 truncate text-ui font-medium text-content"
        >{identity.name}</span
      >
      {#if invited}
        <!--
          An invitation reads as a room in every other respect — name,
          avatar, position in the roster — so without this the only
          honest thing about the row is what happens when you open it.
          Outlined rather than filled: it is a state, not a count, and
          the filled accent pill is the unread number's.
        -->
        <span
          class="shrink-0 rounded-pill border border-accent px-1.5 py-0.5 text-meta text-accent"
        >
          Invitation
        </span>
      {:else if room.unread > 0}
        <!--
          No `aria-label` of its own. The button above sets an explicit
          one covering name, role and unread count, and an explicit
          `aria-label` replaces its whole subtree for name computation
          — so a label here would be dead for the row's accessible
          name while still being reachable by an assistive
          technology's virtual cursor, which is the worst of both:
          inert where it looks useful, and a second, differently
          worded reading of the same number where it isn't.
        -->
        <span
          class="shrink-0 rounded-pill bg-accent px-1.5 py-0.5 text-meta tabular-nums text-accent-content"
        >
          {room.unread}
        </span>
      {/if}
    </span>
    {#if showRoleTime}
      <span class="mt-0.5 flex min-w-0 items-baseline gap-1 text-meta tabular-nums text-content-muted">
        {#if identity.role !== null}
          <span class="truncate text-label">{identity.role}</span>
        {/if}
        {#if identity.role !== null && time !== null}
          <span aria-hidden="true">·</span>
        {/if}
        {#if time !== null}
          <span class="shrink-0 {recent ? '' : 'text-content-faint'}">{time}</span>
        {/if}
      </span>
    {/if}
    {#if preview !== null}
      <!--
        The preview line (spec §6.1.1). Mono `--text-meta`, the rank
        §4's scale binds to that face — the same face and size the
        role/time line above it already uses, so the row stays two
        typographic ranks (name, then everything under it) rather
        than three. §5.3's "serif means prose" governs the reading
        surface, where a message is the thing being read; here it is
        a 30-character fragment of chrome, and a third face in a
        32px row would be noise.

        `--color-content-muted` when the room has unread,
        `--color-content-faint` otherwise: the preview is the reason
        to open an unread room, and the row that has already been
        read has nothing left to say. `--color-signal` overrides
        both on the pending path — the only place amber appears
        outside the dispatch card (spec §3), and unreachable today
        (see `core::room_preview`'s decision-bearing types).

        `truncate` rather than a hard-cut string: the core bounds the
        text at 100 code points for transport, CSS owns what the
        reader actually sees, exactly as every other roster string
        here does.

        **Sans**, like the role line above it and everything else
        said or labelled (docs/design-language.md §1: one voice, mono
        only for code, paths, ids and keys). A preview is a fragment of
        something a person or an agent wrote; rendered in mono, three
        stacked lines made a conversation read like terminal output.
      -->
      <span
        class="mt-0.5 block truncate font-sans text-meta {preview.pending
          ? 'text-signal'
          : room.unread > 0
            ? 'text-content-muted'
            : 'text-content-faint'}">{preview.text}</span
      >
    {/if}
  </span>
</button>
