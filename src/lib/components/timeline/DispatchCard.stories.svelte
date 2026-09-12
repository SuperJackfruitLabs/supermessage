<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    dispatchCardAnswered,
    dispatchCardLongValue,
    dispatchCardNewerVersion,
    dispatchCardPending,
    dispatchCardPendingDecision,
    dispatchCardWithReasoning,
    message,
  } from "$lib/fixtures";

  import DispatchCard from "./DispatchCard.svelte";
  import DispatchCardFrame from "./DispatchCardFrame.svelte";

  const item = message({ id: "card-1", timestampMs: 1_700_000_000_000 }).item;
  const formatTime = () => "14:02";

  const { Story } = defineMeta({
    title: "Timeline/DispatchCard",
    component: DispatchCard,
    parameters: {
      docs: {
        description: {
          component:
            "The signature element — the console design's §7 — and the only " +
            "place --color-signal (amber) appears anywhere in this " +
            "application. Every value on it is plain-text interpolation: " +
            "never {@html}, never an href or src fed from the payload, " +
            "because the content is arbitrary JSON from anyone who can send " +
            "to the room. Each story renders inside DispatchCardFrame, " +
            "which carries the wrap guard the timeline puts around the card.",
        },
      },
    },
  });
</script>

<!--
  The one amber state in the product. The left edge, the ground and the
  label move together, and nothing else in any story should look like this
  — if something does, it is a review defect per design-language.md §2.
-->
<Story name="Pending decision">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardPending}
          decision={dispatchCardPendingDecision}
          {item}
          onDecide={() => {}}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- Answered: the amber is gone and the buttons have settled. -->
<Story name="Answered">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardAnswered}
          decision={null}
          {item}
          onDecide={() => {}}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<Story name="With reasoning">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardWithReasoning}
          decision={null}
          {item}
          onDecide={() => {}}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!--
  A schema this build is too old to render fully — the core telling the host
  that the sender knows more about this event type than it does.
-->
<Story name="Newer version">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardNewerVersion}
          decision={null}
          {item}
          onDecide={() => {}}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!--
  A field value that is one 90-character unbroken run.

  The guard being exercised is `max-w-[68ch]` + `min-w-0` on the FRAME, not
  on the card. Rendered without the frame this story was 1147px wide
  instead of 369 — it demonstrated the guard's absence while claiming to
  show it holding, which is worse than having no story at all.
-->
<Story name="Unbreakable value">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardLongValue}
          decision={null}
          {item}
          onDecide={() => {}}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>
