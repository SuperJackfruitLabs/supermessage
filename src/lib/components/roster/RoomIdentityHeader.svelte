<script lang="ts">
  import type { RoomIdentity } from "$lib/ipc";

  /**
   * A room's identity at the top of the info panel: avatar, name, role.
   *
   * The 64px circle is why `--text-avatar` exists. Before that rank, the
   * fallback initial took `--text-ui-lg` — the largest reading rank the
   * scale owned — so a 64px circle held a 15px letter and read as broken.
   * It is sized to the circle rather than to a reading rank, which is why
   * it sits apart from the type scale rather than extending it.
   *
   * `avatarUrl` is resolved by the caller. `onAvatarFailed` lets it stop
   * retrying one that will not load.
   */
  export interface Props {
    identity: RoomIdentity;
    avatarUrl: string | null;
    onAvatarFailed: () => void;
  }

  let { identity, avatarUrl, onAvatarFailed }: Props = $props();
</script>

<div class="flex flex-col items-center gap-2 border-b border-border px-4 py-5">
  {#if avatarUrl}
    <img
      src={avatarUrl}
      alt=""
      aria-hidden="true"
      class="h-16 w-16 shrink-0 rounded-pill object-cover"
      onerror={onAvatarFailed}
    />
  {:else}
    <span
      class="flex h-16 w-16 shrink-0 items-center justify-center rounded-pill bg-surface-raised text-avatar text-content"
      aria-hidden="true"
    >
      {identity.initial}
    </span>
  {/if}
  <p class="selectable max-w-full text-center text-ui-lg break-words text-content">
    {identity.name}
  </p>
  {#if identity.role !== null}
    <span
      class="shrink-0 truncate rounded-pill border border-border px-2 py-0.5 font-mono text-label text-content-muted uppercase"
    >
      {identity.role}
    </span>
  {/if}
</div>
