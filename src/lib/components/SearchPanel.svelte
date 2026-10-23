<script lang="ts">
  // Finding what an agent said last week.
  //
  // Two shapes of one panel. Overlaid on the app (`embedded` false), because search is a thing you
  // do and finish, in the same shape as the emoji picker; and as the sidebar's Search destination
  // (`embedded`, iOS `SearchPanel`), which the reader lives in for as long as they like.
  //
  // Selecting a result opens its room. It does not scroll to the message
  // itself: that needs a timeline focused on an event, which this client does
  // not have (`TimelineFocus` is never set — see `core::timeline`). Opening
  // the right conversation is most of the distance, and pretending to jump
  // and landing at the bottom would be worse than not offering it.

  import { errorMessage } from "$lib/errorMessage";
  import type { RoomRow, SearchResult } from "$lib/ipc";
  import {
    createLatestOnly,
    escapeOutcome,
    projectSearchResults,
    rerunsOnScopeChange,
    scopedRoomId,
    searchHint,
    type SearchScope,
  } from "./searchView";
  import { relativeTime } from "./roomIdentity";

  /**
   * Searching, and opening what it finds.
   *
   * `searchMessages` was an IPC call from here and is a callback now — which
   * is what lets a story exercise the empty, failed and populated states
   * without a Tauri host behind it. `rooms` arrives as a view-model because
   * `projectSearchResults` needs it to name the room a hit came from.
   */
  export interface Props {
    rooms: RoomRow[];
    /** The room the search was opened from; null when there is none. */
    scope?: SearchScope | null;
    onSearch: (term: string, roomId?: string) => Promise<SearchResult[]>;
    onOpenRoom: (roomId: string) => void;
    /** Closes the overlay; an embedded panel has nothing to close. */
    onClose?: () => void;
    /**
     * The panel living in the sidebar (iOS `SearchPanel` as a destination): no backdrop, no
     * dialog role, no Close. The field takes focus when it appears; Escape clears the term
     * instead of closing.
     */
    embedded?: boolean;
  }

  let { rooms, scope = null, onSearch, onOpenRoom, onClose, embedded = false }: Props = $props();

  let term = $state("");
  let results = $state<SearchResult[]>([]);
  let searching = $state(false);
  /** Set when a search failed, so the panel does not just look empty. */
  let failure = $state<string | null>(null);
  /** Whether a search has run at all, which is what "no results" depends on. */
  let searched = $state(false);

  /** Starts narrowed to the room, as on iOS. */
  let narrowed = $state(true);
  const latest = createLatestOnly();

  const views = $derived(projectSearchResults(results, rooms));
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
    const ticket = latest.begin();
    searching = true;
    failure = null;
    try {
      const found = await onSearch(term, scopedRoomId(scope, narrowed));
      if (!latest.isCurrent(ticket)) return;
      results = found;
      searched = true;
    } catch (err) {
      if (!latest.isCurrent(ticket)) return;
      results = [];
      failure = errorMessage(err);
      // A failed search was still made: changing scope retries it.
      searched = true;
    } finally {
      if (latest.isCurrent(ticket)) searching = false;
    }
  }

  /** Changing the scope re-runs a search already made. */
  function setNarrowed(value: boolean): void {
    const changed = narrowed !== value;
    narrowed = value;
    if (rerunsOnScopeChange({ changed, searched, term })) void run();
  }

  function open(roomId: string): void {
    onOpenRoom(roomId);
    onClose?.();
  }

  /** Escape, consumed here so the page's window handler does not also act on it. */
  function onEscape(e: KeyboardEvent): void {
    if (e.key !== "Escape") return;
    e.preventDefault();
    if (escapeOutcome(embedded) === "close") {
      onClose?.();
      return;
    }
    // A search still in flight must not bring results back to a cleared field.
    latest.begin();
    term = "";
    results = [];
    failure = null;
    searched = false;
    searching = false;
  }
</script>

{#snippet content()}
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
      class="w-full rounded-control border border-border bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none"
    />
    <button
      type="submit"
      disabled={searching || term.trim() === ""}
      class="shrink-0 rounded-control bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-opacity hover:opacity-90 disabled:opacity-50"
    >
      {searching ? "Searching…" : "Search"}
    </button>
  </form>

  {#if scope}
    <div role="radiogroup" aria-label="Search in" class="flex gap-1">
      {#each [{ value: true, label: scope.name }, { value: false, label: "All rooms" }] as option (option.label)}
        <button
          type="button"
          role="radio"
          aria-checked={narrowed === option.value}
          tabindex={narrowed === option.value ? 0 : -1}
          onkeydown={(e: KeyboardEvent) => {
            if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) return;
            e.preventDefault();
            e.stopPropagation();
            setNarrowed(!narrowed);
            const other = [...(e.currentTarget as HTMLElement).parentElement!.children].find(
              (n) => n !== e.currentTarget,
            ) as HTMLElement;
            other.focus();
          }}
          onclick={() => setNarrowed(option.value)}
          class="max-w-[50%] truncate rounded-control border px-3 py-1 text-ui transition-colors {narrowed ===
          option.value
            ? 'border-accent bg-surface-sunken text-content'
            : 'border-border text-content-muted hover:bg-surface-sunken'}"
        >
          {option.label}
        </button>
      {/each}
    </div>
  {/if}

  {#if failure !== null}
    <p role="alert" class="text-ui text-danger">{failure}</p>
  {:else if searched && views.length === 0}
    <!--
      Named rather than left blank, and honest about the one thing that
      silently limits it: an encrypted room cannot be searched this way,
      because the homeserver cannot read it.
    -->
    <p class="py-6 text-center text-ui text-content-muted">
      Nothing matched. Encrypted rooms are not searchable.
    </p>
  {:else if !searched}
    <p class="py-6 text-center text-ui text-content-muted">{searchHint(scope, narrowed)}</p>
  {:else if views.length > 0}
    <ul class="flex flex-col gap-1 overflow-y-auto">
      {#each views as view (view.eventId)}
        <li>
          <button
            type="button"
            onclick={() => open(view.roomId)}
            class="w-full rounded-control px-3 py-2 text-left transition-colors hover:bg-surface-sunken"
          >
            <span class="flex items-baseline justify-between gap-2">
              <span class="truncate font-sans text-ui font-medium text-content">
                {view.roomLabel}
              </span>
              <span class="shrink-0 font-sans text-meta tabular-nums text-content-faint">
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
{/snippet}

{#if embedded}
  <!-- Escape is consumed here, for the whole panel; the landmark is not itself interactive. -->
  <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
  <div class="flex h-full min-h-0 flex-col gap-3 p-3" role="search" aria-label="Search" onkeydown={onEscape}>
    {@render content()}
  </div>
{:else}
  <div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
    <button type="button" aria-label="Close search" class="absolute inset-0 bg-scrim" onclick={() => onClose?.()}
    ></button>

    <div
      role="dialog"
      tabindex="-1"
      aria-label="Search"
      class="relative z-10 flex max-h-[70vh] w-full max-w-2xl flex-col gap-3 rounded-card border border-border bg-surface p-4 shadow-overlay"
      onkeydown={onEscape}
    >
      {@render content()}
    </div>
  </div>
{/if}
