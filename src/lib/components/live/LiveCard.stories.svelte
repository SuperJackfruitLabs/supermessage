<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import type { ComponentProps } from "svelte";

  import { longTurn, turnSummary } from "$lib/fixtures";
  import LiveCard from "./LiveCard.svelte";
  import { storyServices } from "./liveCardStoryServices";

  const { Story } = defineMeta({
    title: "Live/LiveCard",
    component: LiveCard,
    parameters: {
      docs: {
        description: {
          component:
            "One agent turn as a card inside the timeline (iOS LiveTurnView). Every story is a still frame: " +
            "no clock, a static ring for the spinner, elapsed time measured to the last event.",
        },
      },
    },
  });

  const answer =
    "Staging is green across all four ABIs.\n\nThe 16KB device is the one left to check before promoting.";

  const long = longTurn();
  const finished = longTurn(true);

  const settled = async (find: () => unknown, what: string, ms = 3000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  const text = (root: Element, sel: string) => root.querySelector(sel)?.textContent?.replace(/\s+/g, " ").trim();
  const card = (root: Element) => root.querySelector("[data-testid='live-card']") as HTMLElement;
  const has = (root: Element, needle: string) => card(root)?.textContent?.includes(needle);
</script>

<!-- A thought, a failed step and a running one, and the answer arriving. -->
<Story
  name="Mid-turn"
  args={{
    summary: { ...long.summary, answering: true },
    steps: long.steps,
    thought: long.thought,
    answer,
    name: "Atlas",
    still: true,
    services: storyServices,
  }}
  play={async ({ canvasElement }) => {
    await settled(() => has(canvasElement, "Staging is green"), "the streamed answer");
    await settled(() => text(canvasElement, "[data-testid='live-card-state']") === "Working", "the word Working");
    if (canvasElement.querySelector("[data-testid='live-card-state']")?.getAttribute("data-tone") !== "working")
      throw new Error("a running turn is the accent tone");
    await settled(() => has(canvasElement, "· 1m 04s"), "the elapsed time");
    // A tool step shows even with text.
    if (!has(canvasElement, "regenerate the token targets")) throw new Error("the running tool step is named");
    if (!has(canvasElement, "2 steps done")) throw new Error("2 steps done");
    if (!has(canvasElement, "write design/tokens.toml failed")) throw new Error("the failed step is named");
    if (!has(canvasElement, "All 4 steps")) throw new Error("the steps toggle");
    if (!has(canvasElement, "Reasoning")) throw new Error("the reasoning toggle");
  }}
/>

<!-- Thinking, with nothing else to show yet: the first thing a reader sees after sending. -->
<Story
  name="Thinking only"
  args={{
    summary: turnSummary({ hasThought: true, currentStepTitle: "Thinking" }),
    steps: [],
    thought: "Weighing whether the roster is already sorted.",
    answer: null,
    name: "Atlas",
    still: true,
    services: storyServices,
  }}
  play={async ({ canvasElement }) => {
    await settled(() => has(canvasElement, "Thinking"), "the current step");
    if (has(canvasElement, "steps done")) throw new Error("no steps, no step summary");
    if (has(canvasElement, "All ")) throw new Error("no steps toggle without steps");
  }}
/>

<!-- A plain reply streaming in: no thought, no tools. The text says it all, so no current step. -->
<Story
  name="Answering only"
  args={{
    summary: turnSummary({ answering: true, currentStepTitle: "Writing the answer" }),
    steps: [],
    thought: null,
    answer,
    name: "Atlas",
    still: true,
    services: storyServices,
  }}
  play={async ({ canvasElement }) => {
    await settled(() => has(canvasElement, "Staging is green"), "the answer");
    if (has(canvasElement, "Writing the answer")) throw new Error("the answer is showing; the step must go");
  }}
/>

<!-- iOS longTurn(): 1m 04s, two done, one failed, one running. -->
<Story
  name="Activity card"
  args={{ summary: long.summary, steps: long.steps, thought: long.thought, answer: null, name: "Atlas", still: true, services: storyServices }}
  play={async ({ canvasElement }) => {
    await settled(() => has(canvasElement, "· 1m 04s"), "1m 04s");
    // Open the steps: each has the core's word, and one with detail opens onto it.
    const toggle = [...canvasElement.querySelectorAll("summary")].find((s) => s.textContent?.trim() === "All 4 steps") as HTMLElement;
    toggle.click();
    await settled(() => has(canvasElement, "Running") && has(canvasElement, "Failed"), "the steps' words");
    const failedRow = [...canvasElement.querySelectorAll("details details summary")].find((s) => s.textContent?.includes("write design/tokens.toml")) as HTMLElement;
    failedRow.click();
    await settled(() => has(canvasElement, "permission denied") && has(canvasElement, "Touched"), "the step's detail");
  }}
/>

<!-- The record of that turn once it has finished: Done, how long, the failure still named, a step back. -->
<Story
  name="Activity card, finished"
  args={{ summary: finished.summary, steps: finished.steps, thought: finished.thought, answer: null, name: "Atlas", still: true, services: storyServices }}
  play={async ({ canvasElement }) => {
    await settled(() => text(canvasElement, "[data-testid='live-card-state']") === "Done", "Done");
    if (canvasElement.querySelector("[data-testid='live-card-state']")?.getAttribute("data-tone") !== "ok")
      throw new Error("Done is the ok tone");
    await settled(() => has(canvasElement, "· 1m 11s"), "elapsed to the last event");
    // The current step's row is the one accent-ruled element besides a running word.
    if (card(canvasElement).querySelector(".text-accent")) throw new Error("a finished turn names no current step");
    if (!card(canvasElement).className.includes("opacity-85")) throw new Error("a finished card steps back");
  }}
/>

<!-- A turn the core gave up on: its word, in neutral ink. Nothing says success. -->
<Story
  name="Activity card, timed out"
  args={{
    summary: { ...finished.summary, endedBy: "timedOut", recordLabel: null },
    steps: finished.steps,
    thought: finished.thought,
    answer: null,
    name: "Atlas",
    still: true,
    services: storyServices,
  }}
  play={async ({ canvasElement }) => {
    await settled(() => text(canvasElement, "[data-testid='live-card-state']") === "Stopped", "Stopped");
    const state = canvasElement.querySelector("[data-testid='live-card-state']")!;
    if (state.getAttribute("data-tone") !== "neutral") throw new Error("Stopped is neutral");
    if (state.className.includes("text-ok")) throw new Error("Stopped carries no success treatment");
  }}
/>

<!-- 150 % text in a 600px window: the name truncates, nothing overflows. -->
<Story
  name="Largest text at 600px"
  args={{
    summary: long.summary,
    steps: long.steps,
    thought: long.thought,
    answer,
    name: "Atlas, the assistant with an extraordinarily long working title",
    still: true,
    services: storyServices,
  }}
  play={async ({ canvasElement }) => {
    await settled(() => has(canvasElement, "Staging is green"), "the answer");
    const box = card(canvasElement);
    if (box.scrollWidth > box.clientWidth + 1) throw new Error("the card overflows its width");
    const name = box.querySelector("span.truncate") as HTMLElement;
    if (name.scrollWidth <= name.clientWidth) throw new Error("the long name should truncate");
  }}
>
  {#snippet template(args)}
    <div style="zoom: 1.5; width: 400px;"><LiveCard {...args} /></div>
  {/snippet}
</Story>

<!-- The reading column's measure, so a frame shows the card as the timeline lays it out. -->
{#snippet template(args: ComponentProps<typeof LiveCard>)}
  <div class="w-[640px] bg-surface p-4"><LiveCard {...args} /></div>
{/snippet}
