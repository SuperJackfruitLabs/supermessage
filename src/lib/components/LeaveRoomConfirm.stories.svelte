<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import LeaveRoomConfirm from "./LeaveRoomConfirm.svelte";

  // Plain DOM, as DeleteConfirm.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the dialog to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  const button = (root: HTMLElement, label: string) =>
    [...root.querySelectorAll("button")].find((b) => b.textContent?.trim() === label);

  const ok = () => Promise.resolve();
  // The shape `invoke` rejects with in the app: a serialized CoreError, not an Error.
  const refuse = () => Promise.reject({ kind: "network", message: "" });

  const { Story } = defineMeta({
    title: "Room info/LeaveRoomConfirm",
    component: LeaveRoomConfirm,
  });
</script>

<!-- What a reader sees after choosing "Leave room". Focus is on Cancel. -->
<Story
  name="Leave confirmation"
  args={{ roomName: "Research", onLeave: ok, onCancel: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const cancel = (await settled(() => button(root, "Cancel"))) as HTMLElement;
    await settled(() => (document.activeElement === cancel ? cancel : null));
  }}
/>

<!-- The call failed: the dialog stays open and says so, ready to retry. -->
<Story
  name="Leave fails"
  args={{ roomName: "Research", onLeave: refuse, onCancel: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const leave = await settled(() => button(root, "Leave"));
    leave.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await settled(() => root.querySelector("[role='alert']"));
  }}
/>
