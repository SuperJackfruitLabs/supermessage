<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { reasoningLong, reasoningNone, reasoningShort } from "$lib/fixtures";

  import AgentReasoning from "./AgentReasoning.svelte";

  const { Story } = defineMeta({
    title: "Live/AgentReasoning",
    component: AgentReasoning,
    parameters: {
      docs: {
        description: {
          component:
            "Collapsed by default, which is what makes it affordable: the " +
            "reasoning is parsed by the core over IPC, and nothing is parsed " +
            "until a reader opens the disclosure.",
        },
      },
    },
  });
</script>

<Story name="Streaming, short" args={{ streaming: reasoningShort }} />
<Story name="Streaming, long" args={{ streaming: reasoningLong }} />

<!--
  Nothing streaming. This component deliberately KEEPS the last turn's
  reasoning after the turn ends, so in the app this state still shows
  something; from a cold start in a story it shows nothing, which is the
  honest difference between the two and worth being able to see.
-->
<Story name="Nothing streaming" args={{ streaming: reasoningNone }} />
