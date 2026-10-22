<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { RoomCast } from "$lib/ipc";
  import NameEffectHarness from "./NameEffectHarness.svelte";

  const cast: RoomCast = {
    counterpart: { userId: "@k:example.org", name: "Krishna" },
    people: [{ userId: "@k:example.org", name: "Krishna" }],
    agentIds: ["@k:example.org"],
  } as unknown as RoomCast;

  const { Story } = defineMeta({ title: "Live/NameEffect", component: NameEffectHarness });
</script>

<!-- Selecting a room runs the name effect once and shows the cast's name. -->
<Story
  name="Names the agent without looping"
  args={{ roomId: "!k:example.org", cast }}
  play={async ({ canvasElement }) => {
    const deadline = Date.now() + 2000;
    const name = () => canvasElement.querySelector("[data-testid='name']")?.textContent;
    while (name() !== "Krishna") {
      if (Date.now() > deadline) throw new Error(`the name was ${name()}, not Krishna`);
      await new Promise((r) => setTimeout(r, 16));
    }
    // Past the first run, any more is the effect re-triggering itself.
    await new Promise((r) => setTimeout(r, 200));
    const runs = (globalThis as unknown as { __nameEffectRuns: number }).__nameEffectRuns;
    if (runs !== 1) throw new Error(`the name effect ran ${runs} times, not once`);
  }}
/>
