<script lang="ts">
  // Story-only: a row-like button that opens the RowMenu and closes it the way RoomList does, so a
  // play can check where focus lands after each way out.
  import RowMenu from "./RowMenu.svelte";

  let open = $state(false);
</script>

<button type="button" data-testid="opener" onclick={() => (open = true)} class="m-4 rounded border px-3 py-1">
  A room row
</button>
{#if open}
  <RowMenu
    items={[
      { id: "mute", label: "Mute", destructive: false },
      { id: "pin", label: "Pin", destructive: false },
    ]}
    at={{ x: 120, y: 80 }}
    onChoose={() => (open = false)}
    onClose={() => (open = false)}
  />
{/if}
