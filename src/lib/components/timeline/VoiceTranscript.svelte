<script lang="ts">
  import type { VoiceNoteTranscript } from "$lib/ipc";
  import { CLAMPED_LINES, isClamped, toggleLabel, transcriptSide } from "../voiceTranscriptView";

  /**
   * What a voice note said — `ItemView` `voiceTranscript`.
   *
   * The hub's transcript notice replies to the note, and the transcript
   * belongs to the note rather than to the agent that posted it: no sender
   * line, and it hugs the note's side of the reading column (`onOwnNote`,
   * decided by the core from the reply's parent). Quieter than a message — a
   * small caption and the words in the muted rank, set off by the same 2px
   * rail a reply's quote uses, because a transcript is a quote of the note.
   *
   * Every string came from whoever sent the notice, so every one is
   * plain-text interpolation — never `{@html}`, an `href`, a `src` or a
   * style — and `break-words` keeps a long unbroken run inside the column.
   */
  let { transcript, onOwnNote }: { transcript: VoiceNoteTranscript; onOwnNote: boolean } = $props();

  let expanded = $state(false);
  let clamped = $state(false);
  let words: HTMLParagraphElement | undefined = $state();

  // Measured, not guessed from a character count: whether the clamp hides
  // anything depends on the column's width and the reader's text size.
  $effect(() => {
    const el = words;
    if (!el || expanded) return;
    const measure = () => (clamped = isClamped(el.scrollHeight, el.clientHeight));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  });
</script>

<div class="flex pt-1 {transcriptSide(onOwnNote) === 'end' ? 'justify-end' : 'justify-start'}">
  <div
    class="voice-transcript min-w-0 border-l-2 border-border pl-2 font-sans {onOwnNote ? 'max-w-[52ch]' : 'max-w-[68ch]'}"
    data-testid="voice-transcript"
    role="group"
    aria-label={transcript.accessibilityLabel}
  >
    <p class="m-0 text-meta text-content-faint tabular-nums" aria-hidden="true">{transcript.caption}</p>
    <p
      bind:this={words}
      class="selectable m-0 mt-0.5 text-ui break-words whitespace-pre-wrap text-content-muted"
      class:clamp={!expanded}
      style={expanded ? undefined : `-webkit-line-clamp: ${CLAMPED_LINES}; line-clamp: ${CLAMPED_LINES};`}
    >{transcript.text}</p>
    {#if clamped || expanded}
      <button
        type="button"
        class="mt-0.5 rounded-control text-meta font-medium text-accent transition-colors hover:text-content"
        aria-expanded={expanded}
        onclick={() => (expanded = !expanded)}
      >
        {toggleLabel(expanded)}
      </button>
    {/if}
  </div>
</div>

<style>
  .clamp {
    display: -webkit-box;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
</style>
