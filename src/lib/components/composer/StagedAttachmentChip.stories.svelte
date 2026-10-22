<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { attachmentFailure } from "../stagedAttachment";
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

<!-- A send that failed: the refusal on the chip, in danger. -->
<Story
  name="Send failed on the chip"
  args={{
    filename: "disk-image.iso",
    summary: "",
    caveat: null,
    failure: attachmentFailure(
      {
        kind: "attachmentTooLarge",
        message: "that file is 200.0 MiB, but this homeserver accepts at most 50.0 MiB",
      },
      "send",
    ),
  }}
/>

<!-- A pick that was refused: no file, so the filename falls back (iOS ComposerView.swift:633). -->
<Story
  name="Refused when attaching"
  args={{
    filename: "Attachment",
    summary: "",
    caveat: null,
    failure: attachmentFailure({ kind: "unknownAttachment", message: "" }, "attach"),
  }}
/>

<!-- A send that failed but the file is still staged: the failure shows and Send retries. -->
<Story
  name="Failed, ready to retry"
  args={{
    filename: "holiday.png",
    summary: "PNG · 412 KB",
    caveat: null,
    failure: attachmentFailure({ kind: "network", message: "connection reset" }, "send", "Couldn't send that file."),
  }}
/>
