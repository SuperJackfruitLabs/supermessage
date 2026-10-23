<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import TypingIndicator from "./TypingIndicator.svelte";

  const { Story } = defineMeta({
    title: "Chrome/TypingIndicator",
    component: TypingIndicator,
    parameters: { layout: "fullscreen" },
  });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const pill = (root: HTMLElement) => root.querySelector<HTMLElement>("[data-testid='typing-pill']");
</script>

<!-- The pill sits on the page's ground, in the composer's column. -->
{#snippet onGround(text: string | null)}
  <div class="bg-surface p-4 font-sans text-content" data-testid="frame" style="width: 420px">
    <TypingIndicator {text} />
  </div>
{/snippet}

<Story
  name="A person typing"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => pill(root), "the pill");
    const p = pill(root)!;
    if (!p.className.includes("rounded-pill")) throw new Error("a pill");
    if (!p.textContent?.includes("Atlas is typing…")) throw new Error("the core's line");
    if (root.querySelector("[aria-live='polite']") === null) throw new Error("a polite live region");
  }}
>
  {#snippet template()}{@render onGround("Atlas is typing…")}{/snippet}
</Story>

<Story name="One person">
  {#snippet template()}{@render onGround("Atlas is typing…")}{/snippet}
</Story>
<Story name="Two people">
  {#snippet template()}{@render onGround("Atlas and Krishna are typing…")}{/snippet}
</Story>

<!-- Enough that the line must summarise rather than list every name. -->
<Story name="Many people">
  {#snippet template()}{@render onGround("Atlas, Krishna and 2 others are typing…")}{/snippet}
</Story>

<!--
  No cached display name, so the line falls back to the raw id — which is
  server-controlled arbitrary text, and long. This is the `truncate` case.
-->
<Story name="Unnamed sender">
  {#snippet template()}{@render onGround("9247e5… is typing…")}{/snippet}
</Story>

<!-- A display name is server-controlled and arrives untouched: one line, truncated. -->
<Story
  name="Overlong name"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => pill(root), "the pill");
    const frame = root.querySelector<HTMLElement>("[data-testid='frame']")!;
    if (pill(root)!.getBoundingClientRect().height > 40) throw new Error("the pill wrapped");
    if (frame.scrollWidth > frame.clientWidth + 1) throw new Error("the pill overflows");
  }}
>
  {#snippet template()}{@render onGround("Extremely Verbose Agent Of Consi… is typing…")}{/snippet}
</Story>

<!-- Nobody typing: the pill is not drawn and takes no room. -->
<Story
  name="Nobody typing"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    if (pill(root) !== null) throw new Error("no pill when nobody types");
  }}
>
  {#snippet template()}{@render onGround(null)}{/snippet}
</Story>
