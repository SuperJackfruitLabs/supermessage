<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import RosterSectionHeading from "./RosterSectionHeading.svelte";

  const { Story } = defineMeta({
    title: "Roster/RosterSectionHeading",
    component: RosterSectionHeading,
  });
</script>

<Story name="Plain" args={{ title: "Recent", detail: null, attention: false }} />
<Story name="With a detail" args={{ title: "Waiting", detail: "3", attention: false }} />

<!-- `attention` is the one place amber appears in the roster's chrome. -->
<Story name="Attention" args={{ title: "Waiting", detail: "1", attention: true }} />

<!-- A section title the core produced from a space name, long enough to
     need its own truncate. -->
<Story
  name="Long title"
  args={{ title: "Rakeshs-MacBook-Pro.local station events", detail: "6", attention: false }}
/>
