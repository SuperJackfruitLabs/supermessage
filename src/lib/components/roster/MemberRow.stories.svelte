<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { memberAgent, memberHuman, memberLongName, memberWithoutName } from "$lib/fixtures";

  import MemberRow from "./MemberRow.svelte";

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
