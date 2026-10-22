import { describe, expect, it } from "vitest";
import { errorMessage } from "./errorMessage";

// Tauri rejects `invoke` with the serialized CoreError: a plain object, never an Error.
const core = (kind: string, message = "") => ({ kind, message });

describe("errorMessage", () => {
  it("reasonTooLong shows the core's words, capitalised", () => {
    expect(
      errorMessage(core("reasonTooLong", "that reason is 2105 characters; reports allow at most 2000")),
    ).toBe("That reason is 2105 characters; reports allow at most 2000");
  });

  it("networkShowsTheHomeserversWordsOrTheDefault", () => {
    expect(errorMessage(core("network", "connection refused"))).toBe("connection refused");
    expect(errorMessage(core("network"))).toBe("Can't reach the homeserver.");
  });
  it("protocolIsNotReportedAsUnreachable", () => {
    expect(errorMessage(core("protocol", "M_FORBIDDEN: no"))).toBe(
      "The homeserver sent something this app didn't understand.",
    );
  });
  it("notReadyIsStillConnecting", () => {
    expect(errorMessage(core("notReady", "x"))).toBe("Still connecting.");
  });
  it("roomChangedSaysTheRoomIsNoLongerOpen", () => {
    expect(errorMessage(core("roomChanged", "x"))).toBe(
      "That room is no longer open — nothing was sent.",
    );
  });
  it("refusedIsShownAsIsOrDefault", () => {
    expect(errorMessage(core("refused", "Nothing was sent."))).toBe("Nothing was sent.");
    expect(errorMessage(core("refused"))).toBe("The homeserver declined.");
  });
  it("authAndStoreCarryTheirDetail", () => {
    expect(errorMessage(core("auth"))).toBe("Signed out. Sign in again to continue.");
    expect(errorMessage(core("auth", "bad token"))).toBe("bad token");
    expect(errorMessage(core("store"))).toBe("Couldn't read this device's local store.");
  });
  it("unknownKindsAndNonCoreErrorsGetASensibleDefault", () => {
    expect(errorMessage(core("somethingNew", "core words"))).toBe("core words");
    expect(errorMessage(core("somethingNew"))).toBe("Something went wrong.");
    expect(errorMessage(new Error("boom"))).toBe("boom");
    expect(errorMessage("weird")).toBe("Something went wrong.");
    expect(errorMessage(null)).toBe("Something went wrong.");
  });
});
