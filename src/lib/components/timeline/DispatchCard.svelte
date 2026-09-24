<script lang="ts">
  import type { CustomEventDecision, ItemView, TimelineItem } from "$lib/ipc";

  /**
   * The dispatch card — this client's signature element.
   *
   * `2026-08-13-console-design.md` §7 calls it that, and it is the one
   * differentiator of an agent-aware client. It was ~190 lines of markup
   * inside `Timeline.svelte` while being a separate file on iOS
   * (`DecisionCard.swift`) and on Android (`DecisionCard.kt`) — on the
   * platform where it is easiest to iterate, it was the hardest to find.
   *
   * **Every value here is plain-text interpolation.** Never `{@html}`,
   * never an `href`/`src`/inline style fed from the payload:
   * `view` is the outcome of `core::custom_events::resolve_custom_event`
   * over arbitrary JSON from anyone who can send to the room. The core has
   * already bounded its fields and validated its decision before either
   * reaches here. `break-words` plus the card's own `max-w-[68ch]` and
   * `min-w-0` guard against a long unbroken value, label or option widening
   * the card — the same discipline every other sender-controlled surface in
   * this codebase follows.
   *
   * This component decides nothing. It switches on what the core resolved.
   */
  export interface Props {
    /**
     * The whole `customEvent` render decision, carried through unchanged.
     *
     * The OUTER variant, not the inner `CustomEventView`: the card reads
     * `view.label` and `view.eventType` from the wrapper — the *name* the
     * renderer gave this kind of event ("Turn", "Permission") rather than
     * the schema address — and `view.view` for what the core resolved. The
     * markup is unchanged from when it lived in the timeline, which is what
     * makes the extraction checkable.
     */
    view: Extract<ItemView, { render: "customEvent" }>;
    /** The pending decision, or `null` when there is nothing to answer. */
    decision: CustomEventDecision | null;
    /** The item the card belongs to — read for its Matrix event address and timestamp. */
    item: TimelineItem;
    /**
     * The operator's answer.
     *
     * Named for what it does rather than what it sends: the suite's rule is
     * that supermessage acts only through Matrix, so answering a gate is a
     * decision *event*, never a REST call to Superpipeline.
     */
    onDecide: (eventId: string | null, decision: CustomEventDecision, optionId: string) => void;
    /** How the container renders a timestamp, so both agree. */
    formatTime: (ms: number | null) => string;
  }

  let { view, decision, item, onDecide, formatTime }: Props = $props();
</script>

                      <div class="dispatch-card {decision ? 'dispatch-card-pending' : ''}">
<!--
  Header: what the card is left, the timestamp right,
  a hairline beneath. The *name* the renderer gives
  this kind of event ("Turn", "Permission") rather
  than `view.eventType` — a reader should not have to
  parse `dev.agentpod.turn.v1` to learn they are
  looking at a turn. The schema address stays in the
  `title`, for a card nothing recognises and for
  anyone diagnosing one; `displayEventType` truncated
  it from the *left* for exactly that case, and still
  does.
-->
<div
  class="flex items-baseline gap-3 border-b border-border px-3 py-2 font-sans text-content-muted"
>
  <span
    class="min-w-0 flex-1 text-label break-words"
    title={view.eventType}
  >
    {view.label}
  </span>
  <span class="shrink-0 text-meta tabular-nums">{formatTime(item.timestampMs)}</span>
</div>
{#if view.view.status === "rendered"}
  <!--
    A real `<dl>`: these rows are label/value pairs,
    and a screen reader should read them as such
    rather than as a run of unrelated lines. Keyed by
    index, not `field.label` — a renderer's fields are
    trusted (registered application code, not an array
    read straight off the payload), but a duplicate
    label is still possible and shouldn't be able to
    confuse Svelte's keyed reconciliation.

    A two-column *grid*, not a flex row per pair, and
    the label track is `max-content` rather than the
    fixed `9ch` this first shipped with. Both halves
    of that are corrections found by rendering:

    - A fixed `9ch` is narrower than most real labels,
      and `overflow-wrap` then breaks them mid-word —
      `Request`/`ed by`, and at the 60-char bound a
      twelve-line syllable ladder. `min-w-[9ch]` keeps
      the spec's column rank for a short label like
      `Note`; `max-w-[16ch]` bounds it; between them
      an ordinary multi-word label wraps at its spaces
      and only a single over-long *word* still breaks,
      which is `break-words`' (`overflow-wrap:
      break-word`, not `anywhere`) last resort doing
      what it should.
    - A `max-content` track clamped by those two
      widths sizes to the longest label *in this card*
      and applies to every row, so the values still
      line up in one column. Per-row flex would let
      each row pick its own label width and the grid
      would stop being a grid.

    `ch` resolves against the element's own font, so
    the two caps are on the `dt`, which is set in the
    label rank — 9ch of that, not 9ch of the body
    size the card is set in.
  -->
  <dl
    class="selectable m-0 grid grid-cols-[max-content_minmax(0,1fr)] items-baseline gap-x-3 gap-y-1 px-3 py-2"
  >
    {#each view.view.fields as field, i (i)}
      <dt
        class="min-w-[9ch] max-w-[16ch] font-sans text-label break-words text-content-muted"
      >
        {field.label}
      </dt>
      <dd class="m-0 min-w-0 break-words">{field.value}</dd>
    {/each}
  </dl>
  {#if view.view.reasoning}
    <!--
      How the agent reached this, when it said.
      Collapsed: it is context, not the conclusion,
      and an operator scanning a room wants the
      conclusion first.

      A `<details>` rather than a scripted toggle —
      the element already is a disclosure, keyboard
      operable and announced as one, and re-building
      that in Svelte would only be a worse version.
    -->
    <details class="border-t border-border px-3 py-2">
      <summary
        class="cursor-pointer font-sans text-label text-content-muted"
      >
        Reasoning
      </summary>
      <p
        class="selectable mt-2 mb-0 break-words whitespace-pre-wrap text-meta text-content-muted"
      >
        {view.view.reasoning}
      </p>
    </details>
  {/if}
  {#if view.view.newerVersion}
    <!--
      Emphatically *not* amber: this is a note, not a
      decision, and amber is reserved (spec §3). Not
      italic either — a meta line never is.

      `--color-content-muted`, not `faint`, and that
      is a measured floor rather than a preference:
      `faint` on `--color-surface-raised` is 4.16:1
      in dark, under the 4.5:1 bar, because the card's
      ground is *raised* off the surface the rest of
      the log's faint rows sit on. `muted` on the same
      ground is 8.21:1.
    -->
    <p class="px-3 pb-2 font-sans text-meta text-content-muted">
      Shown from a newer version of this event
    </p>
  {/if}
{:else}
  <!-- status === "fallbackBody": the plain-text
       `content.body` Matrix convention puts on every
       suite custom event, for a type this build has
       no renderer for. Body text, no field grid (spec
       §7) — it is prose, not data. -->
  <p class="selectable px-3 py-2 whitespace-pre-wrap break-words">
    {view.view.text}
  </p>
{/if}
{#if decision}
  <!--
    The core supplies the decision's subject. Gate answers also need this
    item's Matrix event address; a UI row id is never a wire reference.

    Everything here is bounded and validated by
    `boundDecision` before it arrives: the prompt is a
    string capped at 300 chars, and there are at most
    four options, each with a string `id` and a string
    `label` capped at 60. A malformed decision is
    `null` by then, so this block cannot render a
    half-built control.
  -->
  <div class="border-t border-border px-3 py-2">
    <p class="selectable break-words">{decision.prompt}</p>
    <!--
      The only amber in the application (spec §7.1),
      alongside this card's left edge and ground. It
      says the operator owes someone an answer.
    -->
    <p class="mt-2 font-sans text-label text-signal">
      Awaiting your decision
    </p>
    <div class="mt-1.5 flex flex-wrap gap-2">
      <!--
        Keyed by index, not `option.id`, and for a
        sharper reason than the field grid above:
        `boundDecision` guarantees each `id` is a
        string, but nothing makes two options' ids
        *distinct* — a renderer echoing a payload
        could easily produce two `"approve"`s, and a
        duplicate key is a Svelte runtime error
        (`each_key_duplicate`) that would take the
        whole timeline render down. The id is still
        what `onDecide` receives; it is never a key
        and never reaches the DOM.
      -->
      {#each decision.options as option, i (i)}
        <button
          type="button"
          onclick={() => decision && onDecide(item.eventId, decision, option.id)}
          class="min-w-0 max-w-full rounded border border-signal px-2.5 py-1 font-sans text-ui font-medium break-words text-signal transition-colors hover:bg-signal hover:text-surface-raised"
        >
          {option.label}
        </button>
      {/each}
    </div>
  </div>
{/if}
                      </div>

<style>
/*
  * The dispatch card's frame (spec §7) — the timeline's only bordered
  * object, and the only place `--color-signal` (amber) appears anywhere in
  * this application (spec §3).
  *
  * **Two border ranks, and the difference between them is the whole
  * device.** A 1px `--color-border` hairline on three sides, a 2px
  * `--color-border-strong` edge on the left (spec §7). The first
  * implementation used `border-strong` on all four sides, and rendering it
  * is what exposed the mistake: the left edge was then the same colour as
  * its neighbours and merely one pixel wider — invisible at any normal
  * viewing distance. That left the card's signature device existing *only*
  * on the pending variant, which no shipped renderer can currently
  * produce, so everything a user could actually see had no signature at
  * all. The edge has to read as a rank in the ordinary state, so that
  * going amber changes an edge's **meaning** rather than conjuring an edge
  * from nothing.
  *
  * This matters more in light than the token table suggests:
  * `--color-surface-raised` on `--color-surface` measures 1.03:1, so in
  * light mode the card has, for practical purposes, no ground — only its
  * frame. The frame is what makes it an object there.
  *
  * Written here rather than as Tailwind utilities for one specific
  * reason: the card sets `border-color` on three sides and a *different*
  * `border-left-color` on the fourth. As utilities those are two rules of
  * equal specificity, so which one wins depends on the order Tailwind
  * happens to emit `border-color` and `border-left-color` in — not on the
  * order they appear in the class attribute, which is what a reader would
  * naturally assume. One rule, with the left edge stated after the
  * shorthand, is unambiguous. It also lets the pending swap be a single
  * named state rather than four interleaved conditionals.
  *
  * `--color-signal-soft` is the pending ground and `--color-signal` the
  * pending edge; both are tokens, no literal colours (spec §3). The 100ms
  * transition is the whole motion budget this element gets (spec §8) and
  * is covered by `app.css`'s `prefers-reduced-motion` opt-out.
  */
  .dispatch-card {
    border: 1px solid var(--color-border);
    border-left: 2px solid var(--color-border-strong);
    /* `--radius-card`, which is 8px, not the 6px this element carried
       before the scales existed. Two pixels, and taken deliberately: the
       role is called `card` because this is the thing it is named for, and
       a signature element quietly using the control radius is how the four
       ad-hoc radii happened in the first place. */
    border-radius: var(--radius-card);
    background-color: var(--color-surface-raised);
    transition:
      background-color var(--duration-quick),
      border-color var(--duration-quick);
  }

  .dispatch-card-pending {
    border-left-color: var(--color-signal);
    background-color: var(--color-signal-soft);
  }
</style>
