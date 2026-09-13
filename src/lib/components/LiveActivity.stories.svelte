<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    toolsLongTitle,
    toolsManyDone,
    toolsNone,
    toolsRunning,
    toolsWithFailure,
  } from "$lib/fixtures";

  import LiveActivity from "./LiveActivity.svelte";

  const { Story } = defineMeta({
    title: "Live/LiveActivity",
    component: LiveActivity,
  });
</script>

<Story name="One tool running" args={{ tools: toolsRunning, thinking: null }} />

<!--
  A failure among running tools. `LiveActivity` names the last FAILED tool
  ahead of any running one, because a failure is the only state here that
  still matters after the turn ends — and it is the only one that gets
  colour. This story is that rule.
-->
<Story name="A tool failed" args={{ tools: toolsWithFailure, thinking: null }} />

<!-- Past two completed, so the "N done" counter earns its place. -->
<Story name="Several done" args={{ tools: toolsManyDone, thinking: null }} />

<!-- The row's `truncate`, with a title the app does not control. -->
<Story name="Long tool title" args={{ tools: toolsLongTitle, thinking: null }} />

<!-- No tools yet, only a thought: the pulsing "thinking…" state. -->
<Story name="Thinking only" args={{ tools: toolsNone, thinking: "weighing two options" }} />

<!-- Neither: renders nothing at all, which is what most of the time looks like. -->
<Story name="Idle (renders nothing)" args={{ tools: toolsNone, thinking: null }} />
