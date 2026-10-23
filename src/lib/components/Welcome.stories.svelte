<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import Welcome from "./Welcome.svelte";
  // The app's own mark: Storybook serves a different `/favicon.svg` of its own.
  import mark from "../../../static/favicon.svg?raw";

  const markSrc = `data:image/svg+xml;utf8,${encodeURIComponent(mark)}`;

  const noop = () => {};
  const { Story } = defineMeta({
    title: "Onboarding/Welcome",
    component: Welcome,
    parameters: { layout: "fullscreen" },
    args: { onContinue: noop, markSrc },
  });

  // iOS `WelcomeView.swift:86-96`.
  function check(root: HTMLElement, widthPx = 132) {
    const mark = root.querySelector<HTMLImageElement>("[data-testid='welcome-mark']")!;
    if (mark.getAttribute("alt") !== "supermessage") throw new Error("the mark's alt");
    // Layout width: the arrival animation scales the box (0.94 to 1), which `getBoundingClientRect` sees.
    if (mark.offsetWidth !== widthPx) throw new Error(`the mark is ${mark.offsetWidth}px, not ${widthPx}`);
    // Scale only, never opacity: the mark is fully there on the very first frame.
    if (getComputedStyle(mark).opacity !== "1") throw new Error(`the mark fades: opacity ${getComputedStyle(mark).opacity}`);
    const text = root.textContent ?? "";
    for (const words of [
      "supermessage",
      "Chat with your team and your agents — and approve their work without leaving the conversation.",
      "Sign in",
    ]) {
      if (!text.includes(words)) throw new Error(`missing: ${words}`);
    }
    const button = root.querySelector<HTMLElement>("[data-testid='welcome-continue']")!;
    if (button.getBoundingClientRect().width < 200) throw new Error("Sign in is full width");
  }
</script>

<Story name="Welcome" play={async ({ canvasElement }) => check(canvasElement as HTMLElement)} />

<Story
  name="Welcome, dark"
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement)}
>
  {#snippet template(args)}
    <div data-appearance="dark" class="bg-surface"><Welcome {...args} /></div>
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: the same screen, nothing cut, the button reachable. -->
<Story
  name="Largest text at 600px"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const main = root.querySelector("main")!;
    if (main.scrollWidth > main.clientWidth + 1) throw new Error("the screen overflows sideways");
    if (!root.querySelector("[data-testid='welcome-continue']")) throw new Error("Sign in is there");
  }}
>
  {#snippet template(args)}
    <div style="width: 400px; zoom: 1.5;"><Welcome {...args} /></div>
  {/snippet}
</Story>
