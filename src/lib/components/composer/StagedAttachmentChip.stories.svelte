<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import StagedAttachmentChip from "./StagedAttachmentChip.svelte";

  const { Story } = defineMeta({
    title: "Composer/StagedAttachmentChip",
    component: StagedAttachmentChip,
    args: { onRemove: () => {} },
    parameters: {
      docs: {
        description: {
          component:
            "The eyebrow takes --color-accent, and that is a measurement: " +
            "accent-soft over surface-sunken is 1.059:1 in light, weaker " +
            "than the 1.090:1 sheet-on-field step the depth story rests on " +
            "— subliminal in exactly the theme most people use. Amber would " +
            "be louder and is forbidden, because signal means the operator " +
            "owes someone an answer and they owe this one only to themselves.",
        },
      },
    },
  });
</script>

<Story
  name="An image"
  args={{ filename: "muster-dark.png", summary: "PNG · 412 KB", caveat: null }}
/>

<!-- A caveat: what sending this will and will not carry. -->
<Story
  name="With a caveat"
  args={{
    filename: "tokens.toml",
    summary: "TOML · 6 KB",
    caveat: "Sending this will drop the message text.",
  }}
/>

<!-- A filename with nowhere to wrap. -->
<Story
  name="Long filename"
  args={{
    filename: "supermessage-design-language-tokens-regenerated-2026-09-12.toml",
    summary: "TOML · 6 KB",
    caveat: null,
  }}
/>
