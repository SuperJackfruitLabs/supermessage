import type { CustomEventDecision, CustomEventField, ItemView } from "$lib/ipc";

/**
 * Dispatch-card states.
 *
 * The signature element, per the console design's §7 — and the only place
 * `--color-signal` (amber) appears anywhere in the application. These three
 * fixtures are what make that rule checkable rather than merely written
 * down: exactly one of them should be amber.
 */

type CustomEventItemView = Extract<ItemView, { render: "customEvent" }>;

export function customEventView(
  overrides: Partial<CustomEventItemView> = {},
): CustomEventItemView {
  return {
    render: "customEvent",
    label: "Turn",
    eventType: "dev.agentpod.turn.v1",
    view: {
      status: "rendered",
      fields: [],
      reasoning: null,
      newerVersion: false,
      decision: null,
    },
    ...overrides,
  };
}

const fields: CustomEventField[] = [
  { label: "Station", value: "Rakeshs-MacBook-Pro.local" },
  { label: "Build", value: "214" },
  { label: "ABIs", value: "arm64-v8a, armeabi-v7a, x86, x86_64" },
];

const decision: CustomEventDecision = {
  prompt: "Promote build 214 to production?",
  options: [
    { id: "approve", label: "Approve" },
    { id: "decline", label: "Decline" },
  ],
};

/**
 * The decision on {@link dispatchCardPending}, exported separately.
 *
 * `CustomEventView` is a union and only its `rendered` arm has a
 * `decision`, so a story cannot reach through the fixture to get it — which
 * svelte-check says out loud the moment a story tries.
 */
export const dispatchCardPendingDecision = decision;

/**
 * A pending decision — the one state that is amber.
 *
 * `--color-signal` means the operator owes someone an answer, and this is
 * the only thing in the product that may use it. The card's left edge, its
 * ground and its label all move; a story is where that is visible.
 */
export const dispatchCardPending = customEventView({
  label: "Permission",
  eventType: "dev.agentpod.permission.v1",
  view: { status: "rendered", fields, reasoning: null, newerVersion: false, decision },
});

/** Answered, so the amber is gone and the buttons have settled. */
export const dispatchCardAnswered = customEventView({
  label: "Permission",
  eventType: "dev.agentpod.permission.v1",
  view: { status: "rendered", fields, reasoning: null, newerVersion: false, decision: null },
});

/** A card with the agent's reasoning attached. */
export const dispatchCardWithReasoning = customEventView({
  view: {
    status: "rendered",
    fields,
    reasoning: "Staging is green across all four ABIs and the 16KB device passed.",
    newerVersion: false,
    decision: null,
  },
});

/**
 * A schema this build is too old to render fully.
 *
 * `newerVersion` is the core telling the host that the sender knows more
 * about this event type than it does.
 */
export const dispatchCardNewerVersion = customEventView({
  view: { status: "rendered", fields, reasoning: null, newerVersion: true, decision: null },
});

/**
 * A field value that is one long unbroken run.
 *
 * The card's wrap guards — `break-words`, `max-w-[68ch]`, `min-w-0` — exist
 * because every value here is arbitrary JSON from anyone who can send to
 * the room. This is the fixture that shows them holding.
 */
export const dispatchCardLongValue = customEventView({
  view: {
    status: "rendered",
    fields: [
      { label: "Session", value: "wss://bridge.agentpod.dev/v1/sessions/" + "a".repeat(90) },
    ],
    reasoning: null,
    newerVersion: false,
    decision: null,
  },
});
