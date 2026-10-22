<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import MembershipStretch from "./MembershipStretch.svelte";

  const { Story } = defineMeta({
    title: "Timeline/MembershipStretch",
    component: MembershipStretch,
    args: { onOpen: () => {} },
    parameters: {
      docs: {
        description: {
          component:
            "Five or more membership changes folded into one line the reader " +
            "can open. The core writes the summary; this is the same quiet " +
            "row as LogLine with a chevron, and a button because it opens.",
        },
      },
    },
  });
</script>

<Story
  name="Collapsed"
  args={{ text: "5 membership changes · Strategy Sam, Rakesh and 1 other" }}
/>

<!--
  The guard this row shares with LogLine. `text` is built from members' own
  unbounded display names; without min-w-0 + max-w + break-words a 600-character
  name would hold the row, and the scroller, open to its own width.
-->
<Story
  name="Unbounded names"
  args={{ text: "5 membership changes · " + "z".repeat(600) + ", Rakesh and 1 other" }}
/>
