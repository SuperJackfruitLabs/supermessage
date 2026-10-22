<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import RoomNotificationsSection from "./RoomNotificationsSection.svelte";

  const { Story } = defineMeta({
    title: "Room info/RoomNotificationsSection",
    component: RoomNotificationsSection,
    args: { onMode: () => {}, onPinned: () => {} },
  });
</script>

<Story name="Account default" args={{ mode: "default", pinned: false }} />

<Story name="Muted and pinned" args={{ mode: "muted", pinned: true }} />

<!-- A write is in flight: the controls wait for it. -->
<Story
  name="Write in flight"
  args={{ mode: "muted", pinned: false, disabled: true }}
  play={async ({ canvasElement }) => {
    const controls = [...canvasElement.querySelectorAll<HTMLInputElement | HTMLSelectElement>("input, select")];
    if (controls.length !== 3 || !controls.every((c) => c.disabled)) throw new Error("controls should be disabled");
  }}
/>

<!-- The write was refused: the controls show the core's real state, with the line under them. -->
<Story
  name="Write refused"
  args={{ mode: "default", pinned: false, failure: "Couldn't change that. Try again." }}
  play={async ({ canvasElement }) => {
    if (!canvasElement.querySelector("[role=alert]")) throw new Error("the failure line is missing");
  }}
/>
