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

<!--
  Short and long render identically, and that is correct twice over.

  The component is collapsed by default — deliberately, so nothing is parsed
  until a reader opens it — and collapsed, both are the same one-line trigger.
  So the screenshot gate flags this pair as byte-identical and should: it is
  telling the truth about a component that has one collapsed appearance.

  Opening one was tried and reverted. `RichText` gets its blocks from
  `richBlocksFromMarkdown`, which is `invoke("rich_blocks_from_markdown")` —
  a call into the Rust core. Storybook has no core behind it, so the promise
  never settles and the panel opens empty. **Every component in this
  catalogue that renders markdown has the same hole**, and this pair is
  simply where it became visible.

  The fix is a preview-level stub returning `RichBlock[]` the real core
  produced, not a markdown parser written for the catalogue: this repository
  has exactly one parser by design, and a second one living in Storybook
  would show blocks the app cannot produce. That is tracked in
  `docs/story-snapshots.md` §4 rather than bodged here, for the same reason
  `preview.ts` refuses to fake the theme — a convincing catalogue that is
  wrong is worse than one with a gap in it.
-->
<Story name="Streaming, short" args={{ streaming: reasoningShort }} />
<Story name="Streaming, long" args={{ streaming: reasoningLong }} />

<!--
  Nothing streaming. This component deliberately KEEPS the last turn's
  reasoning after the turn ends, so in the app this state still shows
  something; from a cold start in a story it shows nothing, which is the
  honest difference between the two and worth being able to see.
-->
<Story name="Nothing streaming" args={{ streaming: reasoningNone }} />
