<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import RowMenu from "./RowMenu.svelte";

  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the menu to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  // Focus is on the first item once the menu has placed itself.
  const focused = async ({ canvasElement }: { canvasElement: HTMLElement }) => {
    const first = await settled(() => canvasElement.querySelector<HTMLElement>("[role=menuitem]"));
    await settled(() => (document.activeElement === first ? first : null));
  };

  const { Story } = defineMeta({
    title: "Roster/RowMenu",
    component: RowMenu,
    args: { at: { x: 120, y: 80 }, onChoose: () => {}, onClose: () => {} },
  });
</script>

<Story
  name="Joined room, muted"
  args={{
    items: [
      { id: "mute", label: "Unmute", destructive: false },
      { id: "pin", label: "Pin", destructive: false },
    ],
  }}
  play={focused}
/>

<Story
  name="Invitation"
  args={{ items: [{ id: "decline", label: "Decline", destructive: true }] }}
  play={focused}
/>
