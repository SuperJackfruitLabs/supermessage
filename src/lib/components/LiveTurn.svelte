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

  import { liveStore } from "$lib/stores/live.svelte";
  import { timelineStore } from "$lib/stores/timeline.svelte";

  let { roomId, senderName }: { roomId: string | null; senderName: string | null } = $props();

  const text = $derived(liveStore.get(roomId));

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
      if (item.kind === "message" && !item.isOwn && item.senderDisplayName !== null) {
        return item.senderDisplayName;
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
    // Read `text` so this runs before every delta's layout, not just the first.
    void text;
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
    void text;
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
      <p class="max-w-[68ch] font-serif text-body whitespace-pre-wrap text-content">
        {text}<!--
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
      </p>
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
