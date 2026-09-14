<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import JumpToNewest from "./JumpToNewest.svelte";

  const { Story } = defineMeta({
    title: "Timeline/JumpToNewest",
    component: JumpToNewest,
    args: { onJump: () => {} },
    parameters: {
      docs: {
        description: {
          component:
            "The route back to the newest message, and the last of the three " +
            "platforms to get one. It is 44px square because that is the " +
            "floor everywhere — iOS's was found eight points under it — and " +
            "the chevron is an inline SVG rather than a text arrow because " +
            "Android's was text and outgrew its own button at large font " +
            "scales. See design-language.md §7.",
        },
      },
    },
  });
</script>

<Story name="Away from newest" />
