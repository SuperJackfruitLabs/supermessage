<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import InvitationPanel from "./InvitationPanel.svelte";

  const settle = () => new Promise<void>((r) => setTimeout(r, 800));
  const refuse = () => Promise.reject(new Error("M_FORBIDDEN: not invited"));

  const { Story } = defineMeta({
    title: "Rooms/InvitationPanel",
    component: InvitationPanel,
  });
</script>

<Story name="An invitation" args={{ roomName: "Cloudchamber", onAccept: settle, onDecline: settle }} />

<!-- A room name the app does not control, long enough to wrap. -->
<Story
  name="Long room name"
  args={{
    roomName: "Rakeshs-MacBook-Pro.local — station events and run transcripts",
    onAccept: settle,
    onDecline: settle,
  }}
/>

<!--
  A refused join, which is the case where silence is worst: the invitation
  stays on screen either way, so without the failure message the operator
  sees a button that does nothing. Press Accept to see it.
-->
<Story name="Accept fails" args={{ roomName: "Guild", onAccept: refuse, onDecline: settle }} />
