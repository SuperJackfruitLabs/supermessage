<script lang="ts">
  // Story-only: the sheet opened from a menu that has already closed (focus is on the page body),
  // told which button it was started from, as Timeline does with the More button.
  import ReportSheet from "./ReportSheet.svelte";

  let open = $state(true);
  let more = $state<HTMLButtonElement | null>(null);
</script>

<button bind:this={more} type="button" data-testid="opener" class="m-4 rounded border px-3 py-1">
  More actions
</button>
{#if open}
  <ReportSheet
    subject={{ kind: "user", userId: "@priya:example.org", name: "Priya", isAgent: false }}
    isBlocked={() => false}
    remaining={async () => 2000}
    onSend={async () => null}
    onClose={() => (open = false)}
    opener={more}
  />
{/if}
