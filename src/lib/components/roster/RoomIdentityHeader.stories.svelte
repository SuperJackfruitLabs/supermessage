<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { roomIdentityLongName, roomIdentityPlain, roomIdentityWithRole } from "$lib/fixtures";

  import RoomIdentityHeader from "./RoomIdentityHeader.svelte";

  const { Story } = defineMeta({
    title: "Roster/RoomIdentityHeader",
    component: RoomIdentityHeader,
    args: { avatarUrl: null, onAvatarFailed: () => {} },
    parameters: {
      docs: {
        description: {
          component:
            "The 64px circle here is why `--text-avatar` exists. Before that " +
            "rank the fallback initial took `--text-ui-lg`, the largest " +
            "reading rank the scale owned, so a 64px circle held a 15px " +
            "letter and read as broken.",
        },
      },
    },
  });
</script>

<!-- The fallback initial at 64px — the case that motivated the rank. -->
<Story name="Fallback initial" args={{ identity: roomIdentityPlain }} />

<Story name="With a role" args={{ identity: roomIdentityWithRole }} />

<!-- `break-words` on the name: a room name is server-controlled text. -->
<Story name="Long name" args={{ identity: roomIdentityLongName }} />
