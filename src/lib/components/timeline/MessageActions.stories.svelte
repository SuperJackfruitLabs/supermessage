<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { message } from "$lib/fixtures";

  import MessageActions from "./MessageActions.svelte";

  // Plain DOM, as DeleteConfirm.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the action pill to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  const base = { type: "item", key: "m1", continuesRun: false } as const;
  const own = {
    ...base,
    ...message({ id: "m1", eventId: "$e1", isOwn: true, editable: true, body: "Ship it." }),
    canReplyOrReact: true,
  };
  // A message still in flight: no event id, so only Copy is on offer.
  const sending = {
    ...base,
    ...message({ id: "m2", eventId: null, isOwn: true, body: "Ship it.", sendState: "notSentYet" }),
    canReplyOrReact: false,
  };

  // Someone else's settled message: Copy and Report.
  const theirs = {
    ...base,
    ...message({ id: "m3", eventId: "$e3", isOwn: false, sender: "@atlas:example.org", body: "Ship it." }),
    canReplyOrReact: true,
  };

  const noop = () => {};
  const args = {
    pickingReactionFor: null,
    onStartReply: noop,
    onToggleReaction: noop,
    onPickReaction: noop,
    onCopy: noop,
    onEdit: noop,
    onDelete: noop,
    onReport: noop,
  };

  const { Story } = defineMeta({
    title: "Timeline/MessageActions",
    component: MessageActions,
  });
</script>

{#snippet frame(row: typeof own)}
  <!-- The `relative group` block the pill is anchored to, as in the timeline. -->
  <div class="group relative mx-auto mt-8 h-44 w-[34rem] bg-surface font-sans text-body">
    <div class="rounded-control bg-surface-sunken px-3 py-2">Ship it.</div>
    <MessageActions {row} {...args} />
  </div>
{/snippet}

<Story
  name="Own message, focused"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const reply = (await settled(() =>
      [...root.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Reply"),
    )) as HTMLElement;
    reply.focus();
    await settled(() => (getComputedStyle(reply.parentElement!).opacity === "1" ? reply : null));
  }}
>
  {#snippet template()}
    {@render frame(own)}
  {/snippet}
</Story>

<Story
  name="More menu open"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const more = (await settled(() => root.querySelector('[aria-label="More actions"]'))) as HTMLElement;
    more.focus();
    more.click();
    await settled(() => root.querySelector("[role=menu]:not(.invisible)"));
  }}
>
  {#snippet template()}
    {@render frame(own)}
  {/snippet}
</Story>

<Story
  name="Copy only (sending)"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const more = (await settled(() => root.querySelector('[aria-label="More actions"]'))) as HTMLElement;
    more.focus();
    await settled(() => (getComputedStyle(more.closest(".absolute")!).opacity === "1" ? more : null));
  }}
>
  {#snippet template()}
    {@render frame(sending)}
  {/snippet}
</Story>

<!-- Someone else's message: "Report…" is last, after a separator, in danger. -->
<Story
  name="More menu with Report"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const more = (await settled(() => root.querySelector('[aria-label="More actions"]'))) as HTMLElement;
    more.focus();
    more.click();
    await settled(() => root.querySelector("[role=menu]:not(.invisible) [role=separator], [role=menu]:not(.invisible)"));
  }}
>
  {#snippet template()}
    {@render frame(theirs)}
  {/snippet}
</Story>
