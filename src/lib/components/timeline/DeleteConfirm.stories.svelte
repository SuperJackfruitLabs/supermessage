<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import DeleteConfirm from "./DeleteConfirm.svelte";

  // Plain DOM, as RecoveryPanel.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the dialog to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  const ok = () => Promise.resolve();
  const refuse = () => Promise.reject(new Error("M_FORBIDDEN: you cannot redact this event"));

  const { Story } = defineMeta({
    title: "Timeline/DeleteConfirm",
    component: DeleteConfirm,
  });
</script>

<!-- What a reader sees after choosing Delete in the message menu. -->
<Story name="Delete confirmation" args={{ onConfirm: ok, onCancel: () => {} }} />

<!--
  The homeserver refused. The dialog stays open and says why in the danger
  colour, so a failed delete never looks like a deleted one.
-->
<Story
  name="Delete failed"
  args={{ onConfirm: refuse, onCancel: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const del = await settled(() =>
      [...root.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Delete"),
    );
    del.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await settled(() => root.querySelector("[role='alert']"));
  }}
/>
