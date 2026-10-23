<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import MentionMenu from "./MentionMenu.svelte";

  const m = (userId: string, name: string | null, runtime: { harness: string; host: string } | null = null) => ({
    userId,
    displayName: name ?? userId,
    isAgent: runtime !== null,
    initial: (name ?? userId.replace("@", ""))[0]!.toUpperCase(),
    runtime,
  });
  const ashram = { harness: "Claude Code", host: "Ashram" };
  const mac = { harness: "Codex", host: "Mac mini" };

  const one = [m("@atlas:id.agentpod.dev", "Atlas", ashram)];
  const many = [
    m("@atlas:id.agentpod.dev", "Atlas", ashram),
    m("@quill:id.agentpod.dev", "Writer Quill", mac),
    m("@chotu:id.agentpod.dev", "Super Chotu", ashram),
    m("@krishna:id.agentpod.dev", "Krishna"),
    m("@sam:id.agentpod.dev", "Strategy Sam"),
    m("@9247e5a1b3c4:id.agentpod.dev", null),
  ];

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const options = (root: HTMLElement) => [...root.querySelectorAll<HTMLElement>("[role='option']")];

  const { Story } = defineMeta({
    title: "Composer/MentionMenu",
    component: MentionMenu,
    args: { onPick: () => {} },
    parameters: {
      // The preview's global `layout: "centered"` wraps each story in a
      // shrink-to-fit box, so anything that sizes itself from its parent gets
      // a parent of zero width. The composer row here did exactly that — and
      // the collapse read as the menu being mispositioned, because a menu
      // anchored to a zero-width row does look mispositioned.
      layout: "fullscreen",
      docs: {
        description: {
          component:
            "Opens upwards, because the composer already sits at the bottom " +
            "of the window. Absolute, so it cannot push the timeline as it " +
            "grows — the reading surface must not move while somebody types " +
            "a name.",
        },
      },
    },
  });
</script>

<!--
  The menu needs the composer's container, not just the page.

  All four of these stories rendered as an empty canvas, and byte-identically
  so — which is how the screenshot gate found them. `MentionMenu` is
  `absolute bottom-full`, and with no positioned ancestor that resolves
  against the initial containing block: `bottom: 100%` puts the whole list
  above the top of the viewport. It was never broken, it was off-screen, and
  a catalogue nobody screenshots cannot tell the difference.

  The wrapper is the composer's own row — `relative`, `max-w-[72ch]`,
  `items-end` — so what the story shows is what `Composer.svelte` produces
  rather than an arrangement invented to make the picture work.

  Heights are inline rather than `h-[22rem]`: Tailwind v4's automatic source
  detection does not scan `.stories.svelte`, so an arbitrary-value class used
  only in a story is never generated, and the wrapper collapses to nothing —
  which looks exactly like the bug being fixed here. Classes that also appear
  in `Composer.svelte` are generated and safe to use.
-->
{#snippet inComposer(matches: ReturnType<typeof m>[], activeIndex: number, width = '72ch', zoom = 1)}
  <div data-testid="frame" style="height: 26rem; display: flex; align-items: flex-end; justify-content: center; padding: 1rem; zoom: {zoom}">
    <!-- `flex: none` keeps the row at its stated width. Its only in-flow
         content is nothing — the menu is absolutely positioned and
         contributes no width — so a flex item free to shrink shrinks to
         zero, and the menu hangs off a zero-width anchor. -->
    <div
      class="relative rounded-control border border-border bg-surface"
      style="height: 2.75rem; width: 100%; max-width: {width}; flex: none"
    >
      <MentionMenu {matches} {activeIndex} onPick={() => {}} />
    </div>
  </div>
{/snippet}

<Story name="One candidate">
  {#snippet template()}{@render inComposer(one, 0)}{/snippet}
</Story>

<Story name="Several candidates">
  {#snippet template()}{@render inComposer(many, 0)}{/snippet}
</Story>

<!-- The keyboard cursor further down, which is the only thing that marks
     a row as selected. -->
<Story name="Cursor moved">
  {#snippet template()}{@render inComposer(many, 3)}{/snippet}
</Story>

<!-- A candidate with no display name falls back to its matrix id. -->
<Story name="Unnamed candidate">
  {#snippet template()}{@render inComposer([m("@9247e5a1b3c4:id.agentpod.dev", null)], 0)}{/snippet}
</Story>

<!-- The room's cast: two agents with where they run and the tag, then a person. -->
<Story
  name="From the room's cast"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => options(root).length === 6, "six rows");
    const [atlas, quill, , krishna] = options(root);
    if (atlas!.getAttribute("aria-label") !== "Atlas, agent") throw new Error("an agent's name says agent");
    if (!atlas!.textContent?.includes("Claude Code on Ashram")) throw new Error("the runtime line");
    if (!quill!.textContent?.includes("Codex on Mac mini")) throw new Error("the second runtime line");
    if (!atlas!.textContent?.includes("Agent")) throw new Error("the Agent tag");
    if (krishna!.querySelector("[data-testid='mention-runtime']")) throw new Error("a person has no runtime line");
    if (krishna!.textContent?.includes("Agent")) throw new Error("a person has no tag");
    if (krishna!.getAttribute("aria-label") !== "Krishna") throw new Error("a person's plain name");
    if (atlas!.getBoundingClientRect().height < 44) throw new Error("rows are at least 44px");
    const face = atlas!.querySelector<HTMLElement>("[data-testid='mention-face']")!;
    if (Math.round(face.getBoundingClientRect().width) !== 30) throw new Error("a 30px face");
    if (face.textContent !== "A") throw new Error("the initial");
    if (options(root).filter((o) => o.getAttribute("aria-selected") === "true").length !== 1) throw new Error("one active row");
  }}
>
  {#snippet template()}{@render inComposer(many, 0)}{/snippet}
</Story>

<!-- 150 % text in a 600px window: rows stay whole, names truncate, nothing overflows. -->
<Story
  name="Largest text at 600px"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => options(root).length === 6, "six rows");
    const list = root.querySelector<HTMLElement>("[role='listbox']")!;
    if (list.scrollWidth > list.clientWidth + 1) throw new Error("the list overflows sideways");
    for (const o of options(root)) {
      if (o.getBoundingClientRect().height < 44) throw new Error("rows are at least 44px");
    }
  }}
>
  {#snippet template()}{@render inComposer(many, 1, '400px', 1.5)}{/snippet}
</Story>
