<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import RowMenu from "./RowMenu.svelte";
  import RowMenuFocusHarness from "./RowMenuFocusHarness.svelte";

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

  /** Focuses the opener, opens the menu from it, and waits for focus to move into the menu. */
  const openFromOpener = async (root: HTMLElement) => {
    const opener = (await settled(() => root.querySelector("[data-testid=opener]"))) as HTMLElement;
    opener.focus();
    opener.click();
    await settled(() => root.ownerDocument.querySelector("[role=menu] [role=menuitem]:focus"));
    return opener;
  };
  const backOn = (opener: HTMLElement) =>
    settled(() => (document.activeElement === opener ? opener : null), 1000).catch(() => {
      throw new Error("focus did not return to the opener");
    });

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

<Story
  name="Focus returns after Escape"
  play={async ({ canvasElement }) => {
    const opener = await openFromOpener(canvasElement as HTMLElement);
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await backOn(opener);
  }}
>
  {#snippet template()}<RowMenuFocusHarness />{/snippet}
</Story>

<Story
  name="Focus returns after a choice"
  play={async ({ canvasElement }) => {
    const opener = await openFromOpener(canvasElement as HTMLElement);
    (document.querySelector("[role=menuitem]") as HTMLElement).click();
    await backOn(opener);
  }}
>
  {#snippet template()}<RowMenuFocusHarness />{/snippet}
</Story>

<Story
  name="Focus returns after a click outside"
  play={async ({ canvasElement }) => {
    const opener = await openFromOpener(canvasElement as HTMLElement);
    document.body.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
    await backOn(opener);
  }}
>
  {#snippet template()}<RowMenuFocusHarness />{/snippet}
</Story>

<!-- A write for this room is in flight: Mute and Pin cannot be chosen again. -->
<Story
  name="Write in flight"
  args={{
    busy: true,
    items: [
      { id: "mute", label: "Unmute", destructive: false },
      { id: "pin", label: "Pin", destructive: false },
    ],
  }}
  play={async ({ canvasElement }) => {
    const items = [...canvasElement.querySelectorAll<HTMLButtonElement>("[role=menuitem]")];
    await settled(() => (items.length === 2 ? items[0] : null));
    if (!items.every((i) => i.disabled)) throw new Error("Mute and Pin should be disabled while writing");
  }}
/>
