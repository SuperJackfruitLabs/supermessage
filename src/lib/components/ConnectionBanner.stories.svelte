<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    connectionError,
    connectionErrorLongMessage,
    connectionLive,
    connectionOffline,
    connectionSyncing,
  } from "$lib/fixtures";

  import ConnectionBanner from "./ConnectionBanner.svelte";

  const { Story } = defineMeta({
    title: "Chrome/ConnectionBanner",
    component: ConnectionBanner,
    parameters: {
      docs: {
        description: {
          component:
            "A status strip above the two-pane layout, hidden entirely once " +
            "the core reports `live` — a banner that is always there is just " +
            "noise. State is carried by the label text as well as the colour, " +
            "never by colour alone.",
        },
      },
    },
  });
</script>

<Story name="Offline" args={connectionOffline} />

<Story name="Syncing" args={connectionSyncing} />

<!--
  The only state that uses `--color-danger`. Worth switching appearances on:
  the danger token is one of the six roles that light and paper share, so
  this story should look identical in those two and different in dark.
-->
<Story name="Error" args={connectionError} />

<!--
  The message comes from the core, so its length is not something this app
  controls, and the strip is `h-6` — one line, 24px.
-->
<Story name="Error, long message" args={connectionErrorLongMessage} />

<!--
  Renders nothing, and that is the point of having it here. A banner that
  appears while the connection is fine is the failure mode; an empty frame
  is what correct looks like.
-->
<Story name="Live (renders nothing)" args={connectionLive} />
