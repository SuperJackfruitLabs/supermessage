<script lang="ts">
  import type { RichBlock } from "$lib/ipc";
  import RichText from "../RichText.svelte";

  /**
   * A turn as it is being written.
   *
   * Everything here exists to match the message this text is about to
   * *become*: the same sender line a peer message carries, the same measure,
   * the same renderer over blocks the core parsed. An answer that arrives in
   * one shape and settles into another reads as two events, and the reader
   * notices the seam at exactly the moment they are paying most attention.
   *
   * The scroll box, the slide transition and the `role="status"` region stay
   * with the container — those are about *when* this appears, not what it is.
   */
  export interface Props {
    /** Who is writing. `null` falls back to "Agent". */
    writerName: string | null;
    /** The answer so far, parsed into blocks by the core. */
    blocks: RichBlock[];
  }

  let { writerName, blocks }: Props = $props();
</script>

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
