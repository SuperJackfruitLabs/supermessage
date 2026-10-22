<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import type { ComponentProps } from "svelte";

  import { longTurn } from "$lib/fixtures";
  import WhatIDid from "./WhatIDid.svelte";

  const { Story } = defineMeta({
    title: "Live/WhatIDid",
    component: WhatIDid,
    parameters: {
      docs: {
        description: {
          component:
            "A finished turn's record, collapsed between the agent's name and its answer (iOS WhatIDidFooter).",
        },
      },
    },
  });

  const turn = longTurn(true);
  const args = { label: "Thought for 12s · 4 steps", thought: turn.thought, steps: turn.steps };

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 16));
    }
  };
</script>

<Story
  name="Collapsed"
  {args}
  play={async ({ canvasElement }) => {
    const box = (await settled(() => canvasElement.querySelector("[data-testid='what-i-did']"), "the record")) as HTMLDetailsElement;
    if (box.open) throw new Error("the record starts collapsed");
    if (box.querySelector("summary")?.textContent?.trim() !== "Thought for 12s · 4 steps") throw new Error("the core's label");
    if (box.getBoundingClientRect().width > 600) throw new Error("at most 600px wide");
  }}
/>

<Story
  name="Open"
  {args}
  play={async ({ canvasElement }) => {
    const box = (await settled(() => canvasElement.querySelector("[data-testid='what-i-did']"), "the record")) as HTMLDetailsElement;
    (box.querySelector("summary") as HTMLElement).click();
    await settled(() => box.open && box.textContent?.includes("The failing test is in the token emitter."), "the thought");
    for (const want of ["read scripts/tokens/model.py", "write design/tokens.toml", "Failed", "Done"])
      if (!box.textContent?.includes(want)) throw new Error(`missing ${want}`);
    // The failed step's word is in the danger ink.
    const failed = [...box.querySelectorAll("span")].find((s) => s.textContent === "Failed");
    if (!failed?.className.includes("text-danger")) throw new Error("a failed step reads in danger");
    // Let the fade finish so the recorded frame is the settled one.
    await new Promise((r) => setTimeout(r, 400));
  }}
/>

<!-- The sheet the record sits on in the timeline. -->
{#snippet template(args: ComponentProps<typeof WhatIDid>)}
  <div class="w-[640px] bg-surface p-4"><WhatIDid {...args} /></div>
{/snippet}
