<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { member, memberAgent, memberHuman, memberLongName, memberWithoutName } from "$lib/fixtures";

  import MemberRow from "./MemberRow.svelte";

  const tagged = member({ userId: "@atlas:id.agentpod.dev", displayName: "Atlas (OpenClaw on Ashram)", isAgent: true });
  const ignored = member({ userId: "@priya:id.agentpod.dev", displayName: "Priya", isIgnored: true });

  const { Story } = defineMeta({
    title: "Roster/MemberRow",
    component: MemberRow,
    args: { avatarUrl: null, onAvatarFailed: () => {} },
  });
</script>

<Story name="A person" args={{ member: memberHuman }} />

<!-- An agent, whose name carries the suite's role convention. -->
<Story name="An agent" args={{ member: memberAgent }} />

<!-- No display name, so the row falls back to the matrix id. -->
<Story name="No display name" args={{ member: memberWithoutName }} />

<!--
  One long unbroken run. This row uses `break-words` rather than
  `truncate` for exactly this: `truncate` would hide the name outright
  rather than let a reader see it wrap, and the name is sender-controlled.
-->
<Story name="Unbreakable name" args={{ member: memberLongName }} />

<!-- The core's agent flag draws the "Agent" tag. -->
<Story name="Agent tagged" args={{ member: tagged }} />

<!-- Blocked: a muted name, and "Blocked" in danger. -->
<Story name="Blocked" args={{ member: ignored, blocked: true, onActions: () => {} }} />

<!-- Someone other than you: a trailing "⋯" opens block and report. The play focuses it. -->
<Story
  name="With actions"
  args={{ member: memberHuman, onActions: () => {} }}
  play={async ({ canvasElement }) => {
    const deadline = Date.now() + 2000;
    for (;;) {
      const more = canvasElement.querySelector<HTMLElement>('[aria-label^="Block or report"]');
      if (more) {
        more.focus();
        if (document.activeElement === more) return;
      }
      if (Date.now() > deadline) throw new Error("timed out waiting for the actions button");
      await new Promise((r) => setTimeout(r, 16));
    }
  }}
/>
