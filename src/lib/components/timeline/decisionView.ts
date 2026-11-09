import type { CustomEventDecision, CustomEventDecisionOption, CustomEventOutcome } from "$lib/ipc";

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

/**
 * A card waiting on a person (attention.v2): every resume takes a typed
 * instruction the desktop does not send, so the whole card is answered on a
 * phone or on the board.
 */
export const RESUME_ELSEWHERE = "Resume or archive on your phone or the board.";

/**
 * A gate offering a choice of versions: the desktop draws no versions, so it
 * never approves one; request changes and reject stay answerable here.
 */
export const CHOOSE_ELSEWHERE = "Choose a version on your phone or the board.";

/**
 * A SuperToolkit approval request, which is on no board card: only a
 * workspace owner or admin may allow it, on a phone that shows the request.
 */
export const APPROVAL_REQUEST_ELSEWHERE = "To allow this, a workspace owner or admin answers on their phone.";

export function answerElsewhere(decision: CustomEventDecision): string | null {
  if (decision.attentionV2) return RESUME_ELSEWHERE;
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
    // A SuperToolkit approval request has no card to open.
    if (question.approvalRequestId) return question.approvalsWithheld ?? APPROVAL_REQUEST_ELSEWHERE;
    return question.approvalsWithheld ?? ANSWER_ELSEWHERE;
  }
  const gate = decision.gateV2;
  if (gate?.variants?.length) return CHOOSE_ELSEWHERE;
  if (gate && (gate.approvalsWithheld !== null || gate.options.length > decision.options.length)) {
    return gate.approvalsWithheld ?? ANSWER_ELSEWHERE;
  }
  return null;
}

/**
 * How far a gate that needs more than one approval has got ("1 of 2 approvals. Waiting for 1
 * more."), worded by the core (`core::gate_quorum`); null for any other decision.
 */
export function quorumProgress(decision: CustomEventDecision): string | null {
  return decision.gateV2?.quorum?.progress ?? null;
}

/** "25 min", "3 h", "6 d" — the core's own words for a span (`core::gate_release`). */
function span(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 60) return `${Math.max(1, minutes)} min`;
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? `${hours} h` : `${Math.floor(hours / 24)} d`;
}

/**
 * The line an open gate shows when an approval would wait, at `nowMs`, or null when nothing would
 * hold it any more. The same words as `core::gate_release::release_line`, which the phones call.
 */
export function releaseLine(decision: CustomEventDecision, nowMs: number): string | null {
  const release = decision.gateV2?.release;
  if (!release) return null;
  const hold = release.holdForMinutes ? span(release.holdForMinutes * 60_000) : null;
  const at = release.notBefore ? Date.parse(release.notBefore) : Number.NaN;
  const notBefore = Number.isFinite(at) && at > nowMs ? span(at - nowMs) : null;
  if (notBefore && hold) return `If approved, it goes ahead in ${notBefore} at the earliest, and no sooner than ${hold} after the approval.`;
  if (notBefore) return `If approved, it goes ahead in ${notBefore} at the earliest.`;
  if (hold) return `If approved, it waits ${hold} before it goes ahead.`;
  return null;
}

/** Where a held approval is pulled back from: the desktop sends no pull-back. */
export const PULL_BACK_ELSEWHERE = "To pull it back, use your phone or the board.";

/**
 * The line a held receipt shows at `nowMs` (`core::gate_release::held_line`), with where to pull
 * it back; null for a receipt that is not held.
 */
export function heldLine(outcome: CustomEventOutcome, nowMs: number): string | null {
  const held = outcome.held;
  if (!held) return null;
  const at = Date.parse(held.heldUntil);
  const when = Number.isFinite(at) && at > nowMs ? `Goes ahead in ${span(at - nowMs)} unless pulled back.` : "Going ahead now.";
  return `${when} ${PULL_BACK_ELSEWHERE}`;
}
