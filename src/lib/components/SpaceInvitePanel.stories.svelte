<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import SpaceInvitePanel from "./SpaceInvitePanel.svelte";

  const settle = () => new Promise<void>((r) => setTimeout(r, 700));
  const refuse = () => Promise.reject({ kind: "refused", message: "M_FORBIDDEN: no longer invited" });

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
  failure has somewhere to be shown.
-->
<Story name="Accept fails" args={{ label: "Guild", onAccept: refuse }}
  play={pressAccept}
/>
