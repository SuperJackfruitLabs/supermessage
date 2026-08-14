<script lang="ts">
  // The spaces rail (spaces-rail design §6): a vertical strip left of the
  // roster, scoping it to one space. "All rooms" at the top, always, then
  // one entry per joined space.
  //
  // A thin wrapper over two things that are testable without a DOM, which is
  // the pattern this codebase uses everywhere its vitest setup
  // (`environment: "node"`, no component tests) cannot reach:
  //
  // - `spacesRailView.ts`'s `railEntries` decides *what* is here, including
  //   the rule that an account with no spaces gets **no rail at all** rather
  //   than an empty strip with one inert button. An empty entry list is that
  //   rule; the `{#if}` below is the only place it becomes markup.
  // - `spaces.svelte.ts` owns the selection and the `unknownSpace` recovery.
  //   Clicking here is one `spaceSelect` command and nothing else — no
  //   resync, no tracker re-arm, no timeline resubscribe. See that store's
  //   module doc comment for why each of those would be a corruption bug
  //   rather than a wasted call.
  //
  // Icon-only, like every spaces rail: each entry is an avatar or an
  // initial. That makes the accessible name the *only* channel carrying what
  // the entry is, so it is set explicitly (`aria-label`) and mirrored into
  // `title` — an avatar alone is not a label, and a circle a reader does not
  // recognize is not one either.
  //
  // Selection is the **accent** rail plus a lifted ground, exactly matching
  // `RoomList`'s selected row: choosing a space is navigation, not a
  // decision, so it must not reach for `--color-signal` (spec §3 reserves
  // amber for a pending decision, and this is one of the places that
  // reservation is easy to break by analogy).
  //
  // No unread badges in this cut (§6): a badge on a space would have to sum
  // its subtree's unread counts and keep that sum current as rooms change.
  // Worth doing; not worth blocking the rail on.

  import { spacesStore } from "$lib/stores/spaces.svelte";
  import { createAvatarCache } from "$lib/stores/avatarCache.svelte";
  import { railEntries } from "./spacesRailView";

  // The same per-component cache the roster and the room header each keep
  // (see `avatarCache.svelte.ts`): keyed by room id, fetched lazily, and
  // called for **every** entry rather than only those whose `avatarUrl` is
  // set. A space is a room, so `room_avatar` resolves it the same way.
  const avatarCache = createAvatarCache();

  const entries = $derived(railEntries(spacesStore.spaces));
</script>

{#if entries.length > 0}
  <nav
    aria-label="Spaces"
    class="flex w-14 shrink-0 flex-col items-center gap-1 overflow-y-auto border-r border-border bg-surface-sunken py-2"
  >
    <!-- Keyed on the space id; the empty string stands in for "All rooms",
         which no room id can collide with (every one starts with `!`). -->
    {#each entries as entry (entry.spaceId ?? "")}
      {@const selected = entry.spaceId === spacesStore.selectedId}
      {@const avatar = entry.spaceId === null ? null : avatarCache.get(entry.spaceId)}
      <!--
        `pr-[2px]` against the 2px left border, so the circle sits on the
        strip's optical centre rather than 1px right of it — the same
        compensation the roster row makes with `pl-[10px]` against its own
        `pr-4`.

        `hover:bg-surface/60` for the unselected state and a solid
        `bg-surface` for the selected one: this strip is
        `--color-surface-sunken`, so a control here lifts rather than drops
        (spec §3), and the two states have to differ from each other as well
        as from the ground.
      -->
      <button
        type="button"
        onclick={() => void spacesStore.select(entry.spaceId)}
        aria-current={selected ? "true" : undefined}
        aria-label={entry.label}
        title={entry.label}
        class="flex w-full shrink-0 justify-center border-l-2 py-1.5 pr-[2px] transition-colors {selected
          ? 'border-l-accent bg-surface'
          : 'border-l-transparent hover:bg-surface/60'}"
      >
        {#if avatar}
          <img
            src={avatar}
            alt=""
            aria-hidden="true"
            class="h-8 w-8 rounded-full object-cover"
            onerror={() => avatarCache.markFailed(entry.spaceId ?? "")}
          />
        {:else if entry.spaceId === null}
          <!--
            "All rooms" has no avatar to fall back from, so its circle is the
            word itself, in the mono label rank — the register this design
            uses for chrome that names a state rather than an entity. A
            rounded square, not a circle: it is the one entry that is not a
            room, and the silhouette says so before the label is read.
          -->
          <span
            class="flex h-8 w-8 items-center justify-center rounded-lg border border-border bg-surface-raised font-mono text-label text-content-muted uppercase"
            aria-hidden="true"
          >
            {entry.initial}
          </span>
        {:else}
          <span
            class="flex h-8 w-8 items-center justify-center rounded-full bg-surface-raised text-ui font-medium text-content"
            aria-hidden="true"
          >
            {entry.initial}
          </span>
        {/if}
      </button>
    {/each}
  </nav>
{/if}
