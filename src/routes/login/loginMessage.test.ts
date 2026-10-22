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
});
