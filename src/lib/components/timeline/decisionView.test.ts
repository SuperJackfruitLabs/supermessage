import { describe, expect, it } from "vitest";

import type { CustomEventDecision } from "$lib/ipc";
import {
  ANSWER_ELSEWHERE,
  answerElsewhere,
  answeredWhen,
  arrangeOptions,
  optionRole,
  receiptOutcome,
} from "./decisionView";

const opt = (id: string, label = id) => ({ id, label });

describe("optionRole", () => {
  it("is by id, case-insensitive", () => {
    expect(optionRole(opt("Reject"))).toBe("reject");
    expect(optionRole(opt("approve"))).toBe("approve");
    expect(optionRole(opt("request_changes"))).toBe("requestChanges");
    expect(optionRole(opt("decline"))).toBe("other");
  });
});

describe("arrangeOptions", () => {
  it("puts reject last", () => {
    const [reject, approve, changes] = [opt("reject"), opt("approve"), opt("request_changes")];
    expect(arrangeOptions([reject, approve, changes])).toEqual({
      primary: approve,
      secondaries: [changes],
      rejections: [reject],
    });
  });

  it("has no primary when every option is a reject", () => {
    const r = opt("reject");
    expect(arrangeOptions([r])).toEqual({ primary: null, secondaries: [], rejections: [r] });
  });
});

describe("receiptOutcome", () => {
  it("names the four outcomes", () => {
    expect(receiptOutcome(opt("approve"))).toBe("Approved by you");
    expect(receiptOutcome(opt("request_changes"))).toBe("Changes requested by you");
    expect(receiptOutcome(opt("reject"))).toBe("Rejected by you");
    expect(receiptOutcome(opt("Allow once", "Allow once"))).toBe("You chose “Allow once”");
  });
});

describe("answeredWhen", () => {
  it("says just now under a minute, then relative minutes and hours", () => {
    const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });
    expect(answeredWhen(0, 59_000)).toBe("just now");
    expect(answeredWhen(0, 120_000)).toBe(rtf.format(-2, "minute"));
    expect(answeredWhen(0, 3 * 3_600_000)).toBe(rtf.format(-3, "hour"));
  });
});

describe("answerElsewhere", () => {
  const base: CustomEventDecision = { prompt: "Ship it?", options: [opt("approve")], subject: "gate_1" };

  it("is null for a v1 gate and a permission request", () => {
    expect(answerElsewhere(base)).toBeNull();
    expect(answerElsewhere({ ...base, subject: null })).toBeNull();
  });

  it("points elsewhere for a follow-up and a question that takes text", () => {
    const delivery = { gateId: "gate_1", options: [], approvalsWithheld: null };
    expect(answerElsewhere({ ...base, options: [], subject: null, deliveryV2: delivery })).toBe(ANSWER_ELSEWHERE);
    expect(
      answerElsewhere({ ...base, subject: null, questionV2: { options: [], freeText: { maxLength: 2000 } } }),
    ).toBe(ANSWER_ELSEWHERE);
    const typed = { id: "other", label: "Other", input: { kind: "text" as const, label: "What?", required: true, maxLength: 500 } };
    expect(answerElsewhere({ ...base, subject: null, questionV2: { options: [typed], freeText: null } })).toBe(
      ANSWER_ELSEWHERE,
    );
    expect(
      answerElsewhere({ ...base, subject: null, questionV2: { options: [opt("run")], freeText: null } }),
    ).toBeNull();
  });

  it("says why when a gate.v2's approvals were withheld", () => {
    const gate = { gateId: "gate_1", options: [opt("reject")], approvalsWithheld: "Open the card to approve it." };
    expect(answerElsewhere({ ...base, options: [opt("reject")], gateV2: gate })).toBe("Open the card to approve it.");
  });
});
