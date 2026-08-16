<script lang="ts">
  // Finding what an agent said last week.
  //
  // A panel over the app rather than a pane beside the roster: search is a
  // thing you do and finish, not a place you live, and the roster is already
  // the width it needs to be. Same shape as the emoji picker for the same
  // reason — nothing about the reading surface has to move.
  //
  // Selecting a result opens its room. It does not scroll to the message
  // itself: that needs a timeline focused on an event, which this client does
  // not have (`TimelineFocus` is never set — see `core::timeline`). Opening
  // the right conversation is most of the distance, and pretending to jump
  // and landing at the bottom would be worse than not offering it.

  import { roomsStore } from "$lib/stores/rooms.svelte";
  import { searchMessages, type SearchResult } from "$lib/ipc";
  import { projectSearchResults } from "./searchView";
  import { relativeTime } from "./roomIdentity";

  let { onClose }: { onClose: () => void } = $props();

  let term = $state("");
  let results = $state<SearchResult[]>([]);
  let searching = $state(false);
  /** Set when a search failed, so the panel does not just look empty. */
  let failure = $state<string | null>(null);
  /** Whether a search has run at all, which is what "no results" depends on. */
  let searched = $state(false);

  const views = $derived(projectSearchResults(results, roomsStore.rooms));
  const now = Date.now();

  function focusOnMount(node: HTMLInputElement) {
    node.focus();
  }

  /**
   * Runs the search.
   *
   * On submit rather than as you type: this is a homeserver round trip across
   * every room, and firing one per keystroke would be rude to the server and
   * useless to the reader, whose query is not finished yet.
   */
  async function run(): Promise<void> {
    if (searching) return;
    searching = true;
    failure = null;
    try {
      results = await searchMessages(term);
      searched = true;
    } catch (err) {
      results = [];
      failure = err instanceof Error ? err.message : String(err);
    } finally {
      searching = false;
    }
  }

  function open(roomId: string): void {
    roomsStore.select(roomId);
    onClose();
  }
</script>

<div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button
    type="button"
    aria-label="Close search"
    class="absolute inset-0 bg-black/40"
    onclick={onClose}
  ></button>

  <div
    role="dialog"
    tabindex="-1"
    aria-label="Search messages"
    class="relative z-10 flex max-h-[70vh] w-full max-w-2xl flex-col gap-3 rounded-lg border border-border bg-surface p-4 shadow-lg"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    }}
  >
    <form
      class="flex gap-2"
      onsubmit={(e: SubmitEvent) => {
        e.preventDefault();
        void run();
      }}
    >
      <input
        use:focusOnMount
        bind:value={term}
        type="search"
        placeholder="Search messages…"
        aria-label="Search messages"
        class="w-full rounded-md border border-border bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none"
      />
      <button
        type="submit"
        disabled={searching || term.trim() === ""}
        class="shrink-0 rounded-md bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-opacity hover:opacity-90 disabled:opacity-50"
      >
        {searching ? "Searching…" : "Search"}
      </button>
    </form>

    {#if failure !== null}
      <p role="alert" class="text-ui text-destructive">{failure}</p>
    {:else if searched && views.length === 0}
      <!--
        Named rather than left blank, and honest about the one thing that
        silently limits it: an encrypted room cannot be searched this way,
        because the homeserver cannot read it.
      -->
      <p class="py-6 text-center text-ui text-content-muted">
        Nothing matched. Encrypted rooms are not searchable.
      </p>
    {:else if views.length > 0}
      <ul class="flex flex-col gap-1 overflow-y-auto">
        {#each views as view (view.eventId)}
          <li>
            <button
              type="button"
              onclick={() => open(view.roomId)}
              class="w-full rounded-md px-3 py-2 text-left transition-colors hover:bg-surface-sunken"
            >
              <span class="flex items-baseline justify-between gap-2">
                <span class="truncate font-sans text-ui font-medium text-content">
                  {view.roomLabel}
                </span>
                <span class="shrink-0 font-mono text-meta text-content-faint">
                  {relativeTime(view.timestampMs, now) ?? ""}
                </span>
              </span>
              <span class="mt-0.5 block truncate font-mono text-meta text-content-faint">
                {view.sender}
              </span>
              <span class="mt-0.5 block font-sans text-ui text-content-muted">
                {view.snippet}
              </span>
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
</div>
