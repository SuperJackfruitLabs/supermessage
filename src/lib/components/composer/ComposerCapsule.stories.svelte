<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { attachmentFailure } from "../stagedAttachment";
  import ComposerCapsule from "./ComposerCapsule.svelte";
  import StagedAttachmentChip from "./StagedAttachmentChip.svelte";

  const { Story } = defineMeta({
    title: "Composer/ComposerCapsule",
    component: ComposerCapsule,
    args: {
      onAttach: () => {},
      onSend: () => {},
      attachDisabled: false,
      sendLabel: "Send",
      canSend: false,
    },
    parameters: {
      docs: {
        description: {
          component:
            "One capsule holds the strip, the chip and the field row, with + " +
            "inside at the leading edge. A failed file is drawn on its chip.",
        },
      },
    },
  });

  const failed = attachmentFailure(
    {
      kind: "attachmentTooLarge",
      message: "that file is 200.0 MiB, but this homeserver accepts at most 50.0 MiB",
    },
    "send",
  );
</script>

{#snippet field()}
  <textarea
    rows="1"
    placeholder="Message"
    class="min-h-10 min-w-0 flex-1 resize-none bg-transparent py-1 font-sans text-ui text-content outline-none placeholder:text-content-faint"
  ></textarea>
{/snippet}

{#snippet staged()}
  <StagedAttachmentChip
    filename="muster-dark.png"
    summary="PNG · 412 KB"
    caveat={null}
    onRemove={() => {}}
  />
{/snippet}

{#snippet failedTop()}
  <StagedAttachmentChip
    filename="disk-image.iso"
    summary=""
    caveat={null}
    failure={failed}
    onRemove={() => {}}
  />
{/snippet}

<Story name="Empty" args={{ field }} />

<Story name="With a staged file" args={{ field, top: staged, canSend: true, sendLabel: "Send file" }} />

<Story name="With a failed file" args={{ field, top: failedTop }} />

<!-- Inside the real tray row (Composer.svelte): the capsule must fill the 72ch column. -->
<Story name="Fills the column" args={{ field, top: staged, canSend: true, sendLabel: "Send file" }}>
  {#snippet template(args)}
    <div class="w-[900px] bg-surface-sunken px-4 py-3">
      <div class="relative mx-auto flex w-full max-w-[72ch] items-end gap-2">
        <ComposerCapsule {...args} />
      </div>
    </div>
  {/snippet}
</Story>
