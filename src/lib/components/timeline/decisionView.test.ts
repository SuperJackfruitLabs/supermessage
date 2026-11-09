import { describe, expect, it } from "vitest";

import type { CustomEventDecision } from "$lib/ipc";
import {
  ALLOW_ELSEWHERE,
  ANSWER_ELSEWHERE,
  APPROVAL_REQUEST_ELSEWHERE,
  CHOOSE_ELSEWHERE,
  RESUME_ELSEWHERE,
  answerElsewhere,
  answeredWhen,
  arrangeOptions,
  optionRole,
  quorumProgress,
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

  it("points to a phone or the console for a permission.v2's allow", () => {
    const allow = { id: "allow_once", label: "Allow once" };
    const reject = { id: "reject_once", label: "Reject" };
    const permission = { requestId: "acps_3f0c:12", options: [allow, reject], approvalsWithheld: null };
    expect(answerElsewhere({ ...base, options: [opt("Reject")], subject: null, permissionV2: permission })).toBe(
      ALLOW_ELSEWHERE,
    );
    const withheld = { ...permission, options: [reject], approvalsWithheld: "Answer it in the console to allow it." };
    expect(answerElsewhere({ ...base, options: [opt("Reject")], subject: null, permissionV2: withheld })).toBe(
      "Answer it in the console to allow it.",
    );
    const rejectOnly = { ...permission, options: [reject] };
    expect(answerElsewhere({ ...base, options: [opt("Reject")], subject: null, permissionV2: rejectOnly })).toBeNull();
  });

  it("points elsewhere for a question bound to a subject", () => {
    const approve = { id: "approve", label: "Approve" };
    const deny = { id: "deny", label: "Deny" };
    const strict = { options: [approve, deny], freeText: null, subject: { schema: "salaam-operation/v1" } };
    expect(answerElsewhere({ ...base, options: [opt("Deny")], subject: null, questionV2: strict })).toBe(
      ANSWER_ELSEWHERE,
    );
    const withheld = { ...strict, options: [deny], approvalsWithheld: "Open the card to approve it." };
    expect(answerElsewhere({ ...base, options: [opt("Deny")], subject: null, questionV2: withheld })).toBe(
      "Open the card to approve it.",
    );
  });

  it("sends a waiting card to a phone or the board", () => {
    const instruction = { kind: "text" as const, label: "What should the next attempt do?", required: true, maxLength: 2730 };
    const attention = {
      boardId: "brd_7c1f",
      cardId: "crd_6b40",
      waitId: "wait_0123456789abcdef",
      options: [{ id: "resume_here", label: "Resume here", input: instruction }, opt("archive")],
    };
    expect(answerElsewhere({ ...base, options: [], subject: null, attentionV2: attention })).toBe(RESUME_ELSEWHERE);
  });

  it("names who may allow a SuperToolkit approval request, with no card to open", () => {
    const allow = { id: "allow_once", label: "Allow once" };
    const deny = { id: "deny", label: "Deny" };
    const request = {
      options: [allow, deny],
      freeText: null,
      subject: { schema: "stk-capability/v1" },
      approvalRequestId: "apr_5d2e9c41",
    };
    expect(answerElsewhere({ ...base, options: [opt("Deny")], subject: null, questionV2: request })).toBe(
      APPROVAL_REQUEST_ELSEWHERE,
    );
    const withheld = { ...request, options: [deny], approvalsWithheld: "It can't be allowed from here." };
    expect(answerElsewhere({ ...base, options: [opt("Deny")], subject: null, questionV2: withheld })).toBe(
      "It can't be allowed from here.",
    );
  });

  it("says why when a gate.v2's approvals were withheld", () => {
    const gate = { gateId: "gate_1", options: [opt("reject")], approvalsWithheld: "Open the card to approve it." };
    expect(answerElsewhere({ ...base, options: [opt("reject")], gateV2: gate })).toBe("Open the card to approve it.");
  });

  it("says where to choose when a gate offers versions", () => {
    const options = ["approve_manual", "approve_automatic", "request_changes", "reject"].map((id) => opt(id));
    const gate = { gateId: "gate_5d72", options, approvalsWithheld: null, variants: [{ id: "short" }] };
    const legacy = [opt("request_changes"), opt("reject")];
    expect(answerElsewhere({ ...base, options: legacy, gateV2: gate })).toBe(CHOOSE_ELSEWHERE);
  });
});

describe("quorumProgress", () => {
  const base: CustomEventDecision = { prompt: "Approve?", options: [opt("approve"), opt("reject")], subject: "gate_6e19" };

  it("is the core's line on a gate that needs more than one approval", () => {
    const quorum = { required: 2, progress: "1 of 2 approvals. Waiting for 1 more.", approvedByYou: false };
    const gate = { gateId: "gate_6e19", options: [opt("approve"), opt("reject")], approvalsWithheld: null, quorum };
    expect(quorumProgress({ ...base, gateV2: gate })).toBe("1 of 2 approvals. Waiting for 1 more.");
  });

  it("is nothing on any other decision", () => {
    expect(quorumProgress(base)).toBeNull();
    const gate = { gateId: "gate_1", options: [opt("approve")], approvalsWithheld: null };
    expect(quorumProgress({ ...base, gateV2: gate })).toBeNull();
  });

  it("leaves a reader who approved told why approve is gone", () => {
    const quorum = { required: 2, progress: "1 of 2 approvals. Waiting for 1 more.", approvedByYou: true };
    const note = "You approved. It passes once enough others approve too.";
    const gate = { gateId: "gate_6e19", options: [opt("reject")], approvalsWithheld: note, quorum };
    expect(answerElsewhere({ ...base, options: [opt("reject")], gateV2: gate })).toBe(note);
  });
});
