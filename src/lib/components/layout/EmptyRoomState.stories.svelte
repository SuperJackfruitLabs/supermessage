<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import EmptyRoomState from "./EmptyRoomState.svelte";

  const { Story } = defineMeta({ title: "Layout/EmptyRoomState", component: EmptyRoomState });
</script>

<Story name="Nothing selected" args={{}} />
