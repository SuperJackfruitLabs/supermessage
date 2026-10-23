<script lang="ts">
  // Mounts the page's name effect against a real live store, so a regression in
  // how the store is read inside an `$effect` shows up as it does in the app:
  // `effect_update_depth_exceeded` while selecting a room. Only a component that
  // runs under the client build can show that; a store unit test cannot.
  import { createLiveStore } from "$lib/stores/live.svelte";
  import { turnAgentName } from "$lib/stores/liveTurn";
  import type { RoomCast } from "$lib/ipc";

  let { roomId, cast }: { roomId: string; cast: RoomCast | null } = $props();

  // Counted on window: Storybook clones args, so a prop cannot carry it back.
  const counter = globalThis as unknown as { __nameEffectRuns: number };
  counter.__nameEffectRuns = 0;

  const never = () => Promise.resolve(() => {});
  const store = createLiveStore({ onLive: never, onThought: never, onTurn: never, turn: async () => null });

  // The same call the page makes, in the same shape.
  $effect(() => {
    counter.__nameEffectRuns += 1;
    store.setAgentName(turnAgentName(store.summary, cast), roomId);
  });
</script>

<p data-testid="name">{store.agentName(roomId) ?? "no name"}</p>
