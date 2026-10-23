<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { caughtUpInbox, FLEET_NOW, fleetNeedsYouInbox } from "$lib/fixtures";

  import NeedsYouList from "./NeedsYouList.svelte";

  const noop = () => {};
  const avatars = { get: () => null, markFailed: noop };

  const { Story } = defineMeta({
    title: "Inbox/NeedsYouList",
    component: NeedsYouList,
    args: { nowMs: FLEET_NOW, showsState: true, avatars, onSelect: noop },
  });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const text = (root: HTMLElement, id: string) =>
    root.querySelector(`[data-testid='${id}']`)?.textContent?.trim();

  async function fleetShown(root: HTMLElement) {
    await settled(() => root.querySelectorAll("button").length === 3, "three rows");
    const heads = [...root.querySelectorAll("h3")].map((h) => h.textContent?.replace(/\s+/g, " ").trim());
    if (heads.join("|") !== "Waiting on you 2|Invitations 1") throw new Error(`headers: ${heads}`);
    if (text(root, "needs-you-count-decisions") !== "2") throw new Error("two decisions");
    if (text(root, "needs-you-count-invitations") !== "1") throw new Error("one invitation");
    if (root.querySelector("[data-testid='caught-up']")) throw new Error("not caught up");
  }
</script>

<Story
  name="Needs you"
  args={{ inbox: fleetNeedsYouInbox }}
  play={async ({ canvasElement }) => fleetShown(canvasElement as HTMLElement)}
>
  {#snippet template(args)}
    <div class="flex h-[520px] w-[360px] flex-col bg-surface font-sans text-content"><NeedsYouList {...args} /></div>
  {/snippet}
</Story>

<Story
  name="Needs you, dark"
  args={{ inbox: fleetNeedsYouInbox }}
  play={async ({ canvasElement }) => fleetShown(canvasElement as HTMLElement)}
>
  {#snippet template(args)}
    <div data-appearance="dark" class="flex h-[520px] w-[360px] flex-col bg-surface font-sans text-content">
      <NeedsYouList {...args} />
    </div>
  {/snippet}
</Story>

<Story
  name="All caught up"
  args={{ inbox: caughtUpInbox }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='caught-up']"), "the caught-up state");
    const box = root.querySelector("[data-testid='caught-up']")!;
    if (box.getAttribute("role") !== "status") throw new Error("one status element");
    if (!box.textContent?.includes("You're all caught up")) throw new Error("title");
    if (!box.textContent?.includes("Decisions and invitations that need you land here.")) throw new Error("description");
    if (root.querySelectorAll("button").length !== 0) throw new Error("no rows");
    if (root.querySelector("h3[class*='text-meta']")) throw new Error("no section headers");
  }}
>
  {#snippet template(args)}
    <div class="flex h-[520px] w-[360px] flex-col bg-surface font-sans text-content"><NeedsYouList {...args} /></div>
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: names and previews truncate, nothing overflows. -->
<Story
  name="Largest text at 600px"
  args={{ inbox: fleetNeedsYouInbox }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await fleetShown(root);
    for (const b of root.querySelectorAll("button")) {
      if (b.scrollWidth > b.clientWidth + 1) throw new Error(`a row overflows: ${b.scrollWidth} > ${b.clientWidth}`);
    }
  }}
>
  {#snippet template(args)}
    <div class="flex h-[520px] flex-col bg-surface font-sans text-content" style="zoom: 1.5; width: 400px;">
      <NeedsYouList {...args} />
    </div>
  {/snippet}
</Story>
