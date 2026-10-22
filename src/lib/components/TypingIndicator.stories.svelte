<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import TypingIndicator from "./TypingIndicator.svelte";

  const { Story } = defineMeta({
    title: "Chrome/TypingIndicator",
    component: TypingIndicator,
  });
</script>

<Story name="One person" args={{ text: "Atlas is typing…" }} />
<Story name="Two people" args={{ text: "Atlas and Krishna are typing…" }} />

<!-- Enough that the line must summarise rather than list every name. -->
<Story name="Many people" args={{ text: "Atlas, Krishna and 2 others are typing…" }} />

<!--
  No cached display name, so the line falls back to the raw id — which is
  server-controlled arbitrary text, and long. This is the `truncate` case.
-->
<Story name="Unnamed sender" args={{ text: "9247e5… is typing…" }} />

<!--
  The overflow case, which the story above only appeared to cover.
  A display name is server-controlled and arrives untouched; the unnamed
  fallback is bounded by the core and cannot overflow.
-->
<Story name="Overlong name" args={{ text: "Extremely Verbose Agent Of Consi… is typing…" }} />

<!--
  Nobody typing. The strip keeps its 24px of height and shows nothing, which
  is deliberate: it reserves the space so the timeline above does not shift
  every time someone starts and stops.
-->
<Story name="Nobody (space reserved)" args={{ text: null }} />
