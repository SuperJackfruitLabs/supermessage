<script lang="ts">
  // What an agent is *doing*, while it does it.
  //
  // `LiveTurn` shows what an agent is saying. This shows the work behind it:
  // the tool calls in flight and, when it is not saying anything yet, that it
  // is thinking. Both arrive on their own to-device channels and neither is
  // ever written to a room — see `core/live.rs` and the hub's `matrix-as`.
  //
  // **One line, never a log.** The durable record of what a turn did lands in
  // the room as a card when the turn ends (`dev.agentpod.turn.v1`), and that is
  // where a reader goes to find out what happened. This is the ticker: it
  // answers "what is it doing *right now*", which is a question with exactly
  // one answer at a time. Rendering the whole list here would put the same
  // information on screen twice and make the pane jump on every update.
  //
  // Bounded and `shrink-0` for the reason `LiveTurn` records at length: this is
  // a sibling of the timeline in a flex column, and an unbounded one starves
  // the virtual list until it renders nothing at all.

  import type { LiveTool } from "$lib/stores/live.svelte";

  /**
   * The live turn's tools and its current thought, passed in.
   *
   * `roomId` is gone rather than kept: this component used it only to ask
   * `liveStore` for these two values, so taking them directly means it no
   * longer needs to know that rooms exist at all. The route resolves them.
   */
  export interface Props {
    tools: LiveTool[];
    thinking: string | null;
  }

  let { tools, thinking }: Props = $props();

  /**
   * The tool worth naming: the last one still running, or failing.
   *
   * Last rather than first, because a turn's tools run in sequence and the one
   * a reader cares about is the one happening now. A failure outranks progress
   * — it is the only state here that will still matter after the turn ends.
   */
  const current = $derived.by(() => {
    // On `phase`, the core's decision, never on ACP's raw `status` spellings.
    const failed = tools.findLast((t) => t.phase === "failed");
    if (failed) return failed;
    const running = tools.findLast((t) => t.phase === "queued" || t.phase === "running");
    return running ?? tools.at(-1) ?? null;
  });

  /** Everything that finished before the one being named. */
  const doneCount = $derived(tools.filter((t) => t.phase === "done").length);
</script>

{#if current !== null || thinking !== null}
  <!--
    `bg-surface-sunken`, matching the composer and the field behind the reading
    column rather than the sheet: this is chrome about the conversation, not
    part of it. The same reasoning that keeps the typing indicator off the
    sheet.
  -->
  <div
    class="flex h-6 shrink-0 items-center gap-2 overflow-hidden bg-surface-sunken px-4 font-mono text-meta text-content-muted lg:px-8"
    role="status"
    aria-live="polite"
  >
    {#if current !== null}
      <!--
        The core's `statusLabel` ("Running", "Done", …), shown as given: the
        host no longer spells its own word from ACP's wire vocabulary, which
        is how this used to read `IN PROGRESS`. `failed` is the one phase that
        gets colour, because it is the only state a reader may need to act on.
      -->
      <span class="shrink-0 {current.phase === 'failed' ? 'text-danger' : ''}">
        {current.statusLabel}
      </span>
      <span class="min-w-0 truncate">{current.title}</span>
      {#if doneCount > 1}
        <!-- Only past two: "1 done" beside the thing being done is noise. -->
        <span class="ml-auto shrink-0">{doneCount} done</span>
      {/if}
    {:else}
      <span class="animate-pulse">thinking…</span>
    {/if}
  </div>
{/if}
