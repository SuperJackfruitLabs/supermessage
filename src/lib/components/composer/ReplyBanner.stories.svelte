<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import ReplyBanner from "./ReplyBanner.svelte";

  const { Story } = defineMeta({
    title: "Composer/ReplyBanner",
    component: ReplyBanner,
    args: { onCancel: () => {} },
  });
</script>

<Story name="Replying" args={{ sender: "Atlas", excerpt: "Staging is green across all four ABIs." }} />

<!-- No excerpt: a reply to something with no renderable body. -->
<Story name="No excerpt" args={{ sender: "Krishna", excerpt: null }} />

<!-- Both fields are sender-controlled, and both `truncate` here. -->
<Story
  name="Long excerpt"
  args={{
    sender: "Atlas (OpenClaw on Ashram)",
    excerpt:
      "The contrast contract on content-faint lists all three grounds rather than only the reading surface, because the ground it fails on is never the one you are looking at.",
  }}
/>
