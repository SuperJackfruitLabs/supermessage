import { describe, expect, test } from "vitest";
import { turnErrorCard } from "$lib/fixtures/turnError";
import { attemptCount, attemptsToShow, moreAttemptsLabel } from "./turnErrorView";

describe("the attempts on a turn error card", () => {
  test("collapsed, only the model that was asked for is shown", () => {
    const shown = attemptsToShow(turnErrorCard, false);
    expect(shown.map((a) => a.source)).toEqual(["kimi-coding / k2p6"]);
  });

  test("expanded, the whole chain is shown in order", () => {
    const shown = attemptsToShow(turnErrorCard, true);
    expect(shown.map((a) => a.source)).toEqual([
      "kimi-coding / k2p6",
      "opencode-go / hy3-preview",
      "opencode-go / qwen3.7-plus",
    ]);
  });

  test("the disclosure counts the lines it hides", () => {
    expect(moreAttemptsLabel(turnErrorCard)).toBe("2 more attempts");
    expect(
      moreAttemptsLabel({ ...turnErrorCard, attempts: turnErrorCard.attempts.slice(0, 2) }),
    ).toBe("1 more attempt");
  });

  test("with one attempt or none there is nothing to disclose", () => {
    const one = { ...turnErrorCard, attempts: turnErrorCard.attempts.slice(0, 1) };
    expect(moreAttemptsLabel(one)).toBeNull();
    expect(attemptsToShow(one, false)).toHaveLength(1);

    const none = { ...turnErrorCard, attempts: [] };
    expect(moreAttemptsLabel(none)).toBeNull();
    expect(attemptsToShow(none, true)).toEqual([]);
  });

  test("a folded line says how many times, a single one says nothing", () => {
    const [asked, , qwen] = turnErrorCard.attempts;
    expect(attemptCount(qwen)).toBe("×4");
    expect(attemptCount(asked)).toBeNull();
  });
});
