<script lang="ts">
  import type { Mentionable } from "$lib/ipc";
  import { mentionLabel } from "../mentions";

  /**
   * The mention autocomplete.
   *
   * Above the composer, not below it: the composer already sits at the
   * bottom of the window, and a list opening downwards would open
   * off-screen. Absolute, so it cannot push the timeline as it grows and
   * shrinks — the reading surface must not move while somebody types a name.
   */
  export interface Props {
    /**
     * The candidates, as `Mentionable` rather than `RoomMember`.
     *
     * They are not the same type — `collectMentions` returns what can be
     * mentioned, which carries no avatar, and svelte-check caught the
     * difference the moment this became a typed prop. That is the argument
     * for these interfaces existing at all.
     */
    matches: Mentionable[];
    /** Which match the keyboard is on. */
    activeIndex: number;
    onPick: (member: Mentionable) => void;
  }

  let { matches, activeIndex, onPick }: Props = $props();
</script>

<ul
  class="absolute bottom-full left-2 z-30 mb-1 max-h-56 w-72 overflow-y-auto rounded-control border border-border bg-surface py-1 shadow-overlay"
  role="listbox"
  aria-label="Mention a member"
>
  {#each matches as member, index (member.userId)}
    <li>
      <button
        type="button"
        role="option"
        aria-selected={index === activeIndex}
        onclick={() => onPick(member)}
        class="flex w-full items-baseline gap-2 px-3 py-1.5 text-left transition-colors {index ===
        activeIndex
          ? 'bg-surface-sunken'
          : 'hover:bg-surface-sunken'}"
      >
        <span class="truncate font-sans text-ui text-content">{mentionLabel(member)}</span>
        {#if member.displayName !== null}
          <span class="truncate font-mono text-meta text-content-faint">
            {member.userId}
          </span>
        {/if}
      </button>
    </li>
  {/each}
</ul>
