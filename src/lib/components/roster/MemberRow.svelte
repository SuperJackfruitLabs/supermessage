<script lang="ts">
  import type { RoomMember } from "$lib/ipc";
  import { initial, memberDisplayName, splitSigil } from "../roomInfoView";
  import AgentTag from "../AgentTag.svelte";
  import { memberName } from "../safety/memberActions";

  /**
   * One member of a room.
   *
   * `break-words`, not `truncate`, throughout: a display name is
   * sender-controlled free text, this codebase has already shipped the
   * "long unbroken run widens its container" bug twice, and `truncate`
   * would hide a long name outright rather than let a reader see it wrap.
   */
  export interface Props {
    member: RoomMember;
    /** Resolved by the caller, keyed on the member's own `avatarUrl`. */
    avatarUrl: string | null;
    onAvatarFailed: () => void;
    /** Whether this member is blocked: the name is muted and "Blocked" is said in danger. */
    blocked?: boolean;
    /**
     * Opens the member's actions (block, report). Set on every member but this account; the
     * name and id stay selectable text, so the affordance is a trailing button, not the row.
     */
    onActions?: () => void;
  }

  let { member, avatarUrl, onAvatarFailed, blocked = false, onActions }: Props = $props();
</script>

<li class="flex items-center gap-2">
  {#if avatarUrl}
    <img
      src={avatarUrl}
      alt=""
      aria-hidden="true"
      class="h-8 w-8 shrink-0 rounded-pill object-cover"
      onerror={onAvatarFailed}
    />
  {:else}
    <span
      class="flex h-8 w-8 shrink-0 items-center justify-center rounded-pill bg-surface-raised text-ui font-medium text-content"
      aria-hidden="true"
    >
      {initial(memberDisplayName(member))}
    </span>
  {/if}
  <span class="min-w-0 flex-1">
    <!--
      `break-words`, not `truncate`: a member's display name is
      sender-controlled free text, and this codebase has already
      shipped the "long unbroken run widens its container" bug
      twice (see Timeline.svelte's top-of-script doc comment) —
      `truncate` (nowrap + ellipsis) would also just hide a long
      name outright rather than let the reader see it wrap.
    -->
    <span
      class="selectable block font-sans text-ui break-words {blocked ? 'text-content-muted' : 'text-content'}"
    >
      {memberDisplayName(member)}
    </span>
    {#if member.displayName}
      {@const parsedMember = splitSigil(member.userId)}
      <span class="selectable block font-mono text-meta break-words">
        <span class="text-content-faint">{parsedMember.sigil}</span><span
          class="text-content-muted">{parsedMember.rest}</span
        >
      </span>
    {/if}
  </span>
  {#if member.isAgent}<AgentTag />{/if}
  {#if blocked}<span class="shrink-0 text-label text-danger">Blocked</span>{/if}
  {#if onActions}
    <button
      type="button"
      onclick={onActions}
      aria-label={blocked ? `Unblock or report ${memberName(member)}` : `Block or report ${memberName(member)}`}
      aria-haspopup="dialog"
      class="shrink-0 rounded-control px-1.5 py-0.5 text-ui text-content-muted transition-colors hover:bg-surface hover:text-content"
    >
      <span aria-hidden="true">⋯</span>
    </button>
  {/if}
</li>
