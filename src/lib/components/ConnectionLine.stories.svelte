<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    connectionError,
    connectionLive,
    connectionOffline,
    connectionSyncing,
  } from "$lib/fixtures";

  import ConnectionLine from "./ConnectionLine.svelte";

  const { Story } = defineMeta({
    title: "Chrome/ConnectionLine",
    component: ConnectionLine,
    parameters: {
      docs: {
        description: {
          component:
            "The app's one connection line, under the roster title, in iOS's " +
            "words. Nothing when live. Never amber, no dot.",
        },
      },
    },
  });
</script>

<Story name="Connecting" args={connectionSyncing} />

<Story name="Offline" args={connectionOffline} />

<!-- The core's detail lives in the title attribute only; the line says "Reconnecting…". -->
<Story name="Reconnecting" args={connectionError} />

<!-- Renders nothing; an empty frame is what correct looks like. -->
<Story name="Live (renders nothing)" args={connectionLive} />
