<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import LiveTurnBubble from "./LiveTurnBubble.svelte";

  const para = (text: string) => ({
    block: "paragraph" as const,
    inlines: [{ inline: "text" as const, text }],
  });

  const { Story } = defineMeta({
    title: "Live/LiveTurnBubble",
    component: LiveTurnBubble,
    parameters: {
      docs: {
        description: {
          component:
            "Everything here matches the message this text is about to " +
            "become — same sender line, same measure, same renderer over " +
            "blocks the core parsed. An answer that arrives in one shape and " +
            "settles into another reads as two events, at exactly the moment " +
            "the reader is paying most attention.",
        },
      },
    },
  });
</script>

<Story
  name="Writing"
  args={{ writerName: "Atlas", blocks: [para("Staging is green across all four ABIs.")] }}
/>

<!-- No writer name, so the line falls back to "Agent". -->
<Story name="Unnamed writer" args={{ writerName: null, blocks: [para("Working on it.")] }} />

<!-- Several paragraphs: an agent's answer arrives with its own breaks, and
     losing them mid-stream would reflow when the real message lands. -->
<Story
  name="Several paragraphs"
  args={{
    writerName: "Krishna",
    blocks: [
      para("The contrast contract lists all three grounds, not only the reading surface."),
      para("In dark the worst ground flips to surface-raised, since the ramp runs the other way."),
    ],
  }}
/>

<!-- Empty blocks: the caret alone, which is what the first instant looks like. -->
<Story name="Just the caret" args={{ writerName: "Atlas", blocks: [] }} />
