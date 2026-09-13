<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import ArrangementMenu from "./ArrangementMenu.svelte";

  const { Story } = defineMeta({
    title: "Roster/ArrangementMenu",
    component: ArrangementMenu,
    args: { onChange: () => {} },
  });
</script>

<Story name="Recent" args={{ view: "recent" }} />
<Story name="Waiting" args={{ view: "waiting" }} />
<Story name="Machine" args={{ view: "machine" }} />
