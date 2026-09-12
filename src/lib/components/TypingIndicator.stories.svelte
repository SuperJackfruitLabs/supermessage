<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { typingMany, typingNobody, typingOne, typingTwo, typingUnnamed } from "$lib/fixtures";

  import TypingIndicator from "./TypingIndicator.svelte";

  const { Story } = defineMeta({
    title: "Chrome/TypingIndicator",
    component: TypingIndicator,
  });
</script>

<Story name="One person" args={{ users: typingOne }} />
<Story name="Two people" args={{ users: typingTwo }} />

<!-- Enough that the line must summarise rather than list every name. -->
<Story name="Many people" args={{ users: typingMany }} />

<!--
  No cached display name, so the line falls back to the raw id — which is
  server-controlled arbitrary text, and long. This is the `truncate` case.
-->
<Story name="Unnamed sender" args={{ users: typingUnnamed }} />

<!--
  Nobody typing. The strip keeps its 24px of height and shows nothing, which
  is deliberate: it reserves the space so the timeline above does not shift
  every time someone starts and stops.
-->
<Story name="Nobody (space reserved)" args={{ users: typingNobody }} />
