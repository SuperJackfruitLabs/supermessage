<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import NewRoomPanel from "./NewRoomPanel.svelte";

  const created = async () => "!new:id.agentpod.dev";
  const opened = async () => {};

  const { Story } = defineMeta({
    title: "Rooms/NewRoomPanel",
    component: NewRoomPanel,
    args: { onCreate: created, onJoin: created, onOpened: opened, onClose: () => {} },
  });
</script>

<Story name="Create or join" args={{}} />

<!--
  The homeserver refusing. `roomCreation.ts` exists because a request built
  from a typo comes back as an opaque homeserver error rather than "that is
  not a user id" — so this is the state where the panel's own validation has
  passed and the server still said no.
-->
<Story
  name="Create fails"
  args={{ onCreate: async () => { throw new Error("M_ROOM_IN_USE: that alias is taken"); } }}
/>
