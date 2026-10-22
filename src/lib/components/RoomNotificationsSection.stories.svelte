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
