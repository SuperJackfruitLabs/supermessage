import type { CustomEventDecision, CustomEventDecisionOption } from "$lib/ipc";

/**
 * How a decision's answers are laid out and acknowledged — iOS's
 * `DecisionOptionRole` and `DecisionReceipt`. The role comes from the option
 * **id**, never its label: the label is the sender's wording.
 */
export type OptionRole = "approve" | "requestChanges" | "reject" | "other";

export function optionRole(option: CustomEventDecisionOption): OptionRole {
  switch (option.id.toLowerCase()) {
    case "approve":
      return "approve";
    case "request_changes":
      return "requestChanges";
    case "reject":
      return "reject";
    default:
      return "other";
  }
}

export interface ArrangedOptions {
  primary: CustomEventDecisionOption | null;
  secondaries: CustomEventDecisionOption[];
  rejections: CustomEventDecisionOption[];
}

/** One answer leads (the first non-reject), the rest follow, rejections last. */
export function arrangeOptions(options: CustomEventDecisionOption[]): ArrangedOptions {
  const rejections = options.filter((o) => optionRole(o) === "reject");
  const rest = options.filter((o) => optionRole(o) !== "reject");
  return { primary: rest[0] ?? null, secondaries: rest.slice(1), rejections };
}

export function receiptOutcome(option: CustomEventDecisionOption): string {
  switch (optionRole(option)) {
    case "approve":
      return "Approved by you";
    case "requestChanges":
      return "Changes requested by you";
    case "reject":
      return "Rejected by you";
    case "other":
      return `You chose “${option.label}”`;
  }
}

/** "just now" under a minute, then the platform's relative phrasing. */
export function answeredWhen(atMs: number, nowMs: number): string {
  const elapsed = Math.max(0, nowMs - atMs);
  if (elapsed < 60_000) return "just now";
  const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 60) return rtf.format(-minutes, "minute");
  return rtf.format(-Math.floor(minutes / 60), "hour");
}

/**
 * The line a card shows when the decision can be answered in full only on a
 * phone or the board: a follow-up (it takes a link), a question that asks for
 * text, a gate.v2 whose options include ones a decision.v1 cannot send, or
 * one whose approvals the core withheld. `null` when the buttons here are the
 * whole of it.
 */
export const ANSWER_ELSEWHERE = "Open the card, or answer on your phone, for the rest of this one.";

/**
 * The same for a harness permission.v2, which has no board card to open: the
 * desktop never allows (it draws no preview), so allowing is on a phone or in
 * the console.
 */
export const ALLOW_ELSEWHERE = "To allow this, answer on your phone or in the console.";

export function answerElsewhere(decision: CustomEventDecision): string | null {
  if (decision.deliveryV2) return ANSWER_ELSEWHERE;
  const permission = decision.permissionV2;
  if (permission) {
    if (permission.approvalsWithheld) return permission.approvalsWithheld;
    return permission.options.length > decision.options.length ? ALLOW_ELSEWHERE : null;
  }
  const question = decision.questionV2;
  if (question && (question.freeText !== null || question.options.some((o) => o.input))) {
    return ANSWER_ELSEWHERE;
  }
  // A question bound to a subject: its approvals are never among `options`.
  if (question?.subject && (question.approvalsWithheld || question.options.length > decision.options.length)) {
    return question.approvalsWithheld ?? ANSWER_ELSEWHERE;
  }
  const gate = decision.gateV2;
  if (gate && (gate.approvalsWithheld !== null || gate.options.length > decision.options.length)) {
    return gate.approvalsWithheld ?? ANSWER_ELSEWHERE;
  }
  return null;
}
