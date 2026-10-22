import { describe, expect, it } from "vitest";
import { keyToShow } from "./recoveryKey";

describe("keyToShow", () => {
  it("aKeyArrivingAtAnOpenPanelBecomesTheOneShown", () => {
    // Panel opened with no key (the reader clicked "Encryption recovery"),
    // then `ensureRecovery` finished and handed one over.
    expect(keyToShow(null, "EsTb 8Qn4")).toBe("EsTb 8Qn4");
  });
  it("aNewerKeyReplacesOneAlreadyShown", () => {
    expect(keyToShow("old key", "new key")).toBe("new key");
  });
  it("noIncomingKeyLeavesWhatThePanelHolds", () => {
    // A key from the panel's own reset must survive the prop staying null.
    expect(keyToShow("reset key", null)).toBe("reset key");
    expect(keyToShow(null, null)).toBeNull();
    expect(keyToShow(null, "")).toBeNull();
  });
});
