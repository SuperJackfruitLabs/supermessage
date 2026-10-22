<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import ReplyQuote from "./ReplyQuote.svelte";

  const available = {
    state: "available" as const,
    sender: "Atlas",
    excerpt: "Staging is green across all four ABIs.",
    label: null,
  };

  // Plain DOM, as DispatchCard.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the quote");
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  let jumps = 0;

  const { Story } = defineMeta({
    title: "Timeline/ReplyQuote",
    component: ReplyQuote,
    parameters: {
      docs: {
        description: {
          component:
            "The quote above a reply. With `onJump` it is a button that shows " +
            "the original message; without it (the parent is not loaded) it " +
            "is inert.",
        },
      },
    },
  });
</script>

<!-- A loaded parent: a real button, reachable by Tab, with a visible focus ring. -->
<Story
  name="Jumpable quote"
  play={async ({ canvasElement }) => {
    const button = (await settled(() => canvasElement.querySelector("button"))) as HTMLButtonElement;
    if (button.getAttribute("aria-description") !== "Shows the original message") {
      throw new Error("the quote should carry the hint");
    }
    button.focus();
    if (document.activeElement !== button) throw new Error("the quote should take focus");
    button.click();
    if (jumps !== 1) throw new Error(`one click should jump once, got ${jumps}`);
  }}
>
  {#snippet template()}
    <div class="w-[420px] bg-surface p-4 font-sans text-body">
      <ReplyQuote quote={available} isOwn={false} onJump={() => (jumps += 1)} />
      <p>Ready to promote.</p>
    </div>
  {/snippet}
</Story>

<!-- A parent that has not loaded: the quote reads as before and does nothing. -->
<Story
  name="Unloaded parent"
  play={async ({ canvasElement }) => {
    await settled(() => canvasElement.querySelector("div"));
    if (canvasElement.querySelector("button")) throw new Error("an unloaded parent must not be a button");
  }}
>
  {#snippet template()}
    <div class="w-[420px] bg-surface p-4 font-sans text-body">
      <ReplyQuote quote={{ state: "unavailable" }} isOwn={false} />
      <p>Ready to promote.</p>
    </div>
  {/snippet}
</Story>
