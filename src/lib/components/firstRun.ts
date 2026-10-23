// The first-run demo's step machine (iOS `FirstRunDemoView`). The words, the pauses and which step
// follows which are the core's (`core::first_run`); this holds the current step and paces the
// automatic advances. It touches nothing else: no session, no store, no homeserver.

import {
  firstRunAdvance as defaultAdvance,
  firstRunAnswer as defaultAnswer,
  type FirstRunScript,
  type FirstRunStep,
  type ItemView,
  type TimelineItem,
} from "$lib/ipc";

export interface FirstRunDeps {
  script: FirstRunScript;
  advance: typeof defaultAdvance;
  answer: typeof defaultAnswer;
  sleep: (ms: number) => Promise<void>;
  /** Where the demo starts; a story fixes it. Defaults to the first step. */
  initialStep?: FirstRunStep;
  /** Called whenever `step` or `chosen` changes, for a view that mirrors them. */
  onChange?: () => void;
}

export function createFirstRunDemo(deps: FirstRunDeps) {
  let step: FirstRunStep = deps.initialStep ?? "greeting";
  let chosen: string | null = null;
  let disposed = false;
  const changed = () => deps.onChange?.();

  return {
    get step(): FirstRunStep {
      return step;
    },
    get chosen(): string | null {
      return chosen;
    },

    /** The paced advances, one pause from the script before each; stops at the card or when disposed. */
    async run(): Promise<void> {
      for (const delay of deps.script.stepDelaysMs) {
        await deps.sleep(delay);
        if (disposed) return;
        const next = await deps.advance(step);
        if (disposed) return;
        step = next;
        changed();
      }
    },

    /** The reader's answer to the card. Only the core says whether there is a card to answer. */
    async answer(optionId: string): Promise<boolean> {
      const next = await deps.answer(step);
      if (next === null || disposed) return false;
      step = next;
      chosen = optionId;
      changed();
      return true;
    },

    dispose(): void {
      disposed = true;
    },
  };
}

/** The card's render decision, built from the script (iOS builds the same `customEvent` view). */
export function demoCardView(script: FirstRunScript): Extract<ItemView, { render: "customEvent" }> {
  return { render: "customEvent", view: script.card, label: script.cardLabel, eventType: script.cardEventType };
}

/** A local timeline item for the card: no event id, so there is nothing to address in a room. */
export function demoCardItem(script: FirstRunScript, nowMs: number): TimelineItem {
  return {
    id: "first-run-card",
    eventId: null,
    kind: "customMessage",
    msgtype: null,
    detail: null,
    sender: script.agentName,
    senderDisplayName: script.agentName,
    senderAvatar: null,
    body: null,
    formattedBody: null,
    media: null,
    customPayload: null,
    timestampMs: nowMs,
    isOwn: false,
    sendState: null,
    replyTo: null,
    edited: false,
    reactions: [],
    readBy: [],
    editable: false,
    membershipSubject: null,
  };
}
