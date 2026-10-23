<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { Acknowledgement } from "$lib/ipc";
  import AcknowledgementDock from "./AcknowledgementDock.svelte";

  const { Story } = defineMeta({
    title: "Composer/AcknowledgementDock",
    component: AcknowledgementDock,
    parameters: { layout: "fullscreen" },
  });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const dock = (root: HTMLElement) => root.querySelector<HTMLElement>("[data-testid='ack-dock']");

  const sent: Acknowledgement = { kind: "sent", names: ["Atlas"], text: "Sent to Atlas" };
  const onIt: Acknowledgement = { kind: "onIt", names: ["Atlas"], text: "Atlas is on it…" };
  const several: Acknowledgement = {
    kind: "onIt",
    names: ["Atlas", "Quill", "Sage"],
    text: "Atlas, Quill and 1 other are on it…",
  };
  const long: Acknowledgement = {
    kind: "onIt",
    names: ["An agent with a very long display name indeed", "Another"],
    text: "An agent with a very long display name indeed and Another are on it…",
  };
</script>

{#snippet frame(ack: Acknowledgement | null, width = 420, zoom = 1)}
  <div class="bg-surface-sunken p-4 font-sans text-content" data-testid="frame" style="width: {width}px; zoom: {zoom}">
    <AcknowledgementDock {ack} />
  </div>
{/snippet}

<Story
  name="Sent"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => dock(root), "the dock");
    if (!dock(root)!.textContent?.includes("Sent to Atlas")) throw new Error("the core's text");
    if (!root.querySelector("[data-testid='ack-check']")?.getAttribute("class")?.includes("text-ok")) throw new Error("a check in ok");
    if (root.querySelector("[role='status'][aria-live='polite']") === null) throw new Error("a polite status");
  }}
>
  {#snippet template()}{@render frame(sent)}{/snippet}
</Story>

<Story
  name="On it"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => dock(root), "the dock");
    if (!dock(root)!.textContent?.includes("Atlas is on it…")) throw new Error("the core's text");
    if (!root.querySelector("[data-testid='ack-ellipsis']")?.getAttribute("class")?.includes("text-accent")) throw new Error("an ellipsis in accent");
    if (root.querySelector("[data-testid='ack-check']")) throw new Error("no check once it is on it");
  }}
>
  {#snippet template()}{@render frame(onIt)}{/snippet}
</Story>

<Story
  name="Several on it"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => dock(root), "the dock");
    if (!dock(root)!.textContent?.includes("Atlas, Quill and 1 other are on it…")) throw new Error("several named the core's way");
    if (dock(root)!.textContent?.includes("Sent to")) throw new Error("nobody is 'Sent to' with several agents");
  }}
>
  {#snippet template()}{@render frame(several)}{/snippet}
</Story>

<Story
  name="Nothing to say"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    if (dock(root) !== null) throw new Error("no dock for no acknowledgement");
    if (root.querySelector("[role='status']") === null) throw new Error("the live region stays mounted");
  }}
>
  {#snippet template()}{@render frame(null)}{/snippet}
</Story>

<!-- 150 % text in a 600px window: one line, truncated, nothing overflows. -->
<Story
  name="Largest text at 600px"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => dock(root), "the dock");
    const box = root.querySelector<HTMLElement>("[data-testid='frame']")!;
    if (box.scrollWidth > box.clientWidth + 1) throw new Error("the dock overflows");
    if (dock(root)!.getBoundingClientRect().height > 60) throw new Error("the dock wrapped");
    const text = dock(root)!.querySelector<HTMLElement>("span.truncate")!;
    if (text.scrollWidth <= text.clientWidth) throw new Error("the long text should truncate");
  }}
>
  {#snippet template()}{@render frame(long, 400, 1.5)}{/snippet}
</Story>
