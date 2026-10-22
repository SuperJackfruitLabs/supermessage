<script lang="ts">
  import { onMount, tick } from "svelte";
  import { reportReasonRemaining } from "$lib/ipc";
  import { blockable, type ReportResult, type ReportSubject } from "$lib/safety";
  import {
    blockConsequence,
    canSend,
    confirmationMessage,
    confirmationTitle,
    counterLine,
    reportExplanation,
    reportTitle,
    sendLabel,
  } from "./reportView";

  /**
   * Report a message, a room or a person, with a reason (iOS `ReportSheet`).
   *
   * One native modal `<dialog>` with two stages: the form, and the
   * confirmation iOS shows as a confirmation dialog. The parent owns whether
   * it is open; `onSend` resolves `sent` (the sheet closes), `refused`
   * (the sheet stays, the reason kept) or `reportedNotBlocked` (the report landed and the block did
   * not: the sheet says so and offers only Done, never Send again).
   *
   * While a report is in flight nothing dismisses the sheet: Escape and the
   * backdrop do nothing, as iOS's `interactiveDismissDisabled`.
   */
  export interface Props {
    subject: ReportSubject;
    isBlocked: (userId: string) => boolean;
    remaining?: (reason: string) => Promise<number>;
    onSend: (reason: string, alsoBlock: boolean) => Promise<ReportResult>;
    onClose: () => void;
    /**
     * Where focus returns when the sheet closes, when the caller knows better than whatever has
     * focus as the sheet mounts (a menu that has already closed leaves it on the page body).
     */
    opener?: HTMLElement | null;
  }

  let {
    subject,
    isBlocked,
    remaining = reportReasonRemaining,
    onSend,
    onClose,
    opener: given = null,
  }: Props = $props();

  const target = $derived(blockable(subject));
  const alreadyBlocked = $derived(target !== null && isBlocked(target.userId));

  let reason = $state("");
  // On by default wherever there is someone to block (iOS `:39-45`).
  let alsoBlock = $state(true);
  let stage = $state<"form" | "confirm" | "reported">("form");
  let sending = $state(false);
  let failure = $state<string | null>(null);
  let left = $state(2000);
  let dialog = $state<HTMLDialogElement | null>(null);
  let reasonField = $state<HTMLTextAreaElement | null>(null);
  let cancelButton = $state<HTMLButtonElement | null>(null);
  let doneButton = $state<HTMLButtonElement | null>(null);

  /** Only the newest question's answer may land. */
  let asked = 0;
  async function ask(text: string): Promise<void> {
    const mine = ++asked;
    try {
      const answer = await remaining(text);
      if (mine === asked) left = answer;
    } catch {
      // The counter stays as it was; the core refuses an over-long reason anyway.
    }
  }

  const counter = $derived(counterLine(left));
  const effectiveBlock = $derived(alsoBlock && target !== null && !alreadyBlocked);

  onMount(() => {
    const opener = given ?? document.activeElement;
    dialog?.showModal();
    reasonField?.focus();
    void ask(reason);
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  });

  async function toStage(next: "form" | "confirm"): Promise<void> {
    stage = next;
    await tick();
    (next === "form" ? reasonField : cancelButton)?.focus();
  }

  function dismiss(): void {
    if (sending) return;
    if (stage === "reported") onClose();
    else if (stage === "confirm") void toStage("form");
    else onClose();
  }

  async function send(): Promise<void> {
    if (sending) return;
    sending = true;
    failure = null;
    let result: ReportResult;
    try {
      result = await onSend(reason, effectiveBlock);
    } catch {
      result = { status: "refused", message: "That didn't work." };
    }
    sending = false;
    if (result.status === "sent") onClose();
    else if (result.status === "reportedNotBlocked") {
      // The report is with the homeserver. Offering Send again would file it twice.
      failure = result.message;
      stage = "reported";
      await tick();
      doneButton?.focus();
    } else {
      failure = result.message;
      await toStage("form");
    }
  }
</script>

<dialog
  bind:this={dialog}
  aria-label={reportTitle(subject)}
  class="m-auto mt-16 max-h-[90vh] w-full max-w-md overflow-y-auto rounded-card border border-border bg-surface p-0 text-content shadow-lg backdrop:bg-scrim"
  oncancel={(e: Event) => {
    e.preventDefault();
    dismiss();
  }}
  onclick={(e: MouseEvent) => {
    if (e.target === dialog) dismiss();
  }}
>
  {#if stage === "form"}
    <div class="flex flex-col gap-3 p-4">
      <h2 class="text-ui font-medium break-words text-content">{reportTitle(subject)}</h2>
      <p class="text-ui text-content-muted">{reportExplanation(subject)}</p>

      <div class="flex flex-col gap-1">
        <label for="report-reason" class="text-label text-content-muted">Reason</label>
        <textarea
          id="report-reason"
          bind:this={reasonField}
          bind:value={reason}
          oninput={() => void ask(reason)}
          rows="4"
          disabled={sending}
          placeholder="What's wrong? (optional)"
          aria-label="Reason for the report"
          aria-describedby="report-reason-hint"
          class="max-h-60 w-full resize-y rounded-control border border-border bg-surface-sunken px-2 py-1.5 font-sans text-ui text-content placeholder:text-content-faint"
        ></textarea>
        <span id="report-reason-hint" class="sr-only">Optional. Seen only by the homeserver's administrators.</span>
        {#if counter !== null}
          <p class="text-meta tabular-nums {counter.over ? 'text-danger' : 'text-content-muted'}">
            {counter.text}
          </p>
        {/if}
      </div>

      {#if target !== null}
        <div class="flex flex-col gap-1">
          {#if alreadyBlocked}
            <p class="text-ui text-content-muted">{target.name} is blocked</p>
          {:else}
            <label class="flex items-center gap-2 text-ui text-content">
              <input type="checkbox" bind:checked={alsoBlock} disabled={sending} />
              Also block {target.name}
            </label>
          {/if}
          <p class="text-meta text-content-muted">{blockConsequence(target.isAgent)}</p>
        </div>
      {/if}

      {#if failure !== null}
        <p role="alert" class="text-ui text-danger">{failure}</p>
      {/if}

      <div class="flex justify-end gap-2">
        <button
          type="button"
          disabled={sending}
          onclick={onClose}
          class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content disabled:opacity-50"
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={sending || !canSend(left)}
          title="Asks before sending"
          onclick={() => void toStage("confirm")}
          class="rounded-control bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-colors disabled:opacity-50"
        >
          Send
        </button>
      </div>
    </div>
  {:else if stage === "reported"}
    <div class="flex flex-col gap-3 p-4">
      <h2 class="text-ui font-medium break-words text-content">Report sent</h2>
      <p role="alert" class="text-ui text-danger">{failure}</p>
      <div class="flex justify-end gap-2">
        <button
          bind:this={doneButton}
          type="button"
          onclick={onClose}
          class="rounded-control bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-colors"
        >
          Done
        </button>
      </div>
    </div>
  {:else}
    <div class="flex flex-col gap-3 p-4">
      <h2 class="text-ui font-medium break-words text-content">{confirmationTitle(subject)}</h2>
      <p class="text-ui text-content-muted">{confirmationMessage(subject)}</p>
      <div class="flex justify-end gap-2">
        <button
          bind:this={cancelButton}
          type="button"
          disabled={sending}
          onclick={() => void toStage("form")}
          class="rounded-control px-3 py-2 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content disabled:opacity-50"
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={sending}
          onclick={() => void send()}
          class="rounded-control bg-danger px-3 py-2 text-ui text-accent-content transition-colors disabled:opacity-50"
        >
          {sendLabel(subject, effectiveBlock)}
        </button>
      </div>
    </div>
  {/if}
</dialog>
