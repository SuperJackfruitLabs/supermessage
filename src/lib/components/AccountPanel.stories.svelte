<script lang="ts">
  let toggled = $state(false);
</script>

<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { DEFAULT_PREFS } from "$lib/appearance";

  import AccountPanel from "./AccountPanel.svelte";
  import { KEEP_RUNNING_UNAVAILABLE_FOOTER } from "./accountView";

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
      keepRunning: false,
      trayAvailable: true,
      onKeepRunning: noop,
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

<!-- Keep running off (the default): the switch reads unchecked, under the Notifications footnote. -->
<Story
  name="Keep running off"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const toggle = await settled(() => root.querySelector("[role='switch']"));
    if (toggle.getAttribute("aria-checked") !== "false") throw new Error("the switch is not off");
  }}
/>

<!-- Keep running on. The interaction turns a story-local state on and off again. -->
<Story
  name="Keep running on"
  args={{ keepRunning: true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const toggle = (await settled(() => root.querySelector("[role='switch']"))) as HTMLElement;
    if (toggle.getAttribute("aria-checked") !== "true") throw new Error("the switch is not on");
  }}
/>

<!-- The switch drives story-local state: a click turns it on, a second turns it off. -->
<Story
  name="Keep running toggles"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const toggle = (await settled(() => root.querySelector("[role='switch']"))) as HTMLElement;
    if (toggle.getAttribute("aria-checked") !== "false") throw new Error("it does not start off");
    toggle.click();
    await settled(() => (toggle.getAttribute("aria-checked") === "true" ? toggle : null));
    toggle.click();
    await settled(() => (toggle.getAttribute("aria-checked") === "false" ? toggle : null));
  }}
>
  {#snippet template(args)}
    <AccountPanel {...args} keepRunning={toggled} onKeepRunning={(on) => (toggled = on)} />
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: the label wraps beside the switch and nothing overflows. -->
<Story
  name="Keep running at largest text, 600px"
  args={{ keepRunning: true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const toggle = (await settled(() => root.querySelector("[role='switch']"))) as HTMLElement;
    const row = toggle.parentElement as HTMLElement;
    if (row.scrollWidth > row.clientWidth + 1) throw new Error(`the row overflows: ${row.scrollWidth} > ${row.clientWidth}`);
    const switchBox = toggle.getBoundingClientRect();
    const rowBox = row.getBoundingClientRect();
    if (switchBox.right > rowBox.right + 1) throw new Error("the switch is clipped");
  }}
>
  {#snippet template(args)}
    <div style="zoom: 1.5; width: 600px; height: 100vh; overflow: hidden; transform: translateZ(0);">
      <AccountPanel {...args} />
    </div>
  {/snippet}
</Story>

<!-- No tray to be seen here: none could be created, or (Linux) no StatusNotifier watcher shows it,
     as on GNOME without the AppIndicator extension. The switch is disabled and the footnote says
     closing quits. -->
<Story
  name="Keep running without a tray"
  args={{ keepRunning: true, trayAvailable: false }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const toggle = (await settled(() => root.querySelector("[role='switch']"))) as HTMLButtonElement;
    if (!toggle.disabled) throw new Error("the switch is not disabled");
    if (toggle.getAttribute("aria-checked") !== "false") throw new Error("a disabled switch must not read on");
    const section = toggle.closest("section") as HTMLElement;
    const footnote = section.querySelector("p")?.textContent?.trim();
    if (footnote !== KEEP_RUNNING_UNAVAILABLE_FOOTER) throw new Error(`the footnote is not the unavailable one: ${footnote}`);
  }}
/>
