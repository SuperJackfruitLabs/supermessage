<script lang="ts">
  // An agent's answer as it is being written, before the room has it.
  //
  // **Why this sits outside the timeline.** A streaming message grows its own
  // height continuously, and the virtual list stores measured sizes per key
  // with no way to ask for a remeasure — the fault that clipped a reaction chip
  // on 2026-08-17, but arriving several times a second instead of once. Putting
  // live text inside `VList` would mean fighting that on every delta. Outside
  // it, in ordinary flow between the timeline and the composer, the browser
  // lays it out for free and the virtual list never learns this exists.
  //
  // The seam is honest rather than merely convenient: this is **not history**.
  // It has not been stored in the room, it cannot be replied to, reacted to,
  // searched or scrolled back to, and it disappears the instant the real
  // message arrives. Rendering it among the messages would claim otherwise.
  //
  // It reads as a peer message deliberately — same serif face, same measure —
  // because that is what it is about to become. What marks it as provisional is
  // the caret and the word where the timestamp will be — not muted ink and not
  // a different shape. Dimming the text would mean the answer visibly darkens
  // as it lands, which is the same seam by another route.
  //
  // ## The height cap is load-bearing, not cosmetic
  //
  // The first version had none, and a long answer did not merely look bad: it
  // grew until it had taken the whole window. The timeline is `flex-1` in the
  // same column, so an unbounded sibling starves it — and a virtual list given
  // a zero-height viewport renders nothing at all, which is why the app went
  // blank and stayed blank after the turn ended. One screenshot showed a
  // full-window wall of streaming text with no timeline and no composer; the
  // next showed an empty window.
  //
  // So this element is bounded and scrolls inside itself. A reader can always
  // see the conversation it belongs to and always reach the composer, however
  // much an agent writes.

  import { slide } from "svelte/transition";
  import { cubicOut } from "svelte/easing";
  import { liveStore } from "$lib/stores/live.svelte";
  import { timelineStore } from "$lib/stores/timeline.svelte";
  import { createPacer } from "./pacer";
  import { richBlocksFromMarkdown, type RichBlock } from "$lib/ipc";
  import RichText from "./RichText.svelte";

  let { roomId, senderName }: { roomId: string | null; senderName: string | null } = $props();

  /** What the hub has told us so far. Cumulative — each delta is the whole answer. */
  const text = $derived(liveStore.get(roomId));

  /**
   * ## Why the text on screen is not the text we were told
   *
   * The wire is chunky on purpose. The hub holds a delta until it has at least
   * 24 characters *and* reaches a sentence boundary, or until 1.5s has passed
   * — because every delta is a to-device message to every one of the reader's
   * devices, and one per token would be indefensible. The consequence is that
   * this box can sit perfectly still for a second and a half and then gain a
   * whole paragraph, which is the opposite of watching somebody write.
   *
   * So the pacer stays deliberately behind and spends the debt down at a
   * steady rate. Nothing about the wire changes; the smoothing is entirely
   * local, which is also why it costs no bandwidth to have. See `pacer.ts` for
   * the rate, the catch-up cap, and why a late duplicate can never un-write
   * text that is already on screen.
   *
   * It also happens to fix the panel's height: the box grew in the same jumps
   * the text did (measured at 0 → 69 → 347px), and a steadily growing string
   * gives a steadily growing box for free.
   */
  const pacer = createPacer();
  let visible = $state("");

  // A fresh turn — or a different room — starts from nothing. Without this the
  // next agent's answer would be revealed from wherever the last one stopped.
  $effect(() => {
    void roomId;
    pacer.reset();
    visible = "";
  });

  $effect(() => {
    if (text !== null) pacer.receive(text);
  });

  /**
   * The reveal loop. Runs only while a turn is live, and stops the moment the
   * answer lands in the room and this component unmounts.
   */
  $effect(() => {
    if (text === null) return;
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

  /**
   * How often the revealed text is re-parsed into blocks, in milliseconds.
   *
   * The one genuinely awkward consequence of moving markdown into the core.
   * `AgentProse` tokenised in-process and could afford to run on every one of
   * the pacer's ~60 frames a second; parsing now costs an IPC round trip, and
   * sixty of those a second for a string that grows by a character each time
   * is not a trade worth making.
   *
   * So the reveal stays at 60fps and the *parse* runs at ~16, which is the
   * right way round: what this component exists to protect is the seam when
   * the turn lands — an answer that arrives as `**bold**` and settles into
   * bold — and that is preserved exactly, because the landed message goes
   * through the same parser. What is lost is per-character smoothness in the
   * reveal, which the pacer was already deliberately lagging anyway.
   *
   * Rendering a parsed prefix plus an unparsed tail would keep both, and was
   * rejected: the boundary falls mid-block, so a tail continuing a paragraph
   * would render after the closing `</p>` and drop to its own line — a visible
   * flicker at exactly the moment this component exists to keep steady.
   */
  const PARSE_INTERVAL_MS = 60;

  let blocks = $state<RichBlock[]>([]);

  $effect(() => {
    const source = visible;
    if (source === "") {
      blocks = [];
      return;
    }
    // A generation counter, because responses can resolve out of order: a
    // parse of a longer string issued later could land before an earlier one
    // and be overwritten by it, so the text would appear to go backwards.
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

  /**
   * How long the panel takes to open and close.
   *
   * It used to do neither — it appeared at whatever height its first delta
   * needed and vanished the same way, taking up to 33vh out of the timeline in
   * one step. Sliding is what turns that into the pane making room.
   *
   * Zero under `prefers-reduced-motion`: the panel is already in the right
   * place at either end, and the animation is only about how it gets there.
   */
  const slideMs =
    typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches
      ? 0
      : 170;

  /**
   * The name the arriving message will carry, taken from the last thing this
   * agent actually said in this room.
   *
   * The room's own title is close but not the same string — the header says
   * `krishna` while a message says `krishna (openclaw @ ashram)` — and using
   * it meant the sender line still changed as the answer landed, which is the
   * seam this component exists to close. The timeline already holds the exact
   * text, so it is read from there rather than reconstructed or shipped down
   * the wire a second time.
   *
   * Falls back to the room name for the one case the timeline cannot answer:
   * an agent's very first message in a room nobody has spoken in yet.
   */
  const writerName = $derived.by(() => {
    const items = timelineStore.items;
    for (let i = items.length - 1; i >= 0; i--) {
      const item = items[i]!;
      if (
        item.item.kind === "message" &&
        !item.item.isOwn &&
        item.item.senderDisplayName !== null
      ) {
        return item.item.senderDisplayName;
      }
    }
    return senderName;
  });

  let box = $state<HTMLElement | null>(null);

  /**
   * Whether the reader was at the bottom *before* the new text was laid out.
   *
   * This has to be measured in `$effect.pre`, and the first attempt got it
   * wrong by measuring after: once the delta is in the DOM the box is already
   * taller, so "are we at the bottom" answers about a layout that no longer
   * exists, and every stream past the cap looked pinned whether it was or not.
   */
  let wasAtBottom = true;

  $effect.pre(() => {
    // Read `visible` — what is actually laid out — so this runs before every
    // revealed frame, not only when a delta arrives. Since the pacer reveals
    // on nearly every frame, this is now the difference between following the
    // writing and following the wire.
    void visible;
    const el = box;
    if (!el) return;
    // 48px of slack: a reader a line or two off the bottom is still following,
    // and demanding exactness would drop them the moment a line wrapped.
    wasAtBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
  });

  /**
   * Follow the writing, unless the reader has gone looking.
   *
   * Someone who scrolled up inside a long stream is reading something; yanking
   * them back down is the same discourtesy as scrolling a timeline out from
   * under them. So this follows only what was already being followed.
   */
  $effect(() => {
    void visible;
    const el = box;
    if (el && wasAtBottom) el.scrollTop = el.scrollHeight;
  });
</script>

{#if text !== null}
  <!--
    `max-h-[33vh]` with its own scroll, and `shrink-0` so it keeps that much and
    no more. Both halves matter: without the cap it takes the window, and
    without `shrink-0` it collapses to nothing the moment the timeline is long.
  -->
  <div
    bind:this={box}
    transition:slide={{ duration: slideMs, easing: cubicOut }}
    class="live-turn max-h-[33vh] shrink-0 overflow-y-auto bg-surface px-4 pt-4 pb-3 lg:px-8"
    role="status"
    aria-live="polite"
    aria-label="The agent is writing"
  >
    <div class="mx-auto w-full max-w-[calc(72ch+2rem)] min-w-0 lg:max-w-[calc(72ch+4rem)]">
      <!--
        The same sender line a peer message carries (`Timeline.svelte`, spec
        §6.3): mono, uppercase, muted, on one baseline. `Writing…` sits where
        the timestamp will be, so when the real message lands the line does not
        move — the word is simply replaced by a time.

        The whole point of matching is that this text is about to *become* that
        message. An answer that arrives in one shape and settles into another
        reads as two events, and the reader notices the seam at exactly the
        moment they are paying most attention.
      -->
      <p class="mb-1 flex items-baseline gap-2 font-mono text-meta text-content-muted">
        <span class="min-w-0 truncate text-label uppercase">{writerName ?? "Agent"}</span>
        <span class="shrink-0">Writing…</span>
      </p>
      <!--
        `whitespace-pre-wrap` because an agent's answer arrives with its own
        paragraph breaks and losing them mid-stream would make the text reflow
        when the real message lands, which reads as a flicker at exactly the
        moment the reader is watching most closely.
      -->
      <!--
        The same renderer the landed message uses (`RichText`, over blocks the
        core parsed), for the same reason this component copies the sender line
        and the measure: what is on screen now is about to *become* that
        message, and an answer that arrives as `**bold**` and settles into bold
        is the seam this whole component exists to close.

        Mid-word markers are safe without special handling — CommonMark leaves
        an unclosed `**bo` as literal text until its closing marker lands, so a
        half-typed emphasis cannot flicker on and back off.
      -->
      <div class="message-html max-w-[68ch] font-serif text-body text-content">
        <RichText {blocks} /><!--
          The caret: one honest signal that this is still arriving. A static
          label can go stale — the text can stop moving while the label still
          says "writing" — but a caret sitting immediately after the last
          character is only ever where the writing actually is.

          `aria-hidden` because the `role="status"` region above already
          announces the change; a screen reader has no use for a blinking box.
        --><span
          class="ml-0.5 inline-block h-[1em] w-[0.45em] translate-y-[0.1em] animate-pulse bg-content-muted align-baseline"
          aria-hidden="true"
        ></span>
      </div>
    </div>
  </div>
{/if}

<style>
  /*
    The top edge fades instead of being ruled.

    A hard border made this read as a separate panel bolted under the
    conversation — a slab with the answer trapped in it. What it actually is is
    the next message, arriving. So the text dissolves as it scrolls out of the
    top rather than being sliced by a line, which says "there is more above,
    continuous with this" without drawing a boundary the answer will not have
    once it lands in the timeline.

    `mask-image` rather than a gradient overlay: an overlay would need to match
    the sheet colour and would therefore be wrong in the other theme.
  */
  .live-turn {
    mask-image: linear-gradient(to bottom, transparent 0, black 1.25rem);
    -webkit-mask-image: linear-gradient(to bottom, transparent 0, black 1.25rem);
  }
</style>
