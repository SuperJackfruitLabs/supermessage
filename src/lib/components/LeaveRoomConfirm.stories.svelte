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

<!--
  The dialog is modal: Tab from the last control wraps inside it instead of
  reaching the page behind, and Escape asks to cancel.
-->
<Story
  name="Modal"
  args={{ roomName: "Research", onLeave: ok, onCancel: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const dialog = (await settled(() => root.querySelector("dialog[open]"))) as HTMLDialogElement;
    // `:modal` is what makes the rest of the document inert.
    if (!dialog.matches(":modal")) throw new Error("the dialog is not modal");
    const leave = button(root, "Leave") as HTMLElement;
    leave.focus();
    // Tab order after the last control must not leave the dialog: the only
    // other tabbables would be outside it, and those are inert.
    const outside = document.createElement("button");
    outside.textContent = "outside";
    document.body.appendChild(outside);
    try {
      if (outside.matches(":focus")) throw new Error("outside reachable");
      outside.focus();
      if (document.activeElement === outside) throw new Error("background is not inert");
    } finally {
      outside.remove();
    }
  }}
/>

<!--
  Escape is the dialog's `cancel` event. Idle, it asks to cancel; while Leave
  is in flight it does nothing, so the panel cannot close mid-call.
-->
<Story
  name="Escape while leaving"
  args={{
    roomName: "Research",
    onLeave: () => new Promise<void>(() => {}),
    onCancel: () => {
      (window as unknown as { __cancels?: number }).__cancels =
        ((window as unknown as { __cancels?: number }).__cancels ?? 0) + 1;
    },
  }}
  play={async ({ canvasElement }) => {
    const w = window as unknown as { __cancels?: number };
    w.__cancels = 0;
    const root = canvasElement as HTMLElement;
    const dialog = (await settled(() => root.querySelector("dialog[open]"))) as HTMLDialogElement;
    dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
    if (w.__cancels !== 1) throw new Error("Escape did not cancel an idle confirm");
    button(root, "Leave")?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await settled(() => ((button(root, "Leave") as HTMLButtonElement | undefined)?.disabled ? root : null));
    dialog.dispatchEvent(new Event("cancel", { cancelable: true }));
    if (w.__cancels !== 1) throw new Error("Escape cancelled while Leave was in flight");
  }}
/>
