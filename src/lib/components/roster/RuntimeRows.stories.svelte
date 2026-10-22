<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import RuntimeRows from "./RuntimeRows.svelte";

  const { Story } = defineMeta({
    title: "Roster/RuntimeRows",
    component: RuntimeRows,
  });
</script>

<!-- An agent's room: the harness and the machine it runs on. -->
<Story name="Agent room" args={{ runtime: { harness: "claude-code", host: "mac-mini-1" } }} />
