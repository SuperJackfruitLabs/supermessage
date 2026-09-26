import { describe, expect, test } from "vitest";
import { turnErrorCard } from "$lib/fixtures/turnError";
import { attemptCount, attemptsToShow, fallbacks, moreAttemptsLabel, repeatsLabel } from "./turnErrorView";

// The headline already says the first attempt — the model that was asked for
// ("Usage limit reached · kimi-coding / k2p6"). Listing it again under the
// headline said the same thing twice, and "2 more attempts" beside it read as
// if the card were hiding the cause. What is under the headline is what the
// agent fell back to.
describe("the attempts on a turn error card", () => {
  test("the fallbacks are the attempts after the headline's own", () => {
    expect(fallbacks(turnErrorCard).map((a) => a.source)).toEqual([
      "opencode-go / hy3-preview",
      "opencode-go / qwen3.7-plus",
    ]);
  });

  test("collapsed, no attempt is repeated under the headline", () => {
    expect(attemptsToShow(turnErrorCard, false)).toEqual([]);
  });

  test("expanded, the fallbacks are shown in order", () => {
    expect(attemptsToShow(turnErrorCard, true).map((a) => a.source)).toEqual([
      "opencode-go / hy3-preview",
      "opencode-go / qwen3.7-plus",
    ]);
  });

  test("the disclosure says how many models the agent fell back to", () => {
    expect(moreAttemptsLabel(turnErrorCard)).toBe("Fell back to 2 models");
    expect(moreAttemptsLabel({ ...turnErrorCard, attempts: turnErrorCard.attempts.slice(0, 2) })).toBe(
      "Fell back to 1 model",
    );
  });

  test("with no fallback there is nothing to disclose", () => {
    const one = { ...turnErrorCard, attempts: turnErrorCard.attempts.slice(0, 1) };
    expect(moreAttemptsLabel(one)).toBeNull();
    expect(attemptsToShow(one, true)).toEqual([]);
    const none = { ...turnErrorCard, attempts: [] };
    expect(moreAttemptsLabel(none)).toBeNull();
    expect(repeatsLabel(none)).toBeNull();
  });

  test("the headline's model retried is said once, not hidden", () => {
    // Pi retries a 529 on the same model: one folded attempt, ×3.
    const [asked] = turnErrorCard.attempts;
    const retried = { ...turnErrorCard, attempts: [{ ...asked, count: 3 }] };
    expect(repeatsLabel(retried)).toBe("Tried 3 times");
    expect(repeatsLabel(turnErrorCard)).toBeNull();
  });

  test("an attempt list that does not start with the headline's model is shown whole", () => {
    const [, hy3, qwen] = turnErrorCard.attempts;
    const other = { ...turnErrorCard, attempts: [hy3, qwen] };
    expect(fallbacks(other)).toHaveLength(2);
    expect(repeatsLabel(other)).toBeNull();
  });

  test("a folded line says how many times, a single one says nothing", () => {
    const [asked, , qwen] = turnErrorCard.attempts;
    expect(attemptCount(qwen)).toBe("×4");
    expect(attemptCount(asked)).toBeNull();
  });
});
