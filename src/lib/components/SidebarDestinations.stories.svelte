<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import SidebarDestinations, { type Props } from "./SidebarDestinations.svelte";

  const noop = () => {};

  const { Story } = defineMeta({
    title: "Sidebar/SidebarDestinations",
    component: SidebarDestinations,
    args: { selected: "chats", badgeCount: 0, onSelect: noop },
  });

  const buttons = (root: HTMLElement) => [...root.querySelectorAll<HTMLButtonElement>("nav button")];
  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };

  async function check(root: HTMLElement, selectedId: string, label: string, badge: string | null) {
    await settled(() => buttons(root).length === 4, "four destinations");
    const titles = buttons(root).map((b) => b.querySelector("[data-testid='destination-title']")!.textContent?.trim());
    if (titles.join(",") !== "Chats,Needs you,Agents,Search") throw new Error(`titles: ${titles}`);
    const current = buttons(root).filter((b) => b.getAttribute("aria-current") === "page");
    if (current.length !== 1 || current[0]!.dataset.testid !== `destination-${selectedId}`) {
      throw new Error("exactly the selected one carries aria-current");
    }
    const needs = root.querySelector<HTMLElement>("[data-testid='destination-needsYou']")!;
    if (needs.getAttribute("aria-label") !== label) throw new Error(`label: ${needs.getAttribute("aria-label")}`);
    const shown = root.querySelector("[data-testid='needs-you-badge']")?.textContent?.trim() ?? null;
    if (shown !== badge) throw new Error(`badge: ${shown}`);
  }
</script>

{#snippet frame(args: Props, style: string)}
  <div class="w-[288px] bg-surface-sunken font-sans text-content" {style}>
    <SidebarDestinations {...args} />
  </div>
{/snippet}

<Story
  name="Chats selected"
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "chats", "Needs you", null)}
>
  {#snippet template(args)}{@render frame(args, "")}{/snippet}
</Story>

<Story
  name="Needs you with 3"
  args={{ selected: "needsYou", badgeCount: 3 }}
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "needsYou", "Needs you, 3", "3")}
>
  {#snippet template(args)}{@render frame(args, "")}{/snippet}
</Story>

<Story
  name="Needs you, nothing waiting"
  args={{ selected: "needsYou", badgeCount: 0 }}
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "needsYou", "Needs you", null)}
>
  {#snippet template(args)}{@render frame(args, "")}{/snippet}
</Story>

<Story
  name="Needs you with 3, dark"
  args={{ selected: "agents", badgeCount: 3 }}
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "agents", "Needs you, 3", "3")}
>
  {#snippet template(args)}
    <div data-appearance="dark" class="bg-surface-sunken p-0">{@render frame(args, "")}</div>
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: no title is cut; the row wraps before any label truncates. -->
<Story
  name="Largest text at 600px"
  args={{ selected: "needsYou", badgeCount: 12 }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await check(root, "needsYou", "Needs you, 12", "12");
    for (const t of root.querySelectorAll<HTMLElement>("[data-testid='destination-title']")) {
      if (t.scrollWidth > t.clientWidth) throw new Error(`"${t.textContent}" is clipped: ${t.scrollWidth} > ${t.clientWidth}`);
    }
    const nav = root.querySelector("nav")!;
    if (nav.scrollWidth > nav.clientWidth + 1) throw new Error("the row overflows");
  }}
>
  {#snippet template(args)}{@render frame(args, "zoom: 1.5;")}{/snippet}
</Story>
