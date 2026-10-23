<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { RoomStatus } from "$lib/ipc";
  import RoomHeaderLine from "./RoomHeaderLine.svelte";

  const { Story } = defineMeta({
    title: "Room/Header second line",
    component: RoomHeaderLine,
  });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
</script>

{#snippet header(status: RoomStatus | null, word: string | null, members: number | null)}
  <div class="flex w-[640px] items-center gap-2 border-b border-border bg-surface-sunken px-4 py-2 font-sans">
    <span class="flex h-6 w-6 items-center justify-center rounded-pill bg-surface-raised text-ui font-medium text-content" aria-hidden="true">A</span>
    <div class="flex min-w-0 flex-col items-start">
      <div class="flex items-center gap-2">
        <span class="truncate text-ui-lg text-content">Atlas</span>
        <span class="rounded-pill border border-border px-2 py-0.5 text-label text-content-muted">OPENCLAW @ ASHRAM</span>
      </div>
      <RoomHeaderLine {status} {word} {members} />
    </div>
  </div>
{/snippet}

<!-- An agent's room with a live turn: the pill, not the member count. -->
<Story
  name="An agent's room, working"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='status-pill']"), "the pill");
    const pill = root.querySelector<HTMLElement>("[data-testid='status-pill']")!;
    if (pill.textContent?.trim() !== "Working") throw new Error("the pill says Working");
    if (root.querySelector("[data-testid='member-count']")) throw new Error("no member count beside a pill");
    const dot = pill.firstElementChild as HTMLElement;
    const motion = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const animated = getComputedStyle(dot).animationName !== "none";
    if (motion !== animated) throw new Error(`Working pulses exactly when motion is allowed (motion ${motion}, animated ${animated})`);
  }}
>
  {#snippet template()}{@render header("working", "Working", 2)}{/snippet}
</Story>

<!-- A room of people: "3 members", no pill. -->
<Story
  name="A room of people"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='member-count']"), "the member count");
    if (root.querySelector("[data-testid='member-count']")!.textContent?.trim() !== "3 members") throw new Error("3 members");
    if (root.querySelector("[data-testid='status-pill']")) throw new Error("a room of people has no pill");
  }}
>
  {#snippet template()}{@render header(null, null, 3)}{/snippet}
</Story>

<Story
  name="One member"
  play={async ({ canvasElement }) => {
    await settled(() => (canvasElement as HTMLElement).textContent?.includes("1 member"), "1 member");
    if ((canvasElement as HTMLElement).textContent?.includes("1 members")) throw new Error("singular");
  }}
>
  {#snippet template()}{@render header(null, null, 1)}{/snippet}
</Story>

<!-- Neither a status nor a count yet: the line takes no height. -->
<Story
  name="Nothing to say yet"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("span"), "the header");
    if (root.querySelector("[data-testid='status-pill'], [data-testid='member-count']")) throw new Error("the line should be empty");
  }}
>
  {#snippet template()}{@render header(null, null, null)}{/snippet}
</Story>
