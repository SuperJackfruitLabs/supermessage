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
  const blockedTwo = [
    { userId: "@spammer:example.org", displayName: "Spammy", avatarUrl: null, isAgent: false, isIgnored: true },
    { userId: "@agent_loop:example.org", displayName: "Loop", avatarUrl: null, isAgent: true, isIgnored: true },
  ];
  const buttonNamed = (root: HTMLElement, label: string) =>
    [...root.querySelectorAll("button")].find((b) => b.getAttribute("aria-label") === label);
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
      account: { userId: "@rakesh:id.agentpod.dev", homeserver: "https://id.agentpod.dev/" },
      accountName: "Rakesh",
      blocked: null,
      onUnblock: () => Promise.resolve(null),
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

<!-- Two people are blocked, one an agent: each row has its own Unblock, under the iOS footer. -->
<Story
  name="Blocked users"
  args={{ blocked: blockedTwo }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => buttonNamed(root, "Unblock Spammy"));
    await settled(() => buttonNamed(root, "Unblock Loop"));
  }}
/>

<!-- The homeserver refused: its words replace the footer, in danger. -->
<Story
  name="Unblock fails"
  args={{ blocked: blockedTwo, onUnblock: () => Promise.resolve("Couldn't reach the homeserver.") }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    ((await settled(() => buttonNamed(root, "Unblock Spammy"))) as HTMLElement).click();
    const alert = await settled(() => root.querySelector("[role=alert]"));
    if (alert.textContent?.trim() !== "Couldn't reach the homeserver.") throw new Error("the refusal is not shown");
  }}
/>
