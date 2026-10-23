<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { agentAvatarVariantsFixture, firstRunScriptFixture } from "$lib/fixtures";
  import type { FirstRunStep } from "$lib/ipc";

  import FirstRunDemo from "./FirstRunDemo.svelte";

  const noop = () => {};
  // The core's table (`core::first_run`), standing in: any answer on the card approves.
  const answer = async (s: FirstRunStep): Promise<FirstRunStep | null> => (s === "waiting" ? "approved" : null);
  const advance = async (s: FirstRunStep): Promise<FirstRunStep> => (s === "greeting" ? "asking" : s === "asking" ? "waiting" : s);
  // A pause that never ends: each story stands at one step.
  const never = () => new Promise<void>(() => {});

  const coreAt = (initialStep: FirstRunStep) => ({
    script: firstRunScriptFixture,
    initialStep,
    advance,
    answer,
    sleep: never,
    agentAvatarStyle: async () => agentAvatarVariantsFixture[0]!,
  });

  const { Story } = defineMeta({
    title: "Onboarding/FirstRunDemo",
    component: FirstRunDemo,
    parameters: { layout: "fullscreen" },
    args: { onFinish: noop },
  });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const q = (root: HTMLElement, sel: string) => root.querySelector<HTMLElement>(sel);
  const text = (root: HTMLElement) => root.textContent ?? "";

  async function header(root: HTMLElement) {
    await settled(() => q(root, "[data-testid='first-run']")?.querySelector("h2"), "the header");
    if (q(root, "h2")!.textContent?.trim() !== "Atlas") throw new Error("the agent's name");
    if (!text(root).includes(firstRunScriptFixture.banner)) throw new Error("the banner");
  }
</script>

<Story
  name="Greeting"
  args={{ core: coreAt("greeting") }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await header(root);
    const typing = q(root, "[data-testid='first-run-typing']");
    if (typing?.getAttribute("aria-label") !== "Atlas is typing") throw new Error("the typing bubble");
    if (q(root, "[data-testid='first-run-message']")) throw new Error("no message yet");
    if (q(root, "[data-testid='first-run-skip']")?.textContent?.trim() !== "Skip") throw new Error("Skip is there");
  }}
/>

<Story
  name="Waiting on the card"
  args={{ core: coreAt("waiting") }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await header(root);
    await settled(() => q(root, "[data-testid='first-run-message']"), "the message");
    if (!text(root).includes(firstRunScriptFixture.greeting)) throw new Error("the greeting");
    if (!text(root).includes("Agent")) throw new Error("the Agent tag");
    await settled(() => [...root.querySelectorAll("button")].some((b) => b.textContent?.includes("Approve")), "the Approve button");
    if (!text(root).includes("Can I edit docs/release-notes.md?")) throw new Error("the request");
    if (q(root, "[data-testid='first-run-finish']")) throw new Error("no Get started before the answer");
  }}
/>

<Story
  name="Approved"
  args={{ core: coreAt("waiting") }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await header(root);
    await settled(() => [...root.querySelectorAll("button")].find((b) => b.textContent?.includes("Approve")), "Approve");
    [...root.querySelectorAll("button")].find((b) => b.textContent?.includes("Approve"))!.click();
    await settled(() => q(root, "[data-testid='first-run-finish']"), "Get started");
    if (q(root, "[data-testid='first-run-skip']")) throw new Error("Skip is gone once approved");
    const t = text(root);
    if (!t.includes("That's it — approvals from chat.")) throw new Error("the title");
    if (!t.includes("When an agent needs you, it asks in its room, and the Needs you tab keeps the list.")) throw new Error("the body");
    if (q(root, "[data-testid='first-run-finish']")!.textContent?.trim() !== "Get started") throw new Error("the button");
    if (q(root, "[data-testid='first-run-check']")?.getAttribute("aria-hidden") !== "true") throw new Error("the check is decoration");
  }}
/>

<Story
  name="Approved, dark"
  args={{ core: coreAt("waiting") }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => [...root.querySelectorAll("button")].find((b) => b.textContent?.includes("Approve")), "Approve");
    [...root.querySelectorAll("button")].find((b) => b.textContent?.includes("Approve"))!.click();
    await settled(() => q(root, "[data-testid='first-run-finish']"), "Get started");
  }}
>
  {#snippet template(args)}
    <div data-appearance="dark"><FirstRunDemo {...args} /></div>
  {/snippet}
</Story>

<Story
  name="Largest text at 600px"
  args={{ core: coreAt("waiting") }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => [...root.querySelectorAll("button")].find((b) => b.textContent?.includes("Approve")), "Approve");
    const card = q(root, "[data-testid='first-run']")!;
    if (card.scrollWidth > card.clientWidth + 1) throw new Error("the demo overflows sideways");
    const edge = card.getBoundingClientRect().right;
    for (const el of root.querySelectorAll<HTMLElement>("[data-testid='first-run-message'], button")) {
      if (el.getBoundingClientRect().right > edge + 1) throw new Error("something runs past the window's edge");
    }
  }}
>
  {#snippet template(args)}
    <div style="zoom: 1.5;"><FirstRunDemo {...args} /></div>
  {/snippet}
</Story>
