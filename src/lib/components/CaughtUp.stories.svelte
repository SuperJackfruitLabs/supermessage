<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import CaughtUp from "./CaughtUp.svelte";

  const { Story } = defineMeta({ title: "Inbox/CaughtUp", component: CaughtUp });
</script>

<!--
  The seal bounces once, 250 ms after it appears, unless the reader asked for less motion. The
  class says which: present exactly when motion is allowed (the recorded frame is the reduced one).
-->
<Story
  name="Reduced motion"
  play={async ({ canvasElement }) => {
    const seal = (canvasElement as HTMLElement).querySelector("[data-testid='caught-up-seal']");
    if (!seal) throw new Error("the seal");
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const animated = seal.classList.contains("seal-bounce");
    if (reduced && animated) throw new Error("reduced motion: no animation class");
    if (!reduced && !animated) throw new Error("motion allowed: the bounce class");
    if (seal.getAttribute("width") !== "56") throw new Error("56px glyph");
  }}
>
  {#snippet template()}
    <div class="flex h-[360px] w-[360px] items-center justify-center bg-surface font-sans text-content"><CaughtUp /></div>
  {/snippet}
</Story>
