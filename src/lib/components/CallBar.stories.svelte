<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { CallView } from "$lib/ipc";
  import CallBar from "./CallBar.svelte";

  const START = Date.UTC(2026, 10, 27, 12, 0, 0);
  const call = (over: Partial<CallView>): CallView => ({
    roomId: "!r:example.org",
    agentUserId: "@agent_ada:example.org",
    agentName: "✦ Ada — Researcher",
    phase: "live",
    agentState: "listening",
    startedAtMs: START,
    muted: false,
    ...over,
  });
  const noop = () => {};

  const { Story } = defineMeta({
    title: "Room/CallBar",
    component: CallBar,
    parameters: {
      layout: "fullscreen",
      docs: {
        description: {
          component:
            "The bar while a call with an agent runs: who, how long, the agent's own state " +
            "(superdial.state, from the agent's participant only), Mute and Hang up.",
        },
      },
    },
    args: { onMute: noop, onHangUp: noop, nowMs: START + 161_000 },
  });

  const stateIs = async (root: HTMLElement, word: string) => {
    const deadline = Date.now() + 2000;
    for (;;) {
      const got = root.querySelector("[data-testid='call-state']")?.textContent?.trim();
      if (got === word) return;
      if (Date.now() > deadline) throw new Error(`state: ${got}, wanted ${word}`);
      await new Promise((r) => setTimeout(r, 16));
    }
  };
</script>

<!-- Pressed Call: asking for a token, publishing membership; no clock yet, Mute not offered. -->
<Story
  name="Connecting"
  args={{ call: call({ phase: "connecting", agentState: null, startedAtMs: null }) }}
  play={async ({ canvasElement }) => stateIs(canvasElement as HTMLElement, "Connecting…")}
/>

<Story
  name="Listening"
  args={{ call: call({ agentState: "listening" }) }}
  play={async ({ canvasElement }) => stateIs(canvasElement as HTMLElement, "Listening")}
/>

<!-- The long wait the bar exists for: the agent's turn is running in the room's session. -->
<Story
  name="Thinking"
  args={{ call: call({ agentState: "thinking" }) }}
  play={async ({ canvasElement }) => stateIs(canvasElement as HTMLElement, "Thinking…")}
/>

<Story
  name="Speaking, muted, dark"
  args={{ call: call({ agentState: "speaking", muted: true }) }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await stateIs(root, "Speaking");
    const mute = root.querySelector("button[aria-pressed]");
    if (mute?.getAttribute("aria-pressed") !== "true") throw new Error("Mute is not pressed");
  }}
>
  {#snippet template(args)}
    <div data-appearance="dark" class="bg-surface text-content"><CallBar {...args} /></div>
  {/snippet}
</Story>
