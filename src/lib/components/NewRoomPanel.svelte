<script lang="ts">
  // Starting a conversation, rather than waiting to be invited to one.
  //
  // Three views in one dialog. The first is iOS's: the people you already share a room with,
  // agents first, searchable; choosing one opens the DM you have with them, or makes it. Joining
  // a room by its address is the second (iOS "Join a room"). The third is the desktop's own
  // named-room form, kept because iOS has no equivalent (open question Q6).
  //
  // The checking lives in `roomCreation.ts`: a Matrix id and a room alias have exact shapes, and
  // a request built from a typo comes back as an opaque homeserver error rather than "that is
  // not a user id". The search lives in `peopleSearch.ts`: the core walks every joined room on
  // each call, so it is debounced and a stale answer is dropped.

  import { onDestroy, onMount } from "svelte";
  import { errorMessage } from "$lib/errorMessage";
  import type { PersonDto } from "$lib/ipc";
  import {
    creationProblem,
    emptyState,
    isRoomTarget,
    parseInvitees,
    peopleHeading,
    personSubtitle,
    shouldBeDirect,
  } from "./roomCreation";
  import {
    PEOPLE_RENDER_CAP,
    createPeopleSearch,
    type PeopleState,
  } from "./peopleSearch";

  /**
   * Creating, joining or opening, and what to do once it worked.
   *
   * The IPC calls are callbacks, which is what lets a story exercise this panel without a Tauri
   * host behind it. `onOpened` is: re-read the rail, then select the room. Both halves matter:
   * joining by alias is one of the ways a space arrives, and a space that appeared while this
   * panel was open would otherwise be invisible until the next launch.
   */
  export interface Props {
    onCreate: (name: string, invitees: string[], direct: boolean) => Promise<string>;
    onJoin: (alias: string) => Promise<string>;
    onOpened: (roomId: string) => Promise<void>;
    onClose: () => void;
    /** The people to list: everyone with `null`, otherwise those matching the query. */
    loadPeople: (query: string | null) => Promise<PersonDto[]>;
    /** Resolves to the room to open: the DM already shared with them, or a new one. */
    onOpenPerson: (person: PersonDto) => Promise<string>;
  }

  let { onCreate, onJoin, onOpened, onClose, loadPeople, onOpenPerson }: Props = $props();

  type View = "people" | "join" | "create";
  const TITLES: Record<View, string> = {
    people: "New conversation",
    join: "Join a room",
    create: "New room",
  };

  let view = $state<View>("people");
  let name = $state("");
  let inviteText = $state("");
  let target = $state("");
  let query = $state("");
  let busy = $state(false);
  let openingId = $state<string | null>(null);
  let failure = $state<string | null>(null);
  let peopleState = $state<PeopleState>({ kind: "loading" });

  const invitees = $derived(parseInvitees(inviteText));
  const problem = $derived(creationProblem(name, invitees));
  const loadedPeople = $derived(peopleState.kind === "loaded" ? peopleState.people : []);
  const shown = $derived(loadedPeople.slice(0, PEOPLE_RENDER_CAP));

  const search = createPeopleSearch(
    (q) => loadPeople(q),
    (state) => (peopleState = state),
  );

  onMount(() => search.search("", true));
  onDestroy(() => search.dispose());

  function focusOnMount(node: HTMLInputElement) {
    node.focus();
  }

  function show(next: View): void {
    view = next;
    failure = null;
  }

  /**
   * Opens the room that was just made, joined or found, and closes.
   *
   * The rail is re-read too: joining by alias is one of the ways a space arrives, and a space
   * that appeared while this panel was open would otherwise be invisible until the next launch.
   */
  async function finish(roomId: string): Promise<void> {
    await onOpened(roomId);
    onClose();
  }

  async function openPerson(person: PersonDto): Promise<void> {
    if (busy) return;
    busy = true;
    openingId = person.userId;
    failure = null;
    try {
      await finish(await onOpenPerson(person));
    } catch (err) {
      failure = errorMessage(err);
    } finally {
      busy = false;
      openingId = null;
    }
  }

  async function submit(): Promise<void> {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      if (view === "create") {
        if (problem !== null) {
          failure = problem.message;
          return;
        }
        await finish(await onCreate(name, invitees, shouldBeDirect(invitees)));
      } else {
        if (!isRoomTarget(target)) {
          failure = "That is not a room. They look like #missions:id.agentpod.dev.";
          return;
        }
        await finish(await onJoin(target));
      }
    } catch (err) {
      failure = errorMessage(err);
    } finally {
      busy = false;
    }
  }

  const quietRow =
    "rounded-control px-3 py-2 text-left text-ui text-content transition-colors hover:bg-surface-sunken";
  const field =
    "rounded-control border border-border bg-surface-sunken px-3 py-2 text-ui text-content placeholder:text-content-faint focus:border-accent focus:outline-none";
</script>

<div class="fixed inset-0 z-50 flex items-start justify-center p-4 pt-16">
  <button
    type="button"
    aria-label="Close"
    class="absolute inset-0 bg-scrim"
    onclick={onClose}
  ></button>

  <div
    role="dialog"
    tabindex="-1"
    aria-label={TITLES[view]}
    class="relative z-10 flex max-h-[calc(100vh-6rem)] w-full max-w-md flex-col gap-3 overflow-y-auto rounded-card border border-border bg-surface p-4 shadow-overlay"
    onkeydown={(e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    }}
  >
    <h2 class="text-ui font-medium text-content">{TITLES[view]}</h2>

    {#if view === "people"}
      <input
        use:focusOnMount
        bind:value={query}
        oninput={() => search.search(query)}
        type="search"
        aria-label="Search people"
        placeholder="Name, machine, or @user:server"
        class={field}
      />

      {#if peopleState.kind === "loading"}
        <p class="px-1 text-ui text-content-muted" role="status">Looking for who you know</p>
      {:else if peopleState.kind === "failed"}
        <p role="alert" class="text-ui text-danger">{errorMessage(peopleState.error)}</p>
      {:else if loadedPeople.length === 0}
        {@const empty = emptyState(query)}
        <div class="flex flex-col gap-1 px-1 py-2">
          <p class="text-ui font-medium text-content">{empty.title}</p>
          <p class="text-meta text-content-muted">{empty.description}</p>
        </div>
      {:else}
        <section class="flex flex-col gap-1" aria-labelledby="people-heading">
          <h3 id="people-heading" class="px-1 text-meta font-medium text-content-muted">
            {peopleHeading(query)}
          </h3>
          <ul class="flex max-h-72 flex-col overflow-y-auto">
            {#each shown as person (person.userId)}
              <li>
                <button
                  type="button"
                  disabled={busy}
                  aria-busy={openingId === person.userId ? "true" : undefined}
                  onclick={() => void openPerson(person)}
                  class="flex w-full items-center gap-3 rounded-control px-2 py-1.5 text-left transition-colors hover:bg-surface-sunken disabled:opacity-60"
                >
                  <span
                    aria-hidden="true"
                    class="flex size-[30px] shrink-0 items-center justify-center rounded-pill bg-surface-raised text-ui font-medium text-content"
                  >
                    {person.initial}
                  </span>
                  <span class="min-w-0 flex-1">
                    <span class="block truncate text-ui text-content">{person.name}</span>
                    <span class="block truncate text-meta text-content-muted">
                      {openingId === person.userId ? "Working…" : personSubtitle(person)}
                    </span>
                  </span>
                </button>
              </li>
            {/each}
          </ul>
          {#if loadedPeople.length > shown.length}
            <p class="px-1 text-meta text-content-faint">
              Showing {shown.length} of {loadedPeople.length}. Type to narrow the list.
            </p>
          {/if}
        </section>
      {/if}

      {#if failure !== null}
        <p role="alert" class="text-ui text-danger">{failure}</p>
      {/if}

      <div class="flex flex-col border-t border-border pt-2">
        <button type="button" class={quietRow} onclick={() => show("join")}>Join by address</button>
        <button type="button" class={quietRow} onclick={() => show("create")}>New room</button>
      </div>

      <div class="flex justify-end">
        <button
          type="button"
          onclick={onClose}
          class="rounded-control border border-border px-3 py-1.5 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
        >
          Cancel
        </button>
      </div>
    {:else}
      <form
        class="flex flex-col gap-3"
        onsubmit={(e: SubmitEvent) => {
          e.preventDefault();
          void submit();
        }}
      >
        {#if view === "create"}
          <label class="flex flex-col gap-1">
            <span class="text-ui text-content-muted">Name</span>
            <input
              use:focusOnMount
              bind:value={name}
              type="text"
              placeholder="Q4 rollout"
              class={field}
            />
          </label>
          <label class="flex flex-col gap-1">
            <span class="text-ui text-content-muted">Invite</span>
            <input
              bind:value={inviteText}
              type="text"
              placeholder="@agent_echo:id.agentpod.dev, @ana:id.agentpod.dev"
              class={field}
            />
            <!--
              Said before it happens, not discovered afterwards: which half of a client's list
              this lands in is decided at creation and cannot be changed later.
            -->
            <span class="text-meta text-content-faint">
              {invitees.length === 1
                ? "One person — this will be a direct message."
                : "Two or more — this will be a room."}
            </span>
          </label>
        {:else}
          <label class="flex flex-col gap-1">
            <span class="text-ui text-content-muted">Room address</span>
            <input
              use:focusOnMount
              bind:value={target}
              type="text"
              placeholder="#general:supermessage.dev"
              class="{field} font-mono"
            />
            <span class="text-meta text-content-faint">
              An alias like #general:supermessage.dev, or a room id starting with !.
            </span>
          </label>
        {/if}

        {#if failure !== null}
          <p role="alert" class="text-ui text-danger">{failure}</p>
        {/if}

        <div class="flex justify-end gap-2">
          <button
            type="button"
            onclick={() => show("people")}
            class="rounded-control border border-border px-3 py-1.5 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={busy || (view === "join" && target.trim() === "")}
            class="rounded-control bg-accent px-3 py-1.5 text-ui font-medium text-accent-content transition-opacity hover:opacity-90 disabled:opacity-50"
          >
            {busy ? "Working…" : view === "create" ? "Create" : "Join"}
          </button>
        </div>
      </form>
    {/if}
  </div>
</div>
