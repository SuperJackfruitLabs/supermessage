<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import MentionMenu from "./MentionMenu.svelte";

  const m = (userId: string, displayName: string | null) => ({ userId, displayName });

  const one = [m("@atlas:id.agentpod.dev", "Atlas")];
  const many = [
    m("@atlas:id.agentpod.dev", "Atlas"),
    m("@krishna:id.agentpod.dev", "Krishna"),
    m("@sam:id.agentpod.dev", "Strategy Sam"),
    m("@quill:id.agentpod.dev", "Writer Quill"),
    m("@chotu:id.agentpod.dev", "Super Chotu"),
    m("@9247e5a1b3c4:id.agentpod.dev", null),
  ];

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
{#snippet inComposer(matches: { userId: string; displayName: string | null }[], activeIndex: number)}
  <div style="height: 22rem; display: flex; align-items: flex-end; justify-content: center; padding: 1rem">
    <!-- `flex: none` keeps the row at its stated width. Its only in-flow
         content is nothing — the menu is absolutely positioned and
         contributes no width — so a flex item free to shrink shrinks to
         zero, and the menu hangs off a zero-width anchor. -->
    <div
      class="relative rounded-control border border-border bg-surface"
      style="height: 2.75rem; width: 100%; max-width: 72ch; flex: none"
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
