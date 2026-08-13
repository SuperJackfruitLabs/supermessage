<script lang="ts">
  // The sidebar: rooms sorted by recency, selecting one drives the timeline
  // subscription via `roomsStore.select`.
  //
  // Each row surfaces the structure spec §5.1/§6.1 calls for: the room name
  // is parsed into glyph/name/role via `roomIdentity.ts`'s
  // `parseRoomIdentity`, the avatar fallback goes through `roomInitial`
  // (never the raw name's first character — see that module's doc comment
  // for the astral-surrogate bug this replaces), and the second line (role
  // and/or relative last activity) is built from that parse plus
  // `relativeTime`.
  //
  // The third line is the message preview (spec §6.1.1), composed from
  // `RoomSummary`'s four `last*` fields by `roomPreview.ts` — including the
  // `You: ` prefix, which is the webview's to add and only for our own
  // non-emote messages. It arrived after the two lines above it and is a
  // further line beyond them, not a replacement for either.
  //
  // **All three lines are independently omitted.** Name is always there;
  // the role/time line appears when there is a role or a time; the preview
  // appears when there is a preview. Any of the eight combinations is a
  // real row — a brand-new room has a name and nothing else, a
  // never-active room with a role has no time, and a room whose latest
  // event is a membership change has a role and a time but no preview.
  // There is no placeholder string for any of them (spec §6.1, §6.1.1), so
  // each line asks its own question rather than sharing a "show the rest of
  // the row" flag.
  //
  // Avatars: fetched via `avatarCache`, keyed by room id, for **every**
  // room — not gated on `room.avatarUrl` being set. That field only ever
  // reflects the room's own `m.room.avatar`, and is `null` for most of
  // these rooms: their "avatar" (per Element) is really the other member's
  // profile picture, which the core can only resolve by reading the room's
  // member list — async, so it happens inside the `room_avatar` command
  // rather than the synchronous room-list projection (see
  // `core::rooms::resolve_room_avatar_mxc`'s doc comment and `ipc.ts`'s
  // `RoomSummary`/`roomAvatar` doc comments). Gating the fetch on
  // `avatarUrl` here would silently skip exactly those rooms. The cache
  // still keeps the list from blocking on avatars: every row renders
  // immediately with its initials, and swaps in the real image once (and
  // if) the fetch resolves.

  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import { parseRoomIdentity, relativeTime, roomInitial } from "./roomIdentity";
  import { composeRoomPreview } from "./roomPreview";
  import { DECISION_BEARING_EVENT_TYPES } from "./customEvents";

  /**
   * `onSelect` fires after a row is chosen, whether or not that changed the
   * selection. It exists for the collapsed layout in `+page.svelte`, which
   * has to move to the room pane on *every* choice — including a re-choice
   * of the room that is already selected, which is exactly how an operator
   * returns to a room after using the back affordance.
   */
  let { onSelect }: { onSelect?: () => void } = $props();

  const avatarCache = createAvatarCache();

  /**
   * Handles a row click: select the room if it isn't already the selected
   * one, then notify.
   *
   * The guard is not a micro-optimization. `roomsStore.select` calls
   * `timelineStore.subscribeTo`, which re-arms the diff tracker to expect a
   * fresh sequence starting at 1 and re-issues `timeline_subscribe` — the
   * teardown-and-rebuild `rooms.svelte.ts`'s module doc comment spends most
   * of its length on. In the collapsed layout, returning to a room from the
   * roster is an ordinary navigation that happens constantly, so paying a
   * resubscribe for it (and the resync window it opens) would be a real
   * correctness and performance regression rather than a wasted call.
   * Choosing the already-focused room now does nothing but re-show it.
   */
  function chooseRoom(id: string): void {
    if (id !== roomsStore.selectedId) roomsStore.select(id);
    onSelect?.();
  }

  const sortedRooms = $derived(
    [...roomsStore.rooms].sort((a, b) => (b.lastActivityMs ?? 0) - (a.lastActivityMs ?? 0)),
  );

  // Recency threshold for spec §6.1's muted-vs-faint split on the "· 4m"
  // time: rooms active within the last 5 minutes render their time in
  // `--color-content-muted`, older ones in `--color-content-faint`. Recency
  // is the only honest per-row liveness signal available — per-room typing
  // isn't streamed, `typingStore` scopes to the focused room only.
  const RECENT_MS = 5 * 60_000;

  // `relativeTime` needs an instant to measure against. This is
  // deliberately *not* a ticking clock: `now` is derived from
  // `sortedRooms`, so it's recomputed exactly when the roster itself
  // re-renders — new activity, a room added or removed, an unread count
  // changing — which is the only time a row's age label could actually be
  // stale. A `setInterval` re-rendering the whole roster every
  // second/minute purely to age a label would be a battery cost for zero
  // new information. Do not add one; if a row's time looks stale, the fix
  // is confirming the roster re-renders on new activity, not a timer.
  const now = $derived.by(() => {
    void sortedRooms;
    return Date.now();
  });

  /**
   * The row's accessible name: parsed name, role, unread count, and — only
   * when a decision is pending — the fixed string `Approval needed`, in
   * that order. Set explicitly as the button's own `aria-label` rather than
   * left to the default descendant-concatenation algorithm. Two reasons:
   * the visual "·" between role and time would otherwise be read literally
   * (e.g. "middle dot") by some screen readers, and the unread badge sits
   * to the right of the name in the DOM/markup for layout reasons, which
   * would otherwise interleave it ahead of the role. The relative-time
   * label ("4m") is left out on purpose: it's supplementary and changes on
   * every re-render, not identifying information worth repeating on every
   * row for a screen reader user.
   *
   * **The message preview is deliberately not in here, and the pending
   * marker deliberately is.** The line this draws is *state, not content*
   * — the same line the unread count already sits on the right side of:
   *
   * - The preview text changes on **every message**, so putting it in the
   *   accessible name makes the row's *identity* churn. An `aria-label` is
   *   what the row *is*, and a name that is different every time the user
   *   arrows past it is a worse name; on a focused row, some assistive
   *   technologies re-announce a changed name outright, turning a busy
   *   fleet into interruptions. It would also make every row's
   *   announcement up to a hundred characters longer, which is the
   *   opposite of what roster navigation is for. The preview is
   *   supplementary detail about the room's contents, and the room's
   *   contents are what selecting the row is *for* — the timeline is where
   *   they belong, in full, with senders and timestamps attached.
   * - `Approval needed` is none of that. It is a fixed string, it does not
   *   change as messages arrive, and it is the row's most consequential
   *   state — and because an explicit `aria-label` replaces the button's
   *   whole subtree for name computation, leaving it out would make the
   *   amber row carry its meaning in colour and in replaced-away text
   *   only. That is the one thing an accessible name must not do. It is
   *   included for exactly the reason the unread count is, and it is
   *   unreachable in production today for the reasons
   *   `DECISION_BEARING_EVENT_TYPES` documents.
   */
  function rowAriaLabel(
    name: string,
    role: string | null,
    unread: number,
    pendingDecision: boolean,
  ): string {
    const parts = [name];
    if (role !== null) parts.push(role);
    if (unread > 0) parts.push(`${unread} unread`);
    if (pendingDecision) parts.push("Approval needed");
    return parts.join(", ");
  }
</script>

<nav aria-label="Rooms" class="flex h-full flex-col overflow-y-auto">
  {#if sortedRooms.length === 0}
    <p class="px-4 py-6 text-center text-ui text-content-muted">No rooms yet.</p>
  {:else}
    {#each sortedRooms as room (room.id)}
      {@const selected = room.id === roomsStore.selectedId}
      {@const avatar = avatarCache.get(room.id)}
      {@const identity = parseRoomIdentity(room.name)}
      {@const time = relativeTime(room.lastActivityMs, now)}
      {@const recent = room.lastActivityMs !== null && now - room.lastActivityMs < RECENT_MS}
      {@const preview = composeRoomPreview(room, DECISION_BEARING_EVENT_TYPES)}
      <!--
        Each of the two lines below the name asks its own question — see
        this component's doc comment on why there is no shared "show the
        rest of the row" flag any more.
      -->
      {@const showRoleTime = identity.role !== null || time !== null}
      <button
        type="button"
        onclick={() => chooseRoom(room.id)}
        aria-current={selected ? "true" : undefined}
        aria-label={rowAriaLabel(
          identity.name,
          identity.role,
          room.unread,
          preview?.pending ?? false,
        )}
        class="flex gap-3 border-l-2 pr-4 pl-[10px] text-left transition-colors {selected
          ? 'border-l-accent bg-surface'
          : preview?.pending
            ? 'border-l-signal hover:bg-surface/60'
            : 'border-l-transparent hover:bg-surface/60'}"
      >
        {#if avatar}
          <img
            src={avatar}
            alt=""
            aria-hidden="true"
            class="h-8 w-8 shrink-0 self-center rounded-full object-cover"
            onerror={() => avatarCache.markFailed(room.id)}
          />
        {:else}
          <span
            class="flex h-8 w-8 shrink-0 self-center items-center justify-center rounded-full bg-surface-raised text-ui font-medium text-content"
            aria-hidden="true"
          >
            {roomInitial(identity)}
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
            <span class="truncate text-ui font-medium text-content">{identity.name}</span>
            {#if room.unread > 0}
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
                class="shrink-0 rounded-full bg-accent px-1.5 py-0.5 font-mono text-meta text-accent-content"
              >
                {room.unread}
              </span>
            {/if}
          </span>
          {#if showRoleTime}
            <span class="mt-0.5 flex min-w-0 items-baseline gap-1 font-mono text-meta text-content-muted">
              {#if identity.role !== null}
                <span class="truncate text-label uppercase">{identity.role}</span>
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
              (see `DECISION_BEARING_EVENT_TYPES`).

              `truncate` rather than a hard-cut string: the core bounds the
              text at 100 code points for transport, CSS owns what the
              reader actually sees, exactly as every other roster string
              here does.
            -->
            <span
              class="mt-0.5 block truncate font-mono text-meta {preview.pending
                ? 'text-signal'
                : room.unread > 0
                  ? 'text-content-muted'
                  : 'text-content-faint'}">{preview.text}</span
            >
          {/if}
        </span>
      </button>
    {/each}
  {/if}
</nav>
