<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import ComposerCapsule from "./ComposerCapsule.svelte";
  import FailureLine from "./FailureLine.svelte";
  import MicButton from "./MicButton.svelte";
  import RecordingPill from "./RecordingPill.svelte";
  import { MIC_REFUSED } from "$lib/voice/voiceRecorder";
  import type { Timers } from "$lib/voice/micGesture";

  const { Story } = defineMeta({
    title: "Composer/Voice",
    component: ComposerCapsule,
    args: { onAttach: () => {}, attachDisabled: false, sendLabel: "Send", canSend: false, onSend: () => {} },
    parameters: {
      docs: {
        description: {
          component:
            "The mic and the recording pill. The dot and the filled mic are danger, never amber. " +
            "The elapsed time is the core's `elapsed_label`; the stories answer it with a fixed clock.",
        },
      },
    },
  });

  const NOW = 1_700_000_003_000;
  const STARTED = NOW - 3000;
  const elapsed = async (ms: number) => `${Math.floor(ms / 1000)}s`;
  const noop = () => {};

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };

  /** A clock the story drives by hand. */
  function fakeClock() {
    let pending: (() => void) | null = null;
    const timers: Timers = { set: (fn) => ((pending = fn), 1), clear: () => (pending = null) };
    return { timers, fire: () => pending?.() };
  }
  const clock = fakeClock();
  let log: string[] = [];
</script>

{#snippet field()}
  <textarea
    rows="1"
    placeholder="Message"
    class="min-h-10 min-w-0 flex-1 resize-none bg-transparent py-1 font-sans text-ui text-content outline-none placeholder:text-content-faint"
  ></textarea>
{/snippet}

{#snippet idleMic()}
  <MicButton onTap={noop} onHoldBegan={noop} onHoldEnded={noop} />
{/snippet}

{#snippet heldMic()}
  <MicButton recording onTap={noop} onHoldBegan={noop} onHoldEnded={noop} />
{/snippet}

{#snippet tappedPill()}
  <RecordingPill startedAt={STARTED} held={false} onDiscard={noop} now={() => NOW} {elapsed} still />
{/snippet}

{#snippet heldPill()}
  <RecordingPill startedAt={STARTED} held onDiscard={noop} now={() => NOW} {elapsed} still />
{/snippet}

{#snippet loggedMic()}
  <MicButton
    timers={clock.timers}
    onTap={() => log.push("tapped")}
    onHoldBegan={() => log.push("holdBegan")}
    onHoldEnded={(cancelled) => log.push(`holdEnded:${cancelled}`)}
  />
{/snippet}

<!-- The field is empty and nothing is staged: the mic takes Send's place. -->
<Story name="Idle" args={{ field, mic: idleMic }}>
  {#snippet template(args)}
    <div class="bg-surface-sunken px-4 py-3 font-sans"><ComposerCapsule {...args} /></div>
  {/snippet}
</Story>

<!-- A tap records: the pill replaces the field, Send reads "Send voice message", Discard is in the pill. -->
<Story
  name="Recording by tap"
  args={{ field: tappedPill, sendLabel: "Send voice message", canSend: true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='recording-elapsed']")?.textContent === "3s", "the elapsed time");
    if (root.querySelector("[data-testid='recording-hint']")?.textContent?.trim() !== "Recording") throw new Error("hint");
    if (!root.querySelector("button[aria-label='Discard recording']")) throw new Error("Discard recording");
  }}
>
  {#snippet template(args)}
    <div class="bg-surface-sunken px-4 py-3 font-sans"><ComposerCapsule {...args} /></div>
  {/snippet}
</Story>

<!-- Held: the mic stays under the pointer, in danger; no Discard button; "Slide left to cancel". -->
<Story
  name="Recording held"
  args={{ field: heldPill, mic: heldMic, attachDisabled: true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='recording-elapsed']")?.textContent === "3s", "the elapsed time");
    if (root.querySelector("[data-testid='recording-hint']")?.textContent?.trim() !== "Slide left to cancel") throw new Error("hint");
    if (root.querySelector("button[aria-label='Discard recording']")) throw new Error("no Discard while held");
  }}
>
  {#snippet template(args)}
    <div class="bg-surface-sunken px-4 py-3 font-sans"><ComposerCapsule {...args} /></div>
  {/snippet}
</Story>

<!-- Tap, then the Discard button by keyboard: focus lands on it and shows a ring. -->
<Story
  name="Discard focused"
  args={{ field: tappedPill, sendLabel: "Send voice message", canSend: true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("button[aria-label='Discard recording']"), "the discard button");
    root.querySelector<HTMLButtonElement>("button[aria-label='Discard recording']")!.focus();
    if (document.activeElement?.getAttribute("aria-label") !== "Discard recording") throw new Error("focus");
  }}
>
  {#snippet template(args)}
    <div class="bg-surface-sunken px-4 py-3 font-sans"><ComposerCapsule {...args} /></div>
  {/snippet}
</Story>

<!-- The microphone is refused: the core-style line above the capsule, the copy iOS shows. -->
<Story name="Microphone refused" args={{ field, mic: idleMic }}>
  {#snippet template(args)}
    <div class="font-sans">
      <FailureLine label="Voice message" message={MIC_REFUSED} />
      <div class="bg-surface-sunken px-4 py-3"><ComposerCapsule {...args} /></div>
    </div>
  {/snippet}
</Story>

<!-- 150 % text in a 600px window: the pill's words truncate before anything overflows. -->
<Story
  name="Largest text at 600px"
  args={{ field: tappedPill, sendLabel: "Send voice message", canSend: true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelector("[data-testid='recording-elapsed']")?.textContent === "3s", "the elapsed time");
    const pill = root.querySelector<HTMLElement>("[data-testid='recording-pill']")!;
    if (pill.scrollWidth > pill.clientWidth + 1) throw new Error("the pill overflows");
    const doc = root.querySelector<HTMLElement>("[data-testid='voice-frame']")!;
    if (doc.scrollWidth > doc.clientWidth + 1) throw new Error("the frame overflows");
  }}
>
  {#snippet template(args)}
    <div data-testid="voice-frame" class="bg-surface-sunken px-4 py-3 font-sans" style="zoom: 1.5; width: 400px;">
      <ComposerCapsule {...args} />
    </div>
  {/snippet}
</Story>

<!-- The hold timer, driven by a fake clock: 300 ms is a hold, release sends; the log says so. -->
<Story
  name="Hold under a fake clock"
  args={{ field, mic: loggedMic }}
  play={async ({ canvasElement }) => {
    const btn = (canvasElement as HTMLElement).querySelector<HTMLButtonElement>("[data-testid='mic-button']")!;
    log = [];
    const fire = (type: string, x: number) =>
      btn.dispatchEvent(new PointerEvent(type, { bubbles: true, button: 0, clientX: x, pointerId: 1 }));
    fire("pointerdown", 200);
    clock.fire();
    fire("pointerup", 190);
    if (log.join(",") !== "holdBegan,holdEnded:false") throw new Error(`hold: ${log}`);
    log = [];
    fire("pointerdown", 200);
    fire("pointerup", 200);
    if (log.join(",") !== "tapped") throw new Error(`tap: ${log}`);
    log = [];
    fire("pointerdown", 200);
    clock.fire();
    fire("pointerup", 100);
    if (log.join(",") !== "holdBegan,holdEnded:true") throw new Error(`cancel: ${log}`);
  }}
>
  {#snippet template(args)}
    <div class="bg-surface-sunken px-4 py-3 font-sans">
      <ComposerCapsule {...args} />
    </div>
  {/snippet}
</Story>
