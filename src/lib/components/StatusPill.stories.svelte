<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { RoomStatus } from "$lib/ipc";
  import StatusPill from "./StatusPill.svelte";

  const { Story } = defineMeta({
    title: "Room/StatusPill",
    component: StatusPill,
    parameters: {
      docs: {
        description: {
          component:
            "What an agent's room is doing, in the header's second line (iOS RoomScreen status pill). " +
            "Words from the core's roomStatusWord; Working pulses unless reduced motion.",
        },
      },
    },
  });

  const FIVE: { status: RoomStatus; word: string }[] = [
    { status: "working", word: "Working" },
    { status: "needsYou", word: "Needs you" },
    { status: "active", word: "Active" },
    { status: "idle", word: "Idle" },
    { status: "quiet", word: "Quiet" },
  ];

  const pills = (root: HTMLElement) => [...root.querySelectorAll<HTMLElement>("[data-testid='status-pill']")];

  async function fiveShown(canvasElement: HTMLElement) {
    const deadline = Date.now() + 3000;
    while (pills(canvasElement).length < 5) {
      if (Date.now() > deadline) throw new Error("the five pills never rendered");
      await new Promise((r) => setTimeout(r, 25));
    }
    const words = pills(canvasElement).map((p) => p.textContent?.trim());
    if (words.join(",") !== "Working,Needs you,Active,Idle,Quiet") throw new Error(`words: ${words}`);
    const needs = pills(canvasElement)[1]!;
    if (!needs.className.includes("bg-signal-soft")) throw new Error("Needs you sits on signal-soft");
    for (const [i, p] of pills(canvasElement).entries()) {
      if (i !== 1 && !p.className.includes("bg-surface-raised")) throw new Error("others sit on surface-raised");
    }
  }
</script>

{#snippet row()}
  <div class="flex flex-wrap items-center gap-3">
    {#each FIVE as p (p.status)}
      <StatusPill status={p.status} word={p.word} />
    {/each}
  </div>
{/snippet}

<Story name="Header statuses" play={async ({ canvasElement }) => fiveShown(canvasElement as HTMLElement)}>
  {#snippet template()}
    <div class="bg-surface-sunken p-4 font-sans text-content">{@render row()}</div>
  {/snippet}
</Story>

<Story name="Header statuses, dark" play={async ({ canvasElement }) => fiveShown(canvasElement as HTMLElement)}>
  {#snippet template()}
    <div data-appearance="dark" class="bg-surface-sunken p-4 font-sans text-content">{@render row()}</div>
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: one line, nothing overflows. -->
<Story
  name="Largest text at 600px"
  play={async ({ canvasElement }) => {
    await fiveShown(canvasElement as HTMLElement);
    const box = (canvasElement as HTMLElement).querySelector<HTMLElement>("[data-testid='frame']")!;
    if (box.scrollWidth > box.clientWidth + 1) throw new Error("the pills overflow");
    for (const p of pills(canvasElement as HTMLElement)) {
      if (p.getBoundingClientRect().height > 40) throw new Error("a pill wrapped to two lines");
    }
  }}
>
  {#snippet template()}
    <div data-testid="frame" class="bg-surface-sunken p-4 font-sans text-content" style="zoom: 1.5; width: 400px;">
      {@render row()}
    </div>
  {/snippet}
</Story>
