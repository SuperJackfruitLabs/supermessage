<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { member } from "$lib/fixtures";

  import MemberActions from "./MemberActions.svelte";

  // Plain DOM, as LeaveRoomConfirm.stories does: no `storybook/test` in the bundle.
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
  const refuse = () => Promise.reject({ kind: "refused", message: "M_FORBIDDEN" });
  const agent = member({ userId: "@agent_atlas:id.agentpod.dev", displayName: "Atlas", isAgent: true });
  const person = member({ userId: "@priya:id.agentpod.dev", displayName: "Priya" });
  const base = { onBlock: ok, onUnblock: ok, onReport: () => {}, onCancel: () => {} };

  const { Story } = defineMeta({
    title: "Safety/MemberActions",
    component: MemberActions,
  });
</script>

<Story
  name="Block an agent"
  args={{ ...base, member: agent, blocked: false }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => button(root, "Block Atlas"));
    const cancel = button(root, "Cancel") as HTMLElement;
    await settled(() => (document.activeElement === cancel ? cancel : null));
  }}
/>

<Story
  name="Unblock"
  args={{ ...base, member: person, blocked: true }}
  play={async ({ canvasElement }) => {
    await settled(() => button(canvasElement as HTMLElement, "Unblock Priya"));
  }}
/>

<!-- A refused block: the dialog stays open and enabled (the parent says why under its list). -->
<Story
  name="Block fails"
  args={{ ...base, member: person, blocked: false, onBlock: refuse }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const block = (await settled(() => button(root, "Block Priya"))) as HTMLButtonElement;
    block.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await new Promise((r) => setTimeout(r, 100));
    if (!root.querySelector("dialog[open]")) throw new Error("the dialog closed");
    if ((button(root, "Block Priya") as HTMLButtonElement).disabled) throw new Error("the dialog stayed disabled");
  }}
/>
