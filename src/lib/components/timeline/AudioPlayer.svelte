<script lang="ts">
  import type { AudioView } from "$lib/ipc";
  import type { NotePlayback } from "$lib/stores/audioPlayback.svelte";
  import { audioClockLabel } from "$lib/audioClock";
  import {
    clockText,
    drawnBars,
    filledBars,
    keyboardSeekMs,
    pointerFraction,
    progressOf,
    toggleLabel,
  } from "../audioPlayerView";

  /**
   * An `m.audio` message as a player — `ItemView` `audio`.
   *
   * Draws; decides nothing. What the note *is* (voice or file, its length at
   * rest, its waveform, what a screen reader calls it) is `core::audio`'s,
   * in `audio`. Where it is in playback is `playback`, which the timeline
   * reads from the app's one player (`$lib/stores/audioPlayback`) and a
   * story simply hands in — so every state here can be drawn without a
   * sound being played.
   *
   * Width: the note is a fixed 20rem object, frame included — the own
   * bubble's `px-3` around 18.5rem, or the peer panel's own `px-3` inside
   * 20rem — so the transcript under it (`VoiceTranscript`, capped at the
   * same 20rem) lines up with both of its edges.
   */
  let {
    audio,
    isOwn,
    playback,
    detail = null,
    disabled = false,
    downloading = false,
    onToggle,
    onSeek,
    onDownload,
  }: {
    audio: AudioView;
    /** Inside the reader's own bubble (tinted by the timeline), or on the sheet. */
    isOwn: boolean;
    playback: NotePlayback;
    /** A file's size, formatted by the host — shown under a non-voice title. */
    detail?: string | null;
    /** A local echo: no event to fetch bytes for yet. */
    disabled?: boolean;
    downloading?: boolean;
    onToggle: () => void;
    /** A fraction 0..1 of the note's length. */
    onSeek: (fraction: number) => void;
    onDownload: () => void;
  } = $props();

  const bars = $derived(drawnBars(audio.waveform));
  const durationMs = $derived(playback.durationMs ?? audio.durationMs);
  const lit = $derived(filledBars(progressOf(playback.positionMs, durationMs), bars.length));
  const clock = $derived(clockText(playback.status, playback.positionMs, audio.lengthLabel));
  const seekable = $derived(!disabled && durationMs !== null && durationMs > 0);
  const failed = $derived(playback.status === "error");

  function seekFromPointer(event: PointerEvent): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    onSeek(pointerFraction(event.clientX, rect.left, rect.width));
  }

  function onPointerDown(event: PointerEvent): void {
    if (!seekable || event.button !== 0) return;
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    seekFromPointer(event);
  }

  function onPointerMove(event: PointerEvent): void {
    if ((event.currentTarget as HTMLElement).hasPointerCapture(event.pointerId)) seekFromPointer(event);
  }

  function onKeyDown(event: KeyboardEvent): void {
    if (!seekable || durationMs === null) return;
    const ms = keyboardSeekMs(event.key, playback.positionMs, durationMs);
    if (ms === null) return;
    event.preventDefault();
    onSeek(ms / durationMs);
  }
</script>

<div
  class="max-w-full min-w-0 font-sans {isOwn
    ? 'w-[18.5rem]'
    : 'w-[20rem] rounded-control bg-surface-sunken px-3 py-2'}"
  role="group"
  aria-label={audio.accessibilityLabel}
  data-testid="audio-player"
>
  {#if !audio.isVoice}
    <!-- An audio file keeps its name, and a way to keep the file. -->
    <div class="mb-1.5 flex items-center gap-2">
      <span class="min-w-0 flex-1">
        <span class="selectable block truncate text-ui font-medium text-content">{audio.title}</span>
        {#if detail}
          <span class="block text-meta tabular-nums text-content-muted">{detail}</span>
        {/if}
      </span>
      <button
        type="button"
        class="shrink-0 rounded-control px-2 py-1 text-ui text-content-muted transition-colors hover:bg-surface hover:text-content disabled:opacity-50"
        disabled={disabled || downloading}
        onclick={onDownload}
      >
        {downloading ? "Saving…" : "Save"}
      </button>
    </div>
  {/if}

  <div class="flex items-center gap-3">
    <!--
      Round, and a fixed 32px with a fixed 14px glyph: a glyph alone in a
      control of fixed size is an icon and must not grow (design-language
      §7). The accent is the only fill on the note, so the one thing to press
      is the one thing that is coloured.
    -->
    <button
      type="button"
      class="flex h-8 w-8 shrink-0 items-center justify-center rounded-pill bg-accent text-accent-content transition-opacity hover:opacity-90 disabled:opacity-50"
      aria-label={toggleLabel(playback.status, audio.isVoice)}
      aria-busy={playback.status === "loading"}
      {disabled}
      onclick={onToggle}
    >
      {#if playback.status === "playing"}
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" fill="currentColor">
          <rect x="3" y="2" width="3" height="10" rx="1" />
          <rect x="8" y="2" width="3" height="10" rx="1" />
        </svg>
      {:else if playback.status === "loading"}
        <!-- Stilled by the app-wide reduced-motion rule, and still reads as
             "busy" when it is: a broken ring is not a play glyph. -->
        <svg class="animate-spin" width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" fill="none">
          <circle cx="7" cy="7" r="5" stroke="currentColor" stroke-width="2" stroke-dasharray="22 10" stroke-linecap="round" />
        </svg>
      {:else}
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" fill="currentColor">
          <path d="M4 2.2v9.6c0 .6.7 1 1.2.7l7.4-4.8c.5-.3.5-1 0-1.3L5.2 1.5C4.7 1.2 4 1.6 4 2.2Z" />
        </svg>
      {/if}
    </button>

    <!--
      The waveform is the scrubber: a slider over the note's length, which
      a pointer drags and the arrow keys step. Played bars in the accent,
      the rest in `border-strong` — the waveform is a picture of the note,
      and the clock beside it carries the same progress as text.
    -->
    <div
      class="waveform flex h-7 min-w-0 flex-1 items-center gap-[2px] {seekable ? 'cursor-pointer' : ''}"
      role="slider"
      tabindex={seekable ? 0 : -1}
      aria-label="Position"
      aria-disabled={!seekable}
      aria-valuemin={0}
      aria-valuemax={durationMs === null ? 0 : Math.floor(durationMs / 1000)}
      aria-valuenow={Math.floor(playback.positionMs / 1000)}
      aria-valuetext={durationMs === null
        ? audioClockLabel(playback.positionMs)
        : `${audioClockLabel(playback.positionMs)} of ${audio.lengthLabel ?? audioClockLabel(durationMs)}`}
      onpointerdown={onPointerDown}
      onpointermove={onPointerMove}
      onkeydown={onKeyDown}
    >
      {#each bars as level, i (i)}
        <span
          class="block min-w-px flex-1 rounded-pill {i < lit ? 'bg-accent' : 'bg-border-strong'}"
          style:height="{Math.round(level * 100)}%"
          aria-hidden="true"
        ></span>
      {/each}
    </div>

    <span class="w-[3.25rem] shrink-0 text-right text-meta tabular-nums text-content-muted">
      {clock ?? ""}
    </span>
  </div>

  {#if failed}
    <!-- Never a dead play button: the file is still the reader's to open. -->
    <p class="mt-1.5 flex items-baseline gap-2 text-meta text-danger" role="alert">
      <span>This audio can't be played here.</span>
      <button
        type="button"
        class="rounded-control font-medium text-accent underline-offset-2 hover:underline disabled:opacity-50"
        disabled={disabled || downloading}
        onclick={onDownload}
      >
        {downloading ? "Saving…" : "Download"}
      </button>
    </p>
  {/if}

  {#if audio.caption}
    <!-- What the sender wrote with it (MSC2530): one message, as sent. -->
    <p class="selectable mt-1.5 break-words whitespace-pre-wrap text-content">{audio.caption}</p>
  {/if}
</div>

<style>
  .waveform:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
    border-radius: var(--radius-control);
  }
</style>
