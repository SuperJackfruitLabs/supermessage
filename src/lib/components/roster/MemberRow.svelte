<script lang="ts">
  import type { RoomMember } from "$lib/ipc";
  import { initial, memberDisplayName, splitSigil } from "../roomInfoView";

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
  }

  let { member, avatarUrl, onAvatarFailed }: Props = $props();
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
    <span class="selectable block font-sans text-ui text-content break-words">
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
</li>
