<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import EditBanner from "./EditBanner.svelte";

  // Plain DOM, as DispatchCard.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for Cancel");
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  const { Story } = defineMeta({
    title: "Composer/EditBanner",
    component: EditBanner,
    args: { onCancel: () => {} },
  });
</script>

<Story name="Editing" />

<!-- The keyboard route to cancelling: the play focuses Cancel. -->
<Story
  name="Edit, Cancel focused"
  play={async ({ canvasElement }: { canvasElement: HTMLElement }) => {
    const cancel = await settled(() => canvasElement.querySelector('button[aria-label="Cancel"]'));
    (cancel as HTMLButtonElement).focus();
  }}
/>
