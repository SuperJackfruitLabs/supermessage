<script lang="ts">
  import { onMount } from "svelte";
  import { cubicOut } from "svelte/easing";
  import type { FlyParams } from "svelte/transition";
  import { fly } from "svelte/transition";
  import {
    agentAvatarStyle,
    firstRunAdvance,
    firstRunAnswer,
    firstRunScript,
    type AgentAvatarStyle,
    type FirstRunScript,
    type FirstRunStep,
  } from "$lib/ipc";
  import { createFirstRunDemo, demoCardItem, demoCardView } from "./firstRun";
  import RoomAvatar from "./roster/RoomAvatar.svelte";
  import DispatchCard from "./timeline/DispatchCard.svelte";

  /**
   * The first-run demo (iOS `FirstRunDemoView`): a sample agent, Atlas, asks for a permission and
   * the reader approves it, over the whole window. **Nothing here is sent anywhere.** It asks the
   * core for the script and for each next step, and for the agent's tile; it takes no session and
   * no store, and the card's answer is a local closure that only moves the step. The isolation
   * test pins that to the first-run commands.
   *
   * `core` is for stories, which have no core to ask: a script, a step to stand at and a sleep that
   * never resolves. The app passes nothing.
   */
  export interface Props {
    onFinish: () => void;
    core?: {
      script?: FirstRunScript;
      initialStep?: FirstRunStep;
      advance?: typeof firstRunAdvance;
      answer?: typeof firstRunAnswer;
      sleep?: (ms: number) => Promise<void>;
      agentAvatarStyle?: typeof agentAvatarStyle;
    };
  }

  let { onFinish, core }: Props = $props();

  const reduced =
    typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  /** Arrivals rise from below and fade in; nothing moves for a reader who asked for less. */
  const arrive = (): FlyParams => ({ y: 16, duration: reduced ? 0 : 450, easing: cubicOut });

  let script = $state<FirstRunScript | null>(null);
  let step = $state<FirstRunStep>("greeting");
  let tile = $state<AgentAvatarStyle | null>(null);
  let dialog = $state<HTMLDialogElement | null>(null);
  let demo: ReturnType<typeof createFirstRunDemo> | null = null;
  const openedAt = Date.now();

  onMount(() => {
    dialog?.showModal();
    let gone = false;
    void (async () => {
      try {
        const loaded = core?.script ?? (await firstRunScript());
        if (gone) return;
        script = loaded;
        step = core?.initialStep ?? "greeting";
        demo = createFirstRunDemo({
          script: loaded,
          advance: core?.advance ?? firstRunAdvance,
          answer: core?.answer ?? firstRunAnswer,
          sleep: core?.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms))),
          initialStep: core?.initialStep,
          onChange: () => {
            if (demo) step = demo.step;
          },
        });
        void (core?.agentAvatarStyle ?? agentAvatarStyle)(loaded.roomId)
          .then((t) => {
            if (!gone) tile = t;
          })
          .catch(() => {});
        await demo.run();
      } catch (err) {
        // The demo is a courtesy: if the core cannot say it, leave rather than trap the reader.
        console.error("first-run demo failed", err);
        if (!gone) onFinish();
      }
    })();
    return () => {
      gone = true;
      demo?.dispose();
    };
  });

  const card = $derived(script ? demoCardView(script) : null);
  const decision = $derived(script && script.card.status === "rendered" ? script.card.decision : null);
  const item = $derived(script ? demoCardItem(script, openedAt) : null);

  async function onDecide(_eventId: string | null, _decision: unknown, optionId: string): Promise<boolean> {
    return demo ? demo.answer(optionId) : false;
  }

  /** Escape is Skip; once approved there is nothing to skip, so it finishes. */
  function leave(): void {
    onFinish();
  }
</script>

<dialog
  bind:this={dialog}
  data-testid="first-run"
  aria-label="A sample conversation"
  class="fixed inset-0 m-0 h-auto max-h-none w-auto max-w-none border-0 bg-surface p-0 text-content backdrop:bg-scrim"
  oncancel={(e: Event) => {
    e.preventDefault();
    leave();
  }}
>
  {#if script !== null}
    <div class="flex h-full flex-col">
      <header class="flex items-center justify-between gap-3 px-4 py-3" style="padding-top: calc(0.75rem + var(--inset-top));">
        <h2 class="min-w-0 truncate text-ui-lg font-semibold">{script.agentName}</h2>
        {#if step !== "approved"}
          <button
            type="button"
            data-testid="first-run-skip"
            onclick={leave}
            class="rounded-control px-3 py-1.5 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
            >{script.skipLabel}</button
          >
        {/if}
      </header>

      <div class="flex-1 overflow-y-auto">
        <div class="mx-auto flex w-full max-w-[560px] flex-col gap-4 p-4">
          <p class="text-center text-meta text-content-faint">{script.banner}</p>

          {#if step === "greeting"}
            <div class="flex items-end gap-2" in:fly={arrive()}>
              <RoomAvatar
                initial={script.initial}
                avatarUrl={null}
                describesAgent={true}
                style={tile}
                state="active"
                size={32}
              />
              <div
                class="flex items-center gap-1 rounded-card bg-surface-raised px-3.5 py-2.5 text-content-muted"
                role="img"
                aria-label={script.typingLabel}
                data-testid="first-run-typing"
              >
                {#each [0, 1, 2] as i (i)}
                  <span class="typing-dot h-1.5 w-1.5 rounded-pill bg-current" style="animation-delay: {i * 160}ms"></span>
                {/each}
              </div>
            </div>
          {:else}
            <div class="flex items-start gap-2" in:fly={arrive()}>
              <RoomAvatar
                initial={script.initial}
                avatarUrl={null}
                describesAgent={true}
                style={tile}
                state="active"
                size={32}
              />
              <div class="min-w-0 flex-1 rounded-card bg-surface-raised p-3" data-testid="first-run-message">
                <p class="flex items-center gap-1.5">
                  <span class="text-ui font-semibold">{script.agentName}</span>
                  <span class="rounded-pill bg-accent-soft px-1.5 text-label font-semibold text-accent">{script.agentTag}</span>
                </p>
                <p class="mt-1 text-body">{script.greeting}</p>
              </div>
            </div>
          {/if}

          {#if (step === "waiting" || step === "approved") && card !== null && item !== null}
            <div class="min-w-0 max-w-[68ch] font-sans text-body text-content" in:fly={arrive()}>
              <DispatchCard
                view={card}
                {decision}
                {item}
                {onDecide}
                formatTime={() => ""}
              />
            </div>
          {/if}

          {#if step === "approved"}
            <div class="flex flex-col items-center gap-3 text-center" in:fly={arrive()}>
              <svg
                viewBox="0 0 24 24"
                width="40"
                height="40"
                aria-hidden="true"
                data-testid="first-run-check"
                class="text-ok {reduced ? '' : 'check-bounce'}"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <circle cx="12" cy="12" r="9.5" />
                <path d="M8 12.3l2.8 2.8 5.2-5.6" />
              </svg>
              <h3 class="text-ui-lg font-semibold">{script.approvedTitle}</h3>
              <p class="text-ui text-content-muted">{script.approvedBody}</p>
              <button
                type="button"
                data-testid="first-run-finish"
                onclick={leave}
                class="w-full rounded-card bg-accent px-4 py-3 text-ui-lg font-semibold text-accent-content transition-opacity hover:opacity-90"
                >{script.finishLabel}</button
              >
            </div>
          {/if}
        </div>
      </div>
    </div>
  {/if}
</dialog>

<style>
  @media (prefers-reduced-motion: no-preference) {
    .typing-dot {
      animation: typing-dot 1000ms ease-in-out infinite;
    }
    .check-bounce {
      animation: check-bounce 450ms ease-out 1 both;
    }
  }
  @keyframes typing-dot {
    0%,
    80%,
    100% {
      opacity: 0.35;
    }
    40% {
      opacity: 1;
    }
  }
  @keyframes check-bounce {
    0%,
    100% {
      transform: scale(1);
    }
    40% {
      transform: scale(1.18);
    }
  }
</style>
