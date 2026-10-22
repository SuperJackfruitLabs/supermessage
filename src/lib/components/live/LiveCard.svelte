<script lang="ts">
  // One agent turn, drawn inside the timeline as a card (iOS
  // `Timeline/LiveTurnView.swift`). It answers, in order, what a reader waiting
  // on an agent asks: who, is it going, for how long, what is it doing now, how
  // far has it got, did anything fail. The individual steps are one click away
  // rather than a growing list.
  //
  // It is a row of the virtual list, so it scrolls with the history and has no
  // height cap of its own. It is the newest thing in the room, still being
  // written; the message it becomes has not arrived. When the turn ends the
  // card goes and the record moves into the answer's row (`WhatIDid`).
  //
  // The streamed answer is paced and parsed exactly as it was when it sat
  // outside the list (`pacer.ts`, the 60 ms parse): the reveal stays smooth
  // although the wire is chunky. The card's own height is never animated, only
  // measured; the history's glide is the timeline's (`glide.ts`).

  import { untrack } from "svelte";
  import type { RichBlock, TurnStep, TurnSummary } from "$lib/ipc";
  import RichText from "../RichText.svelte";
  import { createPacer } from "../pacer";
  import { ipcServices, type LiveCardServices } from "./liveCardServices";
  import {
    elapsedMs,
    failedLabel,
    isFinished,
    showsCurrentStep,
    stateWord,
    statusTone,
    stepsDoneLabel,
    stepsToggleLabel,
    toolGlyph,
    type ToolGlyph,
  } from "./liveCardView";

  export interface Props {
    summary: TurnSummary | null;
    steps: TurnStep[];
    thought: string | null;
    /** The answer so far, cumulative; null before any text. */
    answer: string | null;
    name: string;
    /** A story or a still frame: no clock, no spinner, elapsed to the last event. */
    still?: boolean;
    services?: LiveCardServices;
  }

  let { summary, steps, thought, answer, name, still = false, services = ipcServices }: Props = $props();

  const finished = $derived(isFinished(summary));
  const tone = $derived(statusTone(summary));

  // ---- the header's word and clock, both the core's ----

  let word = $state<string | null>(null);
  $effect(() => {
    const endedBy = summary?.endedBy ?? null;
    let live = true;
    word = null;
    services
      .statusWord(endedBy)
      .then((w) => {
        if (live) word = w;
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  });

  let elapsed = $state("");
  const clock = $derived(summary === null ? null : `${summary.startedAtMs}|${summary.lastActivityMs}|${finished}`);
  $effect(() => {
    if (clock === null) {
      elapsed = "";
      return;
    }
    let live = true;
    const tick = () => {
      const s = untrack(() => summary);
      if (s === null) return;
      services
        .elapsedLabel(elapsedMs(s, Date.now(), still))
        .then((t) => {
          if (live) elapsed = t;
        })
        .catch(() => {});
    };
    tick();
    // Once a second while the turn runs; a still frame or a finished turn is measured once.
    const timer = still || finished ? null : setInterval(tick, 1000);
    return () => {
      live = false;
      if (timer !== null) clearInterval(timer);
    };
  });

  // ---- the answer, paced and parsed ----

  const pacer = createPacer();
  let visible = $state("");
  const turnId = $derived(summary?.turnId ?? null);

  // A new turn starts from nothing, not from wherever the last answer stopped.
  $effect(() => {
    void turnId;
    pacer.reset();
    visible = "";
  });
  $effect(() => {
    if (answer !== null) pacer.receive(answer);
  });
  $effect(() => {
    if (answer === null || still) {
      if (answer !== null) visible = answer;
      return;
    }
    let frame = 0;
    let last = performance.now();
    const step = (now: number) => {
      pacer.advance(now - last);
      last = now;
      visible = pacer.visible;
      frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  });

  /** Parsing costs an IPC round trip; the reveal runs at 60 fps and the parse at ~16. */
  const PARSE_INTERVAL_MS = 60;
  let blocks = $state<RichBlock[]>([]);
  $effect(() => {
    const source = visible;
    if (source === "") {
      blocks = [];
      return;
    }
    // Responses can resolve out of order; a later parse must not be overwritten by an earlier.
    let live = true;
    const timer = setTimeout(() => {
      void services.parse(source).then((parsed) => {
        if (live) blocks = parsed;
      });
    }, still ? 0 : PARSE_INTERVAL_MS);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  });

  const answerShown = $derived(blocks.length > 0 || visible !== "");
  const currentStepTitle = $derived(
    summary !== null ? summary.currentStepTitle : answer !== null ? "Writing the answer" : "Starting",
  );
  const done = $derived(summary?.completedSteps ?? 0);
</script>

{#snippet glyph(kind: ToolGlyph | "ring")}
  <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="shrink-0">
    {#if kind === "done"}
      <circle cx="8" cy="8" r="6.25" /><path d="M5.3 8.2l1.9 1.9 3.6-3.9" />
    {:else if kind === "failed"}
      <circle cx="8" cy="8" r="6.25" /><path d="M5.9 5.9l4.2 4.2M10.1 5.9l-4.2 4.2" />
    {:else if kind === "running"}
      <path d="M13.2 6.2A5.4 5.4 0 0 0 3.4 5M2.8 9.8A5.4 5.4 0 0 0 12.6 11" /><path d="M3.2 2.4v3h3M12.8 13.6v-3h-3" />
    {:else if kind === "ring"}
      <circle cx="8" cy="8" r="6.25" stroke-dasharray="1.2 3" />
    {:else}
      <circle cx="8" cy="8" r="6.25" /><path d="M8 4.6V8l2.2 1.4" />
    {/if}
  </svg>
{/snippet}

<section
  data-testid="live-card"
  aria-label="{name} is working"
  class="flex min-w-0 flex-col gap-2 rounded-card border border-border bg-surface-raised p-3 font-sans {finished ? 'opacity-85' : ''}"
>
  <!-- Who, whether they are still at it, and for how long. -->
  <div class="flex items-baseline gap-1.5">
    <span class="min-w-0 truncate text-ui font-medium text-content">{name}</span>
    <span
      class="ml-auto shrink-0 text-meta {tone === 'working'
        ? 'text-accent'
        : tone === 'ok'
          ? 'text-ok'
          : 'text-content-muted'}"
      data-testid="live-card-state"
      data-tone={tone}>{word ?? stateWord(summary)}</span
    >
    {#if elapsed !== ""}
      <span class="shrink-0 text-meta tabular-nums text-content-muted">· {elapsed}</span>
    {/if}
  </div>

  <!-- "Writing the answer" says nothing the streaming text does not; a step, or a start, does. -->
  {#if showsCurrentStep(summary, answerShown)}
    <div class="flex items-center gap-2 text-accent">
      {#if still}
        {@render glyph("ring")}
      {:else}
        <span class="motion-reduce:hidden" aria-hidden="true">
          <span class="block animate-spin">{@render glyph("ring")}</span>
        </span>
        <span class="hidden motion-reduce:block">{@render glyph("ring")}</span>
      {/if}
      <span class="line-clamp-2 min-w-0 text-ui text-content">{currentStepTitle}</span>
    </div>
  {/if}

  {#if steps.length > 0}
    <div class="flex flex-col gap-0.5">
      <p class="flex items-center gap-1.5 text-meta text-content-muted">
        {@render glyph("done")}<span>{stepsDoneLabel(done)}</span>
      </p>
      {#if summary?.failedStep}
        <p class="flex items-start gap-1.5 text-meta text-danger">
          <span class="mt-0.5">{@render glyph("failed")}</span>
          <span class="line-clamp-2 min-w-0">{failedLabel(summary.failedStep)}</span>
        </p>
      {/if}
    </div>
  {/if}

  {#if thought}
    <details>
      <summary class="cursor-pointer text-meta text-content-muted">Reasoning</summary>
      <p class="selectable mt-1 text-ui whitespace-pre-wrap break-words text-content-muted">{thought}</p>
    </details>
  {/if}

  {#if steps.length > 0}
    <details>
      <summary class="cursor-pointer text-meta text-content-muted">{stepsToggleLabel(steps.length)}</summary>
      <ul class="mt-1 flex flex-col gap-1.5">
        {#each steps as step (step.id)}
          {@const g = toolGlyph(step.phase)}
          {#snippet row()}
            <span class="flex min-w-0 items-center gap-1.5 {g === 'failed' ? 'text-danger' : 'text-content-muted'}">
              {@render glyph(g)}
              <span class="min-w-0 flex-1 truncate text-meta">{step.title}</span>
              <span class="shrink-0 text-meta {g === 'failed' ? 'text-danger' : 'text-content-faint'}">{step.statusLabel}</span>
            </span>
          {/snippet}
          <li>
            {#if step.hasDetail}
              <details class="group">
                <summary class="flex cursor-pointer list-none items-start gap-1">
                  <span class="mt-px inline-block w-3 shrink-0 text-meta text-content-faint group-open:rotate-90" aria-hidden="true">›</span>
                  <span class="min-w-0 flex-1">{@render row()}</span>
                </summary>
                <div class="selectable mt-1 flex flex-col gap-2">
                  {#each [["Touched", step.locations.join("\n")], ["Input", step.input], ["Output", step.output]] as [label, text] (label)}
                    {#if text}
                      <div>
                        <p class="text-meta text-content-faint">{label}</p>
                        <pre class="font-mono text-meta whitespace-pre-wrap break-words text-content-muted">{text}</pre>
                      </div>
                    {/if}
                  {/each}
                </div>
              </details>
            {:else}
              {@render row()}
            {/if}
          </li>
        {/each}
      </ul>
    </details>
  {/if}

  {#if visible !== ""}
    <!--
      The same renderer the landed message uses, over blocks the core parsed:
      an answer that arrives as `**bold**` and settles into bold is the seam
      this card exists to close. The caret is the one honest signal that the
      text is still arriving.
    -->
    <div class="message-html max-w-[68ch] font-sans text-body text-content" role="status" aria-live="polite">
      <RichText {blocks} />{#if !finished}<span
          class="ml-0.5 inline-block h-[1em] w-[0.45em] translate-y-[0.1em] animate-pulse bg-content-muted align-baseline"
          aria-hidden="true"
        ></span>{/if}
    </div>
  {/if}
</section>
