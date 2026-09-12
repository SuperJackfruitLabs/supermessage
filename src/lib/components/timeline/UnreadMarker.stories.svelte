<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import UnreadMarker from "./UnreadMarker.svelte";

  const { Story } = defineMeta({ title: "Timeline/UnreadMarker", component: UnreadMarker });
</script>

<!--
  Accent, not amber. Arriving at unread messages is not something the reader
  owes anyone an answer to, and --color-signal means exactly that — so this
  story is also a check that the divider has not drifted into the one
  reserved colour.
-->
<Story name="New" args={{}} />
