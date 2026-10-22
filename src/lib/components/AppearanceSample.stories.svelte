<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { DEFAULT_PREFS } from "$lib/appearance";

  import AppearanceSample from "./AppearanceSample.svelte";

  // Plain DOM, as DispatchCard.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the sample to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  const sample = (canvas: HTMLElement) =>
    settled(() => canvas.querySelector<HTMLElement>("[data-appearance]")) as Promise<HTMLElement>;
  const send = (frame: HTMLElement) =>
    frame.querySelector("button") as HTMLButtonElement;
  const fail = (msg: string): never => {
    throw new Error(msg);
  };

  const { Story } = defineMeta({
    title: "Account/AppearanceSample",
    component: AppearanceSample,
    parameters: {
      layout: "fullscreen",
      docs: {
        description: {
          component:
            "The tokens an appearance choice changes, drawn from the tokens " +
            "only. Black is true black (#000) and exists in dark mode only; " +
            "an accent recolours buttons, links and the soft highlight.",
        },
      },
    },
  });
</script>

<!-- The default: system mode, tinted, house accent (Violet). Light here. -->
<Story
  name="Light, house accent"
  play={async ({ canvasElement }) => {
    const frame = await sample(canvasElement as HTMLElement);
    if (frame.getAttribute("data-appearance") !== "light") fail("expected data-appearance=light");
    if (frame.hasAttribute("data-accent")) fail("the house accent sets no data-accent");
  }}
>
  {#snippet template()}
    <div class="p-6">
      <AppearanceSample prefs={{ ...DEFAULT_PREFS, mode: "light" }} />
    </div>
  {/snippet}
</Story>

<!-- Dark + Black: the ground is exactly #000000, not the tinted dark surface. -->
<Story
  name="Dark, Black"
  play={async ({ canvasElement }) => {
    const frame = await sample(canvasElement as HTMLElement);
    if (frame.getAttribute("data-appearance") !== "black") fail("expected data-appearance=black");
    const ground = getComputedStyle(frame).backgroundColor;
    if (ground !== "rgb(0, 0, 0)") fail(`Black must be true black, got ${ground}`);
  }}
>
  {#snippet template()}
    <div class="p-6">
      <AppearanceSample prefs={{ ...DEFAULT_PREFS, mode: "dark", darkStyle: "black" }} />
    </div>
  {/snippet}
</Story>

<!-- Dark + Tinted: the contrast to Black above; the ground is not #000. -->
<Story
  name="Dark, Tinted"
  play={async ({ canvasElement }) => {
    const frame = await sample(canvasElement as HTMLElement);
    if (getComputedStyle(frame).backgroundColor === "rgb(0, 0, 0)") fail("Tinted must not be black");
  }}
>
  {#snippet template()}
    <div class="p-6">
      <AppearanceSample prefs={{ ...DEFAULT_PREFS, mode: "dark" }} />
    </div>
  {/snippet}
</Story>

<!-- A non-default accent in light: the button takes the teal token. -->
<Story
  name="Teal accent, light"
  play={async ({ canvasElement }) => {
    const frame = await sample(canvasElement as HTMLElement);
    if (frame.getAttribute("data-accent") !== "teal") fail("expected data-accent=teal");
    const button = send(frame);
    const want = getComputedStyle(frame).getPropertyValue("--color-accent").trim();
    if (!want) fail("--color-accent is empty");
    const probe = document.createElement("i");
    probe.style.color = want;
    frame.appendChild(probe);
    const wantRgb = getComputedStyle(probe).color;
    probe.remove();
    if (getComputedStyle(button).backgroundColor !== wantRgb)
      fail(`button should be the accent ${wantRgb}`);
    // The violet default is #5b43d4; teal must differ from it.
    if (wantRgb === "rgb(91, 67, 212)") fail("accent did not change from the house violet");
  }}
>
  {#snippet template()}
    <div class="p-6">
      <AppearanceSample prefs={{ ...DEFAULT_PREFS, mode: "light", accent: "teal" }} />
    </div>
  {/snippet}
</Story>

<!-- Accent on Black: the pairing OLED users choose. -->
<Story
  name="Pink accent, Black"
  play={async ({ canvasElement }) => {
    const frame = await sample(canvasElement as HTMLElement);
    if (frame.getAttribute("data-appearance") !== "black" || frame.getAttribute("data-accent") !== "pink")
      fail("expected black + pink");
    if (getComputedStyle(frame).backgroundColor !== "rgb(0, 0, 0)") fail("ground must stay #000");
  }}
>
  {#snippet template()}
    <div class="p-6">
      <AppearanceSample prefs={{ ...DEFAULT_PREFS, mode: "dark", darkStyle: "black", accent: "pink" }} />
    </div>
  {/snippet}
</Story>
