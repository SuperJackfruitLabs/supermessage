<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    dispatchCardAnswered,
    dispatchCardGate,
    dispatchCardGateDecision,
    dispatchCardLongValue,
    dispatchCardNewerVersion,
    dispatchCardPending,
    dispatchCardPendingDecision,
    dispatchCardResolved,
    dispatchCardWithReasoning,
    message,
  } from "$lib/fixtures";

  import DispatchCard from "./DispatchCard.svelte";
  import DispatchCardFrame from "./DispatchCardFrame.svelte";

  const item = message({ id: "card-1", timestampMs: 1_700_000_000_000 }).item;
  const formatTime = () => "14:02";

  // Plain DOM, as RecoveryPanel.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the card to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  const clickApprove = async (canvasElement: HTMLElement) => {
    const approve = await settled(() =>
      [...canvasElement.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Approve"),
    );
    approve.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  };

  const clickButton = async (canvasElement: HTMLElement, label: string) => {
    const button = await settled(() =>
      [...canvasElement.querySelectorAll("button")].find((b) => b.textContent?.trim() === label),
    );
    button.dispatchEvent(new MouseEvent("click", { bubbles: true }));
  };
  // The dialog lifts itself to <body>, so it is found on `document`, not in the canvas.
  const typeReason = async (canvasElement: HTMLElement, text: string) => {
    const field = (await settled(() =>
      document.querySelector("[role='dialog'] textarea"),
    )) as HTMLTextAreaElement;
    field.value = text;
    field.dispatchEvent(new Event("input", { bubbles: true }));
  };

  const getAnimations = (el: HTMLElement) => el.getAnimations().length > 0;
  const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  const sentFromCancelStory: (string | null)[] = [];
  const sentFromTrimStory: (string | null)[] = [];

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
          onDecide={() => Promise.resolve(true)}
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
          onDecide={() => Promise.resolve(true)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!--
  Resolved in the room: the board has the answer and says whose it was. No
  buttons and no amber — this is the state that survives the room being
  rebuilt, because it comes from the room.
-->
<Story name="Resolved in the room">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardResolved}
          decision={null}
          {item}
          onDecide={() => Promise.resolve(true)}
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
          onDecide={() => Promise.resolve(true)}
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
          onDecide={() => Promise.resolve(true)}
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
          onDecide={() => Promise.resolve(true)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- The answer is in flight: every button is disabled until the send settles. -->
<Story
  name="Sending"
  play={async ({ canvasElement }) => {
    await clickApprove(canvasElement as HTMLElement);
    await settled(() => canvasElement.querySelector("button:disabled"));
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardPending}
          decision={dispatchCardPendingDecision}
          {item}
          onDecide={() => new Promise<boolean>(() => {})}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- The send failed: a danger-coloured line, and the buttons are live again. -->
<Story
  name="Send failed"
  play={async ({ canvasElement }) => {
    await clickApprove(canvasElement as HTMLElement);
    await settled(() => canvasElement.querySelector("[role='alert']"));
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardPending}
          decision={dispatchCardPendingDecision}
          {item}
          onDecide={() => Promise.resolve(false)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- Three answers: Approve leads, Request changes is bordered, Reject is last as danger text. -->
<Story name="Gate, three answers">
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={() => Promise.resolve(true)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- Request changes asks for the reason before anything is sent. -->
<Story
  name="Request changes dialog"
  play={async ({ canvasElement }) => {
    await clickButton(canvasElement as HTMLElement, "Request changes");
    await settled(() => document.querySelector("[role='dialog']"));
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={() => Promise.resolve(true)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- Offline: the dialog keeps the reason, says it was not sent, and the card shows no receipt. -->
<Story
  name="Request changes fails"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await clickButton(root, "Request changes");
    await typeReason(root, "tests fail");
    await clickButton(document.body, "Send");
    await settled(() => document.querySelector("[role='dialog'] [role='alert']"));
    const field = document.querySelector("[role='dialog'] textarea") as HTMLTextAreaElement;
    if (field.value !== "tests fail") throw new Error("the typed reason was lost");
    if (root.querySelector("[data-testid='decision-receipt']")) throw new Error("a receipt showed for a failed send");
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={() => Promise.resolve(false)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- The send landed: the receipt replaces the buttons, and the amber goes. -->
<Story
  name="Answered on this device"
  play={async ({ canvasElement }) => {
    await clickApprove(canvasElement as HTMLElement);
    const receipt = (await settled(() =>
      canvasElement.querySelector("[data-testid='decision-receipt']"),
    )) as HTMLElement;
    // The receipt settles in (opacity + scale, 250ms) unless the reader asked
    // for reduced motion, in which case it just appears.
    const running = receipt.getAnimations().length > 0;
    if (running === reducedMotion()) throw new Error(`receipt animation running=${running} with reduced motion=${reducedMotion()}`);
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={() => Promise.resolve(true)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- Opening the reasoning fades its text in (opacity only; the snapshot gate renders without motion). -->
<Story
  name="Reasoning opened"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const details = (await settled(() => root.querySelector("details"))) as HTMLDetailsElement;
    details.open = true;
    const body = (await settled(() => root.querySelector("details .reasoning-body"))) as HTMLElement;
    const fading = getAnimations(body);
    if (fading === reducedMotion()) throw new Error(`reasoning fade running=${fading} with reduced motion=${reducedMotion()}`);
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardWithReasoning}
          decision={null}
          {item}
          onDecide={() => Promise.resolve(true)}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- Escape closes the dialog wherever focus is, nothing is sent, and focus returns to the button. -->
<Story
  name="Request changes cancelled"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await clickButton(root, "Request changes");
    await settled(() => document.querySelector("[role='dialog']"));
    document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await settled(() => (document.querySelector("[role='dialog']") ? null : root));
    if (root.querySelector("[data-testid='decision-receipt']")) throw new Error("a receipt showed after cancelling");
    if (sentFromCancelStory.length > 0) throw new Error("cancelling sent something");
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={(_e, _d, _o, comment) => {
            sentFromCancelStory.push(comment);
            return Promise.resolve(true);
          }}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- The reason is trimmed, and a blank one goes as null. -->
<Story
  name="Request changes trims the reason"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    sentFromTrimStory.length = 0;
    await clickButton(root, "Request changes");
    await typeReason(root, "   ");
    await clickButton(document.body, "Send");
    await settled(() => document.querySelector("[role='dialog'] [role='alert']"));
    await typeReason(root, "  tests fail \n");
    await clickButton(document.body, "Send");
    await settled(() => (sentFromTrimStory.length === 2 ? root : null));
    if (sentFromTrimStory[0] !== null) throw new Error("a blank reason was not sent as null");
    if (sentFromTrimStory[1] !== "tests fail") throw new Error("the reason was not trimmed");
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={(_e, _d, _o, comment) => {
            sentFromTrimStory.push(comment);
            return Promise.resolve(false);
          }}
          {formatTime}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>

<!-- A row that remounts after the answer landed (the container held it): still the receipt, no live buttons. -->
<Story
  name="Answered, row remounted"
  play={async ({ canvasElement }) => {
    await settled(() => canvasElement.querySelector("[data-testid='decision-receipt']"));
    if (canvasElement.querySelector("button")) throw new Error("live buttons after a remount");
  }}
>
  {#snippet template()}
      <DispatchCardFrame>
        <DispatchCard
          view={dispatchCardGate}
          decision={dispatchCardGateDecision}
          {item}
          onDecide={() => Promise.resolve(true)}
          {formatTime}
          answeredHere={{ option: dispatchCardGateDecision.options[1], atMs: Date.now() }}
        />
      </DispatchCardFrame>
  {/snippet}
</Story>
