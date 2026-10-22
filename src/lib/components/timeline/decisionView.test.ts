import { describe, expect, it } from "vitest";

import { answeredWhen, arrangeOptions, optionRole, receiptOutcome } from "./decisionView";

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
