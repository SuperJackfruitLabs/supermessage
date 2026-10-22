<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { DEFAULT_PREFS } from "$lib/appearance";

  import AccountPanel from "./AccountPanel.svelte";

  // Plain DOM, as LeaveRoomConfirm.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the panel to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  const radio = (root: HTMLElement, group: string, label: string) =>
    [...root.querySelectorAll(`[role='radiogroup'][aria-label='${group}'] [role='radio']`)].find(
      (b) => (b.getAttribute("aria-label") ?? b.textContent?.trim()) === label,
    );

  const noop = () => {};
  const { Story } = defineMeta({
    title: "Account/AccountPanel",
    component: AccountPanel,
    parameters: { layout: "fullscreen" },
    args: {
      version: "0.0.15",
      prefs: DEFAULT_PREFS,
      onChange: noop,
      onOpenRecovery: noop,
      onSignOut: () => Promise.resolve(),
      onOpenLink: noop,
      onClose: noop,
    },
  });
</script>

<!-- The defaults: System, Tinted, the house accent, Default text. -->
<Story
  name="Account"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => radio(root, "Appearance", "System"));
    const accents = root.querySelectorAll("[role='radiogroup'][aria-label='Accent'] [role='radio']");
    if (accents.length !== 6) throw new Error(`expected 6 accent swatches, saw ${accents.length}`);
  }}
/>

<!-- Dark + Black + Teal chosen: the swatches are drawn in the Black palette. -->
<Story
  name="Dark, black, teal"
  args={{ prefs: { ...DEFAULT_PREFS, mode: "dark", darkStyle: "black", accent: "teal" } }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const teal = (await settled(() => radio(root, "Accent", "Teal"))) as HTMLElement;
    if (teal.getAttribute("aria-checked") !== "true") throw new Error("Teal is not selected");
    if (teal.getAttribute("data-appearance") !== "black") throw new Error("swatch is not in black");
  }}
/>
