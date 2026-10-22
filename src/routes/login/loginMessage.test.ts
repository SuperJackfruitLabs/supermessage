import { describe, expect, it } from "vitest";
import { loginMessage } from "./loginMessage";

describe("loginMessage", () => {
  it("networkShowsTheHomeserversWords", () => {
    expect(loginMessage({ kind: "network", message: "connection refused" })).toBe(
      "connection refused",
    );
  });

  it("networkWithoutDetailIsGeneric", () => {
    expect(loginMessage({ kind: "network", message: "" })).toBe("Can't reach the homeserver.");
  });

  it("authCopyIsUnchanged", () => {
    expect(loginMessage({ kind: "auth", message: "M_FORBIDDEN" })).toBe(
      "Incorrect username or password.",
    );
  });

  it("networkWhitespaceIsGeneric", () => {
    expect(loginMessage({ kind: "network", message: "  " })).toBe("Can't reach the homeserver.");
  });

  it("storeNamesTheLocalStore", () => {
    const text = loginMessage({ kind: "store", message: "disk full" });
    expect(text.startsWith("Supermessage could not open what it saved on this computer")).toBe(true);
    expect(text.endsWith("disk full")).toBe(true);
  });

  it("otherKindsShowTheCoresMessage", () => {
    expect(loginMessage({ kind: "protocol", message: "M_UNKNOWN" })).toBe("M_UNKNOWN");
  });
});
