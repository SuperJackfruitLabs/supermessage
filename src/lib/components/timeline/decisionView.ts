import type { CustomEventDecisionOption } from "$lib/ipc";

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
