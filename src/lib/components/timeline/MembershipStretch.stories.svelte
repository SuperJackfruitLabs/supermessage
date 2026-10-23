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

<!--
  The button's accessible name is the summary, with "Shows each change" as its
  description (iOS: label and hint). An aria-label would replace the summary,
  so a screen reader would never say it. Plain DOM, as the other plays here.
-->
<Story
  name="Collapsed"
  args={{ text: "5 membership changes · Strategy Sam, Rakesh and 1 other" }}
  play={async ({ canvasElement }) => {
    const button = (canvasElement as HTMLElement).querySelector("button");
    if (!button) throw new Error("the stretch should be a button");
    if (button.hasAttribute("aria-label")) throw new Error("aria-label would replace the summary as the name");
    if (!button.textContent?.includes("5 membership changes · Strategy Sam, Rakesh and 1 other")) {
      throw new Error("the accessible name should contain the summary");
    }
    const hint = document.getElementById(button.getAttribute("aria-describedby") ?? "");
    if (hint?.textContent !== "Shows each change") {
      throw new Error("the instruction should be the description");
    }
  }}
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
