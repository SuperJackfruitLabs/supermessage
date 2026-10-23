<script lang="ts">
  import { mentionLabel, runtimeLine, type MentionCandidate } from "../mentions";
  import AgentTag from "../AgentTag.svelte";

  /**
   * The mention autocomplete: the room's cast, agents first (the core's order), each row a
   * face, the name, for an agent where it runs, and the "Agent" tag (iOS
   * `ComposerView.swift:503-549`).
   *
   * Above the composer, not below it: the composer already sits at the
   * bottom of the window, and a list opening downwards would open
   * off-screen. Absolute, so it cannot push the timeline as it grows and
   * shrinks — the reading surface must not move while somebody types a name.
   *
   * The textarea keeps focus and names the active row with `aria-activedescendant`
   * (`optionId`), so a screen reader follows the arrow keys.
   */
  export interface Props {
    /** The candidates, matched and ordered by the core. */
    matches: MentionCandidate[];
    /** Which match the keyboard is on. */
    activeIndex: number;
    onPick: (member: MentionCandidate) => void;
    /** The list's id, for the textarea's `aria-controls`. */
    listId?: string;
  }

  let { matches, activeIndex, onPick, listId = "mention-menu" }: Props = $props();
</script>

<ul
  id={listId}
  class="absolute bottom-full left-2 z-30 mb-1 max-h-72 w-80 max-w-[calc(100%-1rem)] overflow-y-auto rounded-control border border-border bg-surface py-1 shadow-overlay"
  role="listbox"
  aria-label="Mention a member"
>
  {#each matches as member, index (member.userId)}
    <li role="presentation">
      <!-- Not focusable: focus stays in the textarea; the pointer picks, the keys go through it. -->
      <div
        id="{listId}-option-{index}"
        role="option"
        tabindex="-1"
        aria-selected={index === activeIndex}
        aria-label={member.isAgent ? `${mentionLabel(member)}, agent` : mentionLabel(member)}
        onclick={() => onPick(member)}
        onkeydown={(e) => e.key === "Enter" && onPick(member)}
        onmousedown={(e) => e.preventDefault()}
        class="flex min-h-11 w-full cursor-pointer items-center gap-2.5 px-3 py-1.5 text-left transition-colors {index ===
        activeIndex
          ? 'bg-surface-sunken'
          : 'hover:bg-surface-sunken'}"
      >
        <span
          class="flex size-[30px] shrink-0 items-center justify-center rounded-pill bg-accent-soft text-ui font-medium text-accent"
          aria-hidden="true"
          data-testid="mention-face"
        >{member.initial}</span>
        <span class="flex min-w-0 flex-1 flex-col">
          <span class="truncate font-sans text-ui font-medium text-content">{mentionLabel(member)}</span>
          {#if member.runtime}
            <span class="truncate text-meta text-content-muted" data-testid="mention-runtime">{runtimeLine(member.runtime)}</span>
          {/if}
        </span>
        {#if member.isAgent}
          <AgentTag />
        {/if}
      </div>
    </li>
  {/each}
</ul>
