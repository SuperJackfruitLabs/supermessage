<script lang="ts">
  // What the agent thought, before it said anything.
  //
  // Ported from svelte-ai-elements' `reasoning`: a collapsible whose header
  // reports whether the agent is still thinking and, once it has stopped, how
  // long it took. Its shadcn `Collapsible` is bits-ui here (which shadcn wraps
  // anyway), its `Response` is this app's `RichText`, and its theme
  // variables are this app's tokens. Its `runed` context class is Svelte 5
  // state held directly, since one component needs no shared context.
  //
  // **Collapsed by default, and bounded when open.** This is a sibling of the
  // virtual list in a flex column, and `LiveActivity` records at length what an
  // unbounded sibling does here: it starves the list until it renders nothing.
  // Reasoning is also the least important thing on screen — it is why an answer
  // is what it is, not the answer — so it opens only when asked.
  //
  // **It keeps the last turn's reasoning after the turn ends.** `liveStore`
  // drops the thought channel on `done`, correctly: nothing ephemeral belongs
  // in history. But a reader who watched "Thinking…" for twenty seconds and
  // then got an answer has a fair question about what happened in between, and
  // the answer is gone the instant it becomes askable. So this holds its own
  // copy — in memory, for this room, until the pane is remounted. It is not
  // persisted, not paginated, and a device that was asleep never sees it.

  import { Collapsible } from "bits-ui";
  import RichText from "./RichText.svelte";
  import { richBlocksFromMarkdown, type RichBlock } from "$lib/ipc";
  import Shimmer from "./ai/Shimmer.svelte";
  import { reasoningLabel } from "./reasoningLabel";
  import { liveStore } from "$lib/stores/live.svelte";

  let { roomId }: { roomId: string | null } = $props();

  const streaming = $derived(liveStore.thought(roomId));

  /** The reasoning to show: the live one, or the last one this room produced. */
  let held = $state<string | null>(null);
  let startedAt = $state<number | null>(null);
  let seconds = $state<number | undefined>(undefined);
  let open = $state(false);

  /**
   * The reasoning, parsed into blocks by the core.
   *
   * `AgentProse` tokenised markdown in-process and was deleted when parsing
   * moved into the core, so that iOS and this app cannot disagree about what
   * a `**bold**` is. Parsing now costs an IPC round trip.
   *
   * Which is affordable here for a reason `LiveTurn` cannot rely on: this is
   * collapsed by default, so nothing is parsed until a reader opens it, and
   * the reasoning of a turn that has already ended never changes. Only a
   * reader watching an agent think with the panel *open* re-parses, and
   * `PARSE_INTERVAL_MS` bounds that the same way it bounds the answer.
   */
  const PARSE_INTERVAL_MS = 60;

  let blocks = $state<RichBlock[]>([]);

  $effect(() => {
    const source = open ? held : null;
    if (!source) {
      blocks = [];
      return;
    }
    // A generation counter, for the same out-of-order reason `LiveTurn`
    // documents: a later parse of a longer string can land before an earlier
    // one, and the text would appear to go backwards.
    let live = true;
    const timer = setTimeout(() => {
      void richBlocksFromMarkdown(source).then((parsed) => {
        if (live) blocks = parsed;
      });
    }, PARSE_INTERVAL_MS);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  });

  $effect(() => {
    const now = streaming;
    if (now !== null) {
      // `startedAt` is set once per run of thinking, not per delta — the
      // deltas are cumulative text, and re-stamping on each would report the
      // time since the last chunk instead of since it started thinking.
      if (held === null || startedAt === null) startedAt = Date.now();
      held = now;
      seconds = undefined;
      return;
    }
    // The channel closed. Freeze what it said and how long it ran; leaving
    // `held` in place is the whole point of holding it.
    if (startedAt !== null) {
      seconds = Math.round((Date.now() - startedAt) / 1000);
      startedAt = null;
    }
  });

  const label = $derived(reasoningLabel({ streaming: streaming !== null, seconds }));
</script>

{#if held !== null}
  <!--
    `bg-surface-sunken` and `shrink-0`, like `LiveActivity`: this is chrome
    about the conversation rather than part of it, and it must not take height
    from the list.
  -->
  <div class="shrink-0 bg-surface-sunken px-4 lg:px-8">
    <Collapsible.Root bind:open>
      <Collapsible.Trigger
        class="flex w-full items-center gap-2 py-1 text-left font-mono text-meta text-content-muted transition-colors hover:text-content"
      >
        {#if streaming !== null}
          <!-- The sweep says *this* is being written; a pulse would only say something is. -->
          <Shimmer as="span" contentLength={label.length}>{label}</Shimmer>
        {:else}
          <span>{label}</span>
        {/if}
        <span
          class="ml-auto shrink-0 transition-transform {open ? 'rotate-180' : 'rotate-0'}"
          aria-hidden="true">⌄</span
        >
      </Collapsible.Trigger>
      <!--
        Capped and scrollable: reasoning runs long, and the one thing this must
        never do is push the conversation off screen to show its own working.
      -->
      <Collapsible.Content
        class="max-h-40 overflow-y-auto pb-2 text-content-muted"
      >
        <RichText {blocks} />
      </Collapsible.Content>
    </Collapsible.Root>
  </div>
{/if}
