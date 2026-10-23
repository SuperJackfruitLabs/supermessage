<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { FLEET_NOW, fleetAgentDirectory, fleetStatuses } from "$lib/fixtures";

  import AgentsList from "./AgentsList.svelte";

  const noop = () => {};
  const avatars = { get: () => null, markFailed: noop };
  const statuses = { statusOf: (id: string) => fleetStatuses[id] ?? null };

  const { Story } = defineMeta({
    title: "Agents/AgentsList",
    component: AgentsList,
    args: { nowMs: FLEET_NOW, statuses, avatars, onSelect: noop },
  });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const rows = (root: HTMLElement) => [...root.querySelectorAll<HTMLElement>("[data-testid='agent-row']")];

  async function directoryShown(root: HTMLElement) {
    await settled(() => rows(root).length === 4, "four agents");
    const names = rows(root).map((r) => r.querySelector("span.truncate")?.textContent?.trim());
    if (names.join(",") !== "Superpipeline,Atlas,Strategy Sam,Quill") throw new Error(`order: ${names}`);
    const words = rows(root).map((r) => r.querySelector("[data-testid='status-pill']")?.textContent?.trim());
    if (words.join(",") !== "Needs you,Working,Needs you,Idle") throw new Error(`statuses: ${words}`);
    for (const r of rows(root)) {
      const w = r.querySelector<HTMLElement>("[data-testid='room-avatar']")!.style.width;
      if (w !== "44px") throw new Error(`avatar is 44px, not ${w}`);
    }
    if (!rows(root)[0]!.textContent?.includes("3m")) throw new Error("the relative time");
  }
</script>

<Story
  name="Agents"
  args={{ agents: fleetAgentDirectory }}
  play={async ({ canvasElement }) => directoryShown(canvasElement as HTMLElement)}
>
  {#snippet template(args)}
    <div class="flex h-[420px] w-[360px] flex-col bg-surface font-sans text-content"><AgentsList {...args} /></div>
  {/snippet}
</Story>

<Story
  name="Agents, dark"
  args={{ agents: fleetAgentDirectory }}
  play={async ({ canvasElement }) => directoryShown(canvasElement as HTMLElement)}
>
  {#snippet template(args)}
    <div data-appearance="dark" class="flex h-[420px] w-[360px] flex-col bg-surface font-sans text-content">
      <AgentsList {...args} />
    </div>
  {/snippet}
</Story>

<Story
  name="No agents yet"
  args={{ agents: [] }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='no-agents']"), "the empty state");
    const text = root.querySelector("[data-testid='no-agents']")!.textContent ?? "";
    if (!text.includes("No agents yet") || !text.includes("Rooms with an agent in them appear here.")) {
      throw new Error(`copy: ${text}`);
    }
    if (rows(root).length !== 0) throw new Error("no rows");
  }}
>
  {#snippet template(args)}
    <div class="flex h-[420px] w-[360px] flex-col bg-surface font-sans text-content"><AgentsList {...args} /></div>
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: names and roles truncate, nothing overflows. -->
<Story
  name="Largest text at 600px"
  args={{ agents: fleetAgentDirectory }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await directoryShown(root);
    for (const r of rows(root)) {
      if (r.scrollWidth > r.clientWidth + 1) throw new Error(`a row overflows: ${r.scrollWidth} > ${r.clientWidth}`);
    }
  }}
>
  {#snippet template(args)}
    <div class="flex h-[420px] flex-col bg-surface font-sans text-content" style="zoom: 1.5; width: 400px;">
      <AgentsList {...args} />
    </div>
  {/snippet}
</Story>
