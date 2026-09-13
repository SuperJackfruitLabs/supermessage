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

<Story name="One candidate" args={{ matches: one, activeIndex: 0 }} />
<Story name="Several candidates" args={{ matches: many, activeIndex: 0 }} />

<!-- The keyboard cursor further down, which is the only thing that marks
     a row as selected. -->
<Story name="Cursor moved" args={{ matches: many, activeIndex: 3 }} />

<!-- A candidate with no display name falls back to its matrix id. -->
<Story name="Unnamed candidate" args={{ matches: [m("@9247e5a1b3c4:id.agentpod.dev", null)], activeIndex: 0 }} />
