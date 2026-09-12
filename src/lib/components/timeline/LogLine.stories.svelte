<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import LogLine from "./LogLine.svelte";

  const { Story } = defineMeta({
    title: "Timeline/LogLine",
    component: LogLine,
    parameters: {
      docs: {
        description: {
          component:
            "Every quiet machine row: membership changes, room creation, " +
            "encryption enabled, and every placeholder for something this " +
            "build cannot render. Keeping them literally identical is the " +
            "point — a collapsed membership run must read no differently " +
            "from an ungrouped one, and a placeholder must read as part of " +
            "the log rather than as a failed message.",
        },
      },
    },
  });
</script>

<Story name="Membership" args={{ text: "Krishna joined the room" }} />
<Story name="Grouped membership" args={{ text: "Krishna and 4 others joined the room" }} />
<Story name="Encryption" args={{ text: "Encryption enabled" }} />

<!-- A placeholder: the same row, deliberately. -->
<Story name="Placeholder" args={{ text: "Encrypted message" }} />

<!--
  The guard this row exists to carry. `text` is built from a sender's own
  unbounded display name — before min-w-0 + max-w + break-words, a single
  5000-character name pushed the scroller's scrollWidth to 16515px against
  a 1563px column. break-words alone is not enough: overflow-wrap does not
  reduce min-content size, so the flex item's automatic minimum held the
  row open until min-w-0 let it shrink.
-->
<Story name="Unbounded sender name" args={{ text: "z".repeat(600) + " joined the room" }} />
