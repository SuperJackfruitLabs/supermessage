<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import SpaceInvitePanel from "./SpaceInvitePanel.svelte";

  const settle = () => new Promise<void>((r) => setTimeout(r, 700));
  const refuse = () => Promise.reject(new Error("M_FORBIDDEN: no longer invited"));

  const { Story } = defineMeta({
    title: "Rooms/SpaceInvitePanel",
    component: SpaceInvitePanel,
    args: { onAccept: settle, onDecline: settle, onClose: () => {} },
  });
</script>

<Story name="A space invitation" args={{ label: "New Game" }} />

<Story
  name="Long label"
  args={{ label: "Rakeshs-MacBook-Pro.local — station events and run transcripts" }}
/>

<!--
  Accepting fails. The dialog closes only once the call comes back, so a
  failure has somewhere to be shown — press Accept.
-->
<Story name="Accept fails" args={{ label: "Guild", onAccept: refuse }} />
