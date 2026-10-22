<script lang="ts">
  // Story-only: the panel opened from a button, as the sidebar's "New conversation" does, with a
  // window-level listener standing in for the page's Escape handling.
  import NewRoomPanel from "./NewRoomPanel.svelte";

  let { escapeLeaked = $bindable(false) }: { escapeLeaked?: boolean } = $props();
  let open = $state(false);
  let opener = $state<HTMLButtonElement | null>(null);

  $effect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !e.defaultPrevented) escapeLeaked = true;
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
</script>

<button bind:this={opener} type="button" data-testid="opener" onclick={() => (open = true)}>
  New conversation
</button>
<span data-testid="leaked">{escapeLeaked ? "leaked" : "consumed"}</span>
{#if open}
  <NewRoomPanel
    onCreate={async () => "!x:y"}
    onJoin={async () => "!x:y"}
    onOpened={async () => {}}
    onClose={() => (open = false)}
    loadPeople={async () => []}
    onOpenPerson={async () => "!x:y"}
  />
{/if}
