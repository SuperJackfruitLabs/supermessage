<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import SignOutConfirm from "./SignOutConfirm.svelte";

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

  const { Story } = defineMeta({
    title: "Account/SignOutConfirm",
    component: SignOutConfirm,
  });
</script>

<!-- What a reader sees after choosing "Sign out". Focus is on Cancel; the name is not known yet. -->
<Story
  name="Sign out confirmation"
  args={{ accountName: null, onConfirm: () => Promise.resolve(), onCancel: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const cancel = (await settled(() => button(root, "Cancel"))) as HTMLElement;
    await settled(() => (document.activeElement === cancel ? cancel : null));
    const dialog = root.querySelector("dialog") as HTMLDialogElement;
    if (!dialog.matches(":modal")) throw new Error("the dialog is not modal");
  }}
/>

<!-- Once the account's name is known the title says whose account is leaving. -->
<Story
  name="Sign out of a named account"
  args={{ accountName: "Rakesh", onConfirm: () => Promise.resolve(), onCancel: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("dialog[aria-label='Sign out of Rakesh?']"));
  }}
/>
