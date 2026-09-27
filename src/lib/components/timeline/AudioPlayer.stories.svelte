<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import type { Snippet } from "svelte";

  import type { AudioView } from "$lib/ipc";
  import type { NotePlayback } from "$lib/stores/audioPlayback.svelte";
  import {
    audioFile,
    playbackError,
    playbackIdle,
    playbackLoading,
    playbackLongPlaying,
    playbackPaused,
    playbackPlaying,
    voiceNoteCaptioned,
    voiceNoteLong,
    voiceNoteNoWaveform,
    voiceNoteShort,
    voiceTranscriptLong,
    voiceTranscriptShort,
  } from "$lib/fixtures";

  import AudioPlayer from "./AudioPlayer.svelte";
  import VoiceTranscript from "./VoiceTranscript.svelte";

  const { Story } = defineMeta({
    title: "Timeline/AudioPlayer",
    component: AudioPlayer,
    parameters: {
      docs: {
        description: {
          component:
            "An m.audio message as a player: a voice note, or an audio file " +
            "with its name. Every fact on it is core::audio's; the playback " +
            "state is handed in, so no story plays a sound or calls the core.",
        },
      },
    },
  });

  const noop = () => {};
</script>

<!--
  One timeline row, reduced to what the player sits in: the reading column
  on the sheet, and for an own note the own bubble from `Timeline.svelte`'s
  `messageBlock` (the same classes), trailing.
-->
{#snippet note(audio: AudioView, isOwn: boolean, playback: NotePlayback, detail: string | null = null)}
  <div class="flex {isOwn ? 'justify-end' : 'justify-start'}">
    <div
      class="flex min-w-0 flex-col text-content {isOwn
        ? 'max-w-[52ch] rounded-control bg-accent-soft px-3 py-2 font-sans text-body-own'
        : 'max-w-[68ch] font-sans text-body'}"
    >
      <AudioPlayer {audio} {isOwn} {playback} {detail} onToggle={noop} onSeek={noop} onDownload={noop} />
    </div>
  </div>
{/snippet}

{#snippet sheet(content: Snippet)}
  <div class="w-[72ch] max-w-full bg-surface p-4 font-sans">
    {@render content()}
  </div>
{/snippet}

<!-- At rest: the core's length, "0:07", and nothing lit. -->
<Story name="Own, idle">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteShort, true, playbackIdle)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- A peer's note: leading, on its own quiet panel, since a peer has no bubble. -->
<Story name="Other, idle">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteShort, false, playbackIdle)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- 3.2s into 7s: the elapsed clock (truncated, "0:03") and the bars lit to there. -->
<Story name="Own, playing">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteShort, true, playbackPlaying)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<Story name="Other, playing">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteShort, false, playbackPlaying)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- Paused part way keeps its place: the elapsed clock stays, the glyph is Play. -->
<Story name="Paused part way">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteShort, false, playbackPaused)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- Fetching the bytes: a broken ring in the button, the length still shown. -->
<Story name="Loading">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteShort, true, playbackLoading)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- The engine could not play it: said in danger, with the file still on offer. -->
<Story name="Cannot play">
  {#snippet template()}
    {#snippet body()}
      {@render note(voiceNoteShort, false, playbackError)}
      <div class="h-4"></div>
      {@render note(voiceNoteShort, true, playbackError)}
    {/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- The sender wrote no waveform: an even placeholder, not an invented shape. -->
<Story name="No waveform">
  {#snippet template()}
    {#snippet body()}
      {@render note(voiceNoteNoWaveform, false, playbackIdle)}
      <div class="h-4"></div>
      {@render note(voiceNoteNoWaveform, true, playbackPlaying)}
    {/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- 4:59, the core's 120 bars averaged down, and half way through: "2:31". -->
<Story name="Long">
  {#snippet template()}
    {#snippet body()}
      {@render note(voiceNoteLong, false, playbackIdle)}
      <div class="h-4"></div>
      {@render note(voiceNoteLong, true, playbackLongPlaying)}
    {/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- Not a voice note: the file's name and size above the same player, and Save. -->
<Story name="Audio file">
  {#snippet template()}
    {#snippet body()}
      {@render note(audioFile, false, playbackIdle, "1 MB")}
      <div class="h-4"></div>
      {@render note(audioFile, true, playbackIdle, "1 MB")}
    {/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- What the sender wrote with it (MSC2530), under the player. -->
<Story name="With a caption">
  {#snippet template()}
    {#snippet body()}{@render note(voiceNoteCaptioned, true, playbackIdle)}{/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>

<!-- Dark: the same roles, the same decisions. -->
<Story name="Dark">
  {#snippet template()}
    <div data-appearance="dark" class="w-[72ch] max-w-full bg-surface p-4 font-sans text-content">
      {@render note(voiceNoteShort, false, playbackPlaying)}
      <div class="h-4"></div>
      {@render note(voiceNoteShort, true, playbackIdle)}
      <div class="h-4"></div>
      {@render note(voiceNoteShort, false, playbackError)}
    </div>
  {/snippet}
</Story>

<!--
  The hub's transcript under each note, on the note's side and no wider
  than it: under an own note both hug the trailing edge, under a peer's the
  leading one, and a long transcript meets the note at its other edge too.
-->
<Story name="With a transcript">
  {#snippet template()}
    {#snippet body()}
      {@render note(voiceNoteShort, true, playbackIdle)}
      <VoiceTranscript transcript={voiceTranscriptShort} onOwnNote={true} />
      <div class="h-6"></div>
      {@render note(voiceNoteLong, false, playbackIdle)}
      <VoiceTranscript transcript={voiceTranscriptLong} onOwnNote={false} />
    {/snippet}
    {@render sheet(body)}
  {/snippet}
</Story>
