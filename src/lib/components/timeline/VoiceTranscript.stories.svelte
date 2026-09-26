<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    voiceTranscriptBare,
    voiceTranscriptHindi,
    voiceTranscriptLong,
    voiceTranscriptShort,
  } from "$lib/fixtures";

  import VoiceTranscript from "./VoiceTranscript.svelte";

  const { Story } = defineMeta({
    title: "Timeline/VoiceTranscript",
    component: VoiceTranscript,
    parameters: {
      docs: {
        description: {
          component:
            "What a voice note said, from the AgentPod hub's transcript " +
            "notice. It belongs to the note, not to the agent that posted " +
            "it: no sender line, on the note's side of the column, set off " +
            "by the reply quote's rail. Every value is plain text.",
        },
      },
    },
  });
</script>

<!-- Under your own note: the trailing edge of the reading column. -->
<Story name="Under own note">
  {#snippet template()}
    <div class="w-[72ch] max-w-full font-sans">
      <VoiceTranscript transcript={voiceTranscriptShort} onOwnNote={true} />
    </div>
  {/snippet}
</Story>

<!--
  A two-minute note in a narrow window: six lines, then Show more. At the
  full 68ch measure this transcript is exactly six lines and the clamp hides
  nothing, so no disclosure is offered — the check is measured, not guessed.
-->
<Story name="Long, clamped">
  {#snippet template()}
    <div class="w-[44ch] max-w-full font-sans">
      <VoiceTranscript transcript={voiceTranscriptLong} onOwnNote={false} />
    </div>
  {/snippet}
</Story>

<!-- No language or length from the hub: the caption is only "Transcript". -->
<Story name="No language or length">
  {#snippet template()}
    <div class="w-[72ch] max-w-full font-sans">
      <VoiceTranscript transcript={voiceTranscriptBare} onOwnNote={false} />
    </div>
  {/snippet}
</Story>

<!-- Devanagari sits taller than Latin; its marks must not clip. -->
<Story name="Hindi">
  {#snippet template()}
    <div class="w-[72ch] max-w-full font-sans">
      <VoiceTranscript transcript={voiceTranscriptHindi} onOwnNote={true} />
    </div>
  {/snippet}
</Story>
