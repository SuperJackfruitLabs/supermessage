<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import InvitationPanel from "./InvitationPanel.svelte";

  const settle = () => new Promise<void>((r) => setTimeout(r, 800));
  const refuse = () => Promise.reject({ kind: "refused", message: "M_FORBIDDEN: not invited" });

  // Plain DOM, as RecoveryPanel.stories.svelte does (docs/story-snapshots.md):
  // click Accept, then wait for the failure line to be drawn.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the panel to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  const pressAccept = async ({ canvasElement }: { canvasElement: HTMLElement }) => {
    const accept = await settled(() =>
      [...canvasElement.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Accept"),
    );
    accept.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await settled(() => canvasElement.querySelector("[role='alert']"));
  };

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
<Story name="Accept fails" args={{ roomName: "Guild", onAccept: refuse, onDecline: settle }}
  play={pressAccept}
/>
