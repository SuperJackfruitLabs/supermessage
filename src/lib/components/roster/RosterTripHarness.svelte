<script lang="ts">
  // Story-only: the roster beside a pair of buttons that stand in for the page's destinations, and
  // a count of the avatar reads it makes. The real page wraps `RoomList` in the same `KeptPane`.
  import KeptPane from "../KeptPane.svelte";
  import RoomList, { type Source } from "../RoomList.svelte";

  let { source }: { source: Partial<Source> } = $props();
  let here = $state(true);
  let avatarCalls = $state(0);
</script>

<div
  class="flex h-[460px] w-[288px] flex-col bg-surface font-sans text-content"
  data-testid="trip-root"
  data-avatar-calls={avatarCalls}
>
  <div class="flex shrink-0 gap-2 p-1">
    <button type="button" data-testid="away" onclick={() => (here = false)}>Needs you</button>
    <button type="button" data-testid="back" onclick={() => (here = true)}>Chats</button>
  </div>
  <div class="min-h-0 flex-1">
    <KeptPane active={here}>
      <RoomList
        source={{
          ...source,
          roomAvatar: async () => {
            // After a tick: the cache asks while the roster renders, where a write is not allowed.
            await Promise.resolve();
            avatarCalls += 1;
            return null;
          },
        }}
      />
    </KeptPane>
  </div>
</div>
