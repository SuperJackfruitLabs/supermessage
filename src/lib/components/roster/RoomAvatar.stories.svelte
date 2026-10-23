<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { agentAvatarVariantsFixture } from "$lib/fixtures";
  import type { AgentState } from "$lib/ipc";
  import RoomAvatar from "./RoomAvatar.svelte";

  const { Story } = defineMeta({
    title: "Roster/RoomAvatar",
    component: RoomAvatar,
    parameters: {
      docs: {
        description: {
          component:
            "A room's face (iOS RoomAvatar). An agent is a rounded square in the core's tile colours with a " +
            "sparkle badge; people are a circle. The state dot sits bottom-right; Quiet draws none.",
        },
      },
    },
  });

  const INITIALS = ["A", "K", "M", "R", "S", "T"];
  const STATES: AgentState[] = ["needsYou", "active", "idle", "quiet"];

  const avatars = (root: HTMLElement) => [...root.querySelectorAll<HTMLElement>("[data-testid='room-avatar']")];
  async function ready(root: HTMLElement, n: number) {
    const deadline = Date.now() + 3000;
    while (avatars(root).length < n) {
      if (Date.now() > deadline) throw new Error("avatars never rendered");
      await new Promise((r) => setTimeout(r, 25));
    }
  }
  async function checkAvatars(root: HTMLElement) {
    await ready(root, 6 + 4 + 1);
    const all = avatars(root);
    const tiles = all.slice(0, 6);
    for (const [i, t] of tiles.entries()) {
      if (!t.querySelector("[data-testid='agent-badge']")) throw new Error(`tile ${i} lacks the agent badge`);
      const face = t.querySelector<HTMLElement>("[data-variant]")!;
      if (face.dataset.variant !== String(i)) throw new Error("variants are in order");
      if (Math.abs(parseFloat(face.style.borderRadius) - 40 * 0.28) > 0.01) throw new Error("tile corner is 28 % of the size");
    }
    const states = all.slice(6, 10);
    const dots = states.map((s) => s.querySelector("[data-testid='state-dot']"));
    if (dots.map((d) => (d ? 1 : 0)).join("") !== "1110") throw new Error("Needs you, Active and Idle draw a dot; Quiet draws none");
    if (!states[0]!.querySelector("[data-testid='state-dot']")!.className.includes("bg-signal")) throw new Error("Needs you is signal");
    if (!states[1]!.querySelector("[data-testid='state-dot']")!.className.includes("bg-ok")) throw new Error("Active is ok");
    if (states[0]!.getAttribute("aria-label") !== "Agent, needs you") throw new Error("label says Agent and the state");
    if (states[3]!.getAttribute("aria-label") !== "Agent") throw new Error("quiet says only Agent");
    const person = all[10]!;
    if (person.querySelector("[data-testid='agent-badge']") || person.querySelector("[data-testid='state-dot']")) throw new Error("a person has neither badge nor dot");
    if (!person.querySelector(".rounded-pill")) throw new Error("a person is a circle");
  }
</script>

{#snippet sheet()}
  <div class="flex flex-col gap-6 bg-surface p-6 font-sans text-content">
    <div class="flex gap-4">
      {#each agentAvatarVariantsFixture as style, i (style.variant)}
        <RoomAvatar initial={INITIALS[i]!} avatarUrl={null} describesAgent={true} {style} state={"idle"} />
      {/each}
    </div>
    <div class="flex gap-4">
      {#each STATES as state (state)}
        <RoomAvatar initial="A" avatarUrl={null} describesAgent={true} style={agentAvatarVariantsFixture[0]!} {state} />
      {/each}
      <RoomAvatar initial="M" avatarUrl={null} describesAgent={false} style={null} state={null} />
    </div>
  </div>
{/snippet}

<Story name="Avatars" play={async ({ canvasElement }) => checkAvatars(canvasElement as HTMLElement)}>
  {#snippet template()}{@render sheet()}{/snippet}
</Story>

<Story name="Avatars, dark" play={async ({ canvasElement }) => checkAvatars(canvasElement as HTMLElement)}>
  {#snippet template()}<div data-appearance="dark">{@render sheet()}</div>{/snippet}
</Story>
